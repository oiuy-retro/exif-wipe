/*
 * Exif Wipe - removes metadata from photos in place on Android.
 * Copyright (C) 2026 oiuy-retro
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.oiuy.exifwipe

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.oiuy.exifwipe.meta.WipeOptions
import com.oiuy.exifwipe.strip.StripException
import com.oiuy.exifwipe.strip.UnsupportedFormatException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Share target: removes metadata from each shared photo's stored original, then closes. */
class WipeActivity : ComponentActivity() {
    private sealed interface Ui {
        data class Working(val done: Int, val total: Int) : Ui
        data class Report(val title: String, val lines: List<String>, val details: String?) : Ui
    }

    private var ui by mutableStateOf<Ui>(Ui.Working(0, 0))

    private var pendingSender: CompletableDeferred<Boolean>? = null
    private val senderLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            pendingSender?.complete(it.resultCode == Activity.RESULT_OK)
        }

    private var pendingPermissions: CompletableDeferred<Unit>? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            pendingPermissions?.complete(Unit)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ExifWipeTheme {
                when (val state = ui) {
                    is Ui.Working -> Progress(state)
                    is Ui.Report -> Report(state, ::copy) { finish() }
                }
            }
        }
        val uris = sharedUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(applicationContext, "Exif Wipe: no image was shared", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        lifecycleScope.launch {
            // Nothing that goes wrong while processing may crash the app: show it instead.
            try {
                process(uris)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val entry = ErrorLog.entry(this@WipeActivity, "Unexpected error while processing ${uris.size} shared item(s)", t, options)
                ErrorLog.record(this@WipeActivity, entry)
                ui = Ui.Report(
                    "Nothing was changed",
                    listOf("Unexpected error: ${describe(t)}. The photos were left unchanged unless a message above said otherwise."),
                    entry,
                )
            }
        }
    }

    private fun sharedUris(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        val list = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(list::add)
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(list::addAll)
        }
        if (list.isEmpty()) {
            intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(list::add) }
        }
        return list.toList()
    }

    private data class Item(
        val name: String,
        var resolution: Resolution,
        var message: String? = null,
        var outcome: WipeOutcome? = null,
        var warning: String? = null,
        var error: Throwable? = null,
    )

    private var options: WipeOptions? = null

    private fun describe(t: Throwable): String {
        val root = generateSequence(t) { it.cause?.takeIf { c -> c !== it } }.last()
        val base = "${t.javaClass.simpleName}${t.message?.let { ": $it" } ?: ""}"
        return if (root === t) base else "$base (cause: ${root.javaClass.simpleName}${root.message?.let { ": $it" } ?: ""})"
    }

    private suspend fun process(uris: List<Uri>) {
        val resolver = TargetResolver(this)
        val writer = InPlaceWriter(this)
        val options = try {
            SettingsStore(this).load()
        } catch (e: Exception) {
            WipeOptions.DEFAULT
        }
        this.options = options
        ui = Ui.Working(0, uris.size)

        val items = withContext(Dispatchers.IO) { uris.map { resolve(resolver, it) } }

        // Some links can only be traced back to the stored photo with read access to photos.
        if (items.any { it.resolution is Resolution.NeedsReadPermission }) {
            requestPermissions(TargetResolver.readPermissions())
            withContext(Dispatchers.IO) {
                for (item in items) {
                    val r = item.resolution
                    if (r is Resolution.NeedsReadPermission) {
                        item.resolution = if (TargetResolver.hasReadPermission(this@WipeActivity)) {
                            resolve(resolver, r.shared).resolution.let {
                                if (it is Resolution.NeedsReadPermission) Resolution.Failed(r.shared, r.name, NOT_LOCAL_REASON) else it
                            }
                        } else {
                            Resolution.Failed(r.shared, r.name, "Photo access was not allowed, so the stored original could not be found.")
                        }
                    }
                }
            }
        }

        val found = items.mapNotNull { it.resolution as? Resolution.Found }

        // Before Android 11 there is no edit grant that also allows reading, so comparing an
        // inferred original with the shared copy needs read access to photos.
        if (Build.VERSION.SDK_INT < 30 && found.any { it.mustMatch } && !TargetResolver.hasReadPermission(this)) {
            requestPermissions(TargetResolver.readPermissions())
        }
        val media = found.filter { it.mediaStore }

        // Ask for permission to modify the photos.
        var editDenied = false
        if (media.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= 30) {
                val needGrant = media.map { it.target }.distinct().filter { !canWrite(it) }
                if (needGrant.isNotEmpty()) {
                    val request = MediaStore.createWriteRequest(contentResolver, needGrant)
                    editDenied = !launchSender(request.intentSender)
                }
            } else if (Build.VERSION.SDK_INT <= 28 &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
                editDenied = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            }
        }

        var done = 0
        for (item in items) {
            val r = item.resolution
            if (r is Resolution.Found) {
                if (editDenied && r.mediaStore) {
                    item.message = "Permission to modify the photo was not given."
                } else {
                    wipeOne(resolver, writer, item, r, options)
                }
            } else if (r is Resolution.Failed) {
                item.message = r.reason
            }
            done++
            ui = Ui.Working(done, items.size)
        }

        report(items)
    }

    private fun resolve(resolver: TargetResolver, uri: Uri): Item {
        val r = try {
            resolver.resolve(uri)
        } catch (e: Throwable) {
            Resolution.Failed(uri, uri.lastPathSegment ?: "image", "Could not read the shared image (${describe(e)}).")
        }
        return Item(r.name, r)
    }

    private suspend fun wipeOne(resolver: TargetResolver, writer: InPlaceWriter, item: Item, r: Resolution.Found, options: WipeOptions) {
        var attempts = 0
        while (true) {
            try {
                withContext(Dispatchers.IO) {
                    if (r.mustMatch && !resolver.confirmMatch(r)) {
                        throw StripException(
                            "The shared copy does not match any photo stored on this phone, so nothing was changed. $NOT_LOCAL_REASON",
                        )
                    }
                    val result = writer.wipe(r.target, options)
                    item.outcome = result.outcome
                    item.warning = result.warning
                }
                return
            } catch (e: SecurityException) {
                // Android 10: ask for permission to modify this photo, then try again once.
                if (Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException && attempts == 0) {
                    attempts++
                    if (launchSender(e.userAction.actionIntent.intentSender)) continue
                    item.message = "Permission to modify the photo was not given."
                } else {
                    item.message = if (r.mediaStore) {
                        "Android did not allow Exif Wipe to modify this photo."
                    } else {
                        "The app that shared this file does not allow it to be modified. $NOT_LOCAL_REASON"
                    }
                }
                return
            } catch (e: UnsupportedFormatException) {
                item.message = "${e.format.label} files are not supported. The file was left unchanged."
                return
            } catch (e: RestoreFailedException) {
                item.message = e.message
                item.error = e
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                item.message = "Not enough memory to process this photo. The original was left unchanged."
                item.error = e
                return
            } catch (e: Throwable) {
                // Includes Errors (for example a failed class initialisation), which must not crash the app.
                item.message = if (e is Exception && e.message != null) "${e.message}. The original was left unchanged."
                else "Unexpected error (${describe(e)}). The original was left unchanged."
                item.error = e
                return
            }
        }
    }

    private fun canWrite(uri: Uri): Boolean =
        checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    private suspend fun launchSender(sender: IntentSender): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingSender = deferred
        senderLauncher.launch(IntentSenderRequest.Builder(sender).build())
        return deferred.await()
    }

    private suspend fun requestPermissions(permissions: Array<String>) {
        val deferred = CompletableDeferred<Unit>()
        pendingPermissions = deferred
        permissionLauncher.launch(permissions)
        deferred.await()
    }

    private fun report(items: List<Item>) {
        val cleaned = items.count { it.outcome == WipeOutcome.CLEANED }
        val clean = items.count { it.outcome == WipeOutcome.ALREADY_CLEAN }
        val failed = items.filter { it.outcome == null || it.warning != null }
        val summary = buildString {
            append("Changed metadata of $cleaned of ${items.size} ")
            append(if (items.size == 1) "photo" else "photos")
            if (clean > 0) append(" ($clean needed no change)")
        }
        if (failed.isEmpty()) {
            Toast.makeText(applicationContext, summary, Toast.LENGTH_LONG).show()
            finish()
        } else {
            val lines = failed.map { "${it.name}: ${it.message ?: it.warning ?: "Unknown error."}" }
            val details = buildString {
                append(ErrorLog.entry(this@WipeActivity, summary, null, options))
                failed.forEach { item ->
                    append("\n- ").append(item.name).append(": ").append(item.message ?: item.warning ?: "Unknown error.").append('\n')
                    item.error?.let { append(ErrorLog.stackTrace(it).take(6_000)) }
                }
            }
            ErrorLog.record(this, details)
            ui = Ui.Report(summary, lines, details)
        }
    }

    @Composable
    private fun Progress(state: Ui.Working) {
        Dialog(onDismissRequest = {}) {
            Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
                Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Text(if (state.total > 1) "Removing metadata (${state.done} of ${state.total})" else "Removing metadata")
                }
            }
        }
    }

    private fun copy(text: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("Exif Wipe error details", text))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(applicationContext, "Error details copied", Toast.LENGTH_SHORT).show()
    }

    @Composable
    private fun Report(state: Ui.Report, onCopy: (String) -> Unit, onClose: () -> Unit) {
        AlertDialog(
            onDismissRequest = onClose,
            confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
            dismissButton = state.details?.let { d -> { TextButton(onClick = { onCopy(d) }) { Text("Copy error details") } } },
            title = { Text(state.title) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (state.details != null) {
                        Text(
                            "The details are also saved under \"Last errors\" in the Exif Wipe app.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
        )
    }
}
