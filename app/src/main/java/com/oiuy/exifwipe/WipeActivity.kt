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
import com.oiuy.exifwipe.strip.StripException
import com.oiuy.exifwipe.strip.UnsupportedFormatException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Share target: removes metadata from each shared photo's stored original, then closes. */
class WipeActivity : ComponentActivity() {
    private sealed interface Ui {
        data class Working(val done: Int, val total: Int) : Ui
        data class Report(val title: String, val lines: List<String>) : Ui
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
                    is Ui.Report -> Report(state) { finish() }
                }
            }
        }
        val uris = sharedUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(applicationContext, "Exif Wipe: no image was shared", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        lifecycleScope.launch { process(uris) }
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

    private data class Item(val name: String, var resolution: Resolution, var message: String? = null, var outcome: WipeOutcome? = null)

    private suspend fun process(uris: List<Uri>) {
        val resolver = TargetResolver(this)
        val writer = InPlaceWriter(this)
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
                    wipeOne(resolver, writer, item, r)
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
        } catch (e: Exception) {
            Resolution.Failed(uri, uri.lastPathSegment ?: "image", "Could not read the shared image (${e.message}).")
        }
        return Item(r.name, r)
    }

    private suspend fun wipeOne(resolver: TargetResolver, writer: InPlaceWriter, item: Item, r: Resolution.Found) {
        var attempts = 0
        while (true) {
            try {
                withContext(Dispatchers.IO) {
                    if (r.mustMatch && !resolver.confirmMatch(r)) {
                        throw StripException(
                            "The shared copy does not match any photo stored on this phone, so nothing was changed. $NOT_LOCAL_REASON",
                        )
                    }
                    item.outcome = writer.wipe(r.target)
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
                return
            } catch (e: Exception) {
                item.message = "${e.message ?: e.javaClass.simpleName}. The original was left unchanged."
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
        val failed = items.filter { it.outcome == null }
        val summary = buildString {
            append("Removed metadata from $cleaned of ${items.size} ")
            append(if (items.size == 1) "photo" else "photos")
            if (clean > 0) append(" ($clean already had none)")
        }
        if (failed.isEmpty()) {
            Toast.makeText(applicationContext, summary, Toast.LENGTH_LONG).show()
            finish()
        } else {
            ui = Ui.Report(summary, failed.map { "${it.name}: ${it.message ?: "Unknown error."}" })
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

    @Composable
    private fun Report(state: Ui.Report, onClose: () -> Unit) {
        AlertDialog(
            onDismissRequest = onClose,
            confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
            title = { Text(state.title) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            },
        )
    }
}
