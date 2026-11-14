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

package com.oiuy.exifwipe.meta

import org.w3c.dom.Attr
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Copies only the XMP properties that belong to kept [Group]s. The packet is parsed, every
 * top-level property of each rdf:Description (written as an attribute or as an element) is
 * sorted into a group by namespace and name, and the rest is removed. Unknown namespaces and
 * identifiers (xmpMM document and instance IDs, edit history, toolkit names) are always removed.
 * Anything that cannot be parsed safely is dropped completely.
 */
object XmpFilter {
    private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val XMLNS = "http://www.w3.org/2000/xmlns/"
    private const val X = "adobe:ns:meta/"

    private const val NS_EXIF = "http://ns.adobe.com/exif/1.0/"
    private const val NS_EXIF_EX = "http://cipa.jp/exif/1.0/"
    private const val NS_TIFF = "http://ns.adobe.com/tiff/1.0/"
    private const val NS_XMP = "http://ns.adobe.com/xap/1.0/"
    private const val NS_PHOTOSHOP = "http://ns.adobe.com/photoshop/1.0/"
    private const val NS_DC = "http://purl.org/dc/elements/1.1/"
    private const val NS_RIGHTS = "http://ns.adobe.com/xap/1.0/rights/"
    private const val NS_IPTC_CORE = "http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/"
    private const val NS_IPTC_EXT = "http://iptc.org/std/Iptc4xmpExt/2008-02-29/"
    private const val NS_AUX = "http://ns.adobe.com/exif/1.0/aux/"
    private const val NS_LR = "http://ns.adobe.com/lightroom/1.0/"
    private const val NS_PLUS = "http://ns.useplus.org/ldf/xmp/1.0/"

    /** Motion photo, container and HDR gain map descriptions; meaningful only next to the embedded data. */
    private val EXTRAS_NS = setOf(
        "http://ns.google.com/photos/1.0/camera/",
        "http://ns.google.com/photos/1.0/container/",
        "http://ns.google.com/photos/1.0/container/item/",
        "http://ns.adobe.com/hdr-gain-map/1.0/",
    )

    fun group(ns: String, name: String): Group? = when (ns) {
        NS_EXIF -> when {
            name == "GPSTimeStamp" || name == "GPSDateStamp" -> Group.DATE
            name.startsWith("GPS") -> Group.LOCATION
            name.startsWith("DateTime") || name.startsWith("SubSec") -> Group.DATE
            name in setOf("ExifVersion", "FlashpixVersion", "ColorSpace", "PixelXDimension", "PixelYDimension",
                "ComponentsConfiguration", "NativeDigest") -> null
            name == "UserComment" -> Group.AUTHOR
            name == "ImageUniqueID" -> Group.CAMERA
            else -> Group.LENS
        }
        NS_EXIF_EX -> when {
            name == "BodySerialNumber" || name == "CameraOwnerName" -> Group.CAMERA
            name.startsWith("OffsetTime") -> Group.DATE
            name == "Photographer" || name == "ImageTitle" || name == "ImageEditor" -> Group.AUTHOR
            name.endsWith("Software") -> Group.CAMERA
            else -> Group.LENS
        }
        NS_TIFF -> when (name) {
            "Make", "Model", "Software" -> Group.CAMERA
            "DateTime" -> Group.DATE
            "Artist", "Copyright", "ImageDescription" -> Group.AUTHOR
            else -> null
        }
        NS_XMP -> when (name) {
            "CreateDate", "ModifyDate", "MetadataDate" -> Group.DATE
            "CreatorTool" -> Group.CAMERA
            "Rating", "Label", "Title", "Description", "Nickname" -> Group.AUTHOR
            else -> null
        }
        NS_PHOTOSHOP -> when (name) {
            "DateCreated" -> Group.DATE
            "City", "State", "Country" -> Group.LOCATION
            "History", "DocumentAncestors", "ICCProfile", "ColorMode" -> null
            else -> Group.AUTHOR
        }
        NS_DC -> if (name == "format") null else Group.AUTHOR
        NS_RIGHTS, NS_LR, NS_PLUS -> Group.AUTHOR
        NS_IPTC_CORE -> if (name == "Location" || name == "CountryCode") Group.LOCATION else Group.AUTHOR
        NS_IPTC_EXT -> if (name == "LocationCreated" || name == "LocationShown") Group.LOCATION else Group.AUTHOR
        NS_AUX -> when (name) {
            "SerialNumber", "OwnerName", "ImageNumber", "Firmware" -> Group.CAMERA
            "Lens", "LensInfo", "LensID", "LensSerialNumber" -> Group.LENS
            else -> null
        }
        in EXTRAS_NS -> Group.EXTRAS
        else -> null
    }

    /** Filtered packet, or null if nothing is kept or the packet cannot be parsed safely. */
    fun filter(xmp: String, keep: Set<Group>): ByteArray? {
        if (keep.isEmpty()) return null
        if (xmp.contains("<!DOCTYPE", ignoreCase = true) || xmp.contains("<!ENTITY", ignoreCase = true)) return null
        val start = xmp.indexOf("<x:xmpmeta").let { if (it >= 0) it else xmp.indexOf("<rdf:RDF") }
        if (start < 0) return null
        val endTag = if (xmp.startsWith("<x:xmpmeta", start)) "</x:xmpmeta>" else "</rdf:RDF>"
        val end = xmp.indexOf(endTag, start)
        if (end < 0) return null
        val doc = try {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = true
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            f.isExpandEntityReferences = false
            f.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(xmp.substring(start, end + endTag.length))))
        } catch (_: Exception) {
            return null
        }
        val rdf = doc.getElementsByTagNameNS(RDF, "RDF").item(0) as? Element ?: return null
        val kept = ArrayList<Element>()
        var properties = 0
        for (d in children(rdf)) {
            if (d.namespaceURI != RDF || d.localName != "Description") continue
            val desc = doc.createElementNS(RDF, "rdf:Description")
            desc.setAttributeNS(RDF, "rdf:about", "")
            val attrs = d.attributes
            for (i in 0 until attrs.length) {
                val a = attrs.item(i) as Attr
                val ns = a.namespaceURI ?: continue
                if (ns == XMLNS || ns == RDF) continue
                if (group(ns, a.localName) in keep) {
                    desc.setAttributeNS(ns, a.name, a.value)
                    properties++
                }
            }
            for (c in children(d)) {
                val ns = c.namespaceURI ?: continue
                if (group(ns, c.localName) in keep) {
                    desc.appendChild(c.cloneNode(true))
                    properties++
                }
            }
            if (desc.attributes.length > 1 || desc.hasChildNodes()) kept += desc
        }
        if (properties == 0) return null
        val sb = StringBuilder()
        sb.append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
        sb.append("<x:xmpmeta xmlns:x=\"").append(X).append("\">\n")
        sb.append("<rdf:RDF xmlns:rdf=\"").append(RDF).append("\">\n")
        for (d in kept) { write(d, sb, mutableMapOf("rdf" to RDF, "x" to X)); sb.append('\n') }
        sb.append("</rdf:RDF>\n</x:xmpmeta>\n<?xpacket end=\"w\"?>")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun children(e: Element): List<Element> {
        val out = ArrayList<Element>()
        var n = e.firstChild
        while (n != null) { if (n is Element) out += n; n = n.nextSibling }
        return out
    }

    /** Serializes [e], declaring each namespace prefix the first time it is used in a subtree. */
    private fun write(e: Element, sb: StringBuilder, declared: MutableMap<String, String>) {
        val scope = declared.toMutableMap()
        val decl = StringBuilder()
        fun use(prefix: String?, ns: String?) {
            if (prefix == null || ns == null || prefix == "xml") return
            if (scope[prefix] != ns) { scope[prefix] = ns; decl.append(" xmlns:").append(prefix).append("=\"").append(escape(ns)).append('"') }
        }
        use(e.prefix, e.namespaceURI)
        val attrs = StringBuilder()
        val list = e.attributes
        for (i in 0 until list.length) {
            val a = list.item(i) as Attr
            if (a.namespaceURI == XMLNS || a.name == "xmlns") continue
            use(a.prefix, a.namespaceURI)
            attrs.append(' ').append(a.name).append("=\"").append(escape(a.value)).append('"')
        }
        sb.append('<').append(e.tagName).append(decl).append(attrs)
        if (!e.hasChildNodes()) { sb.append("/>"); return }
        sb.append('>')
        var n = e.firstChild
        while (n != null) {
            when (n.nodeType) {
                Node.ELEMENT_NODE -> write(n as Element, sb, scope)
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> sb.append(escape(n.nodeValue))
            }
            n = n.nextSibling
        }
        sb.append("</").append(e.tagName).append('>')
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
