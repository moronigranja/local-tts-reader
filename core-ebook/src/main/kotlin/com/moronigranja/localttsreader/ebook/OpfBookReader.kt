package com.moronigranja.localttsreader.ebook

import com.moronigranja.localttsreader.ebook.ZipEntries.lookup
import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.TextPassage
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * Shared "OPF package → [Book]" pipeline used by the EPUB and KF8 parsers: container
 * discovery (EPUB), OPF metadata/spine, NCX + nav chapter titles, and lenient XHTML
 * text extraction into paragraph passages.
 *
 * Character handling and structure are shared intentionally — one convention for both
 * formats, so fixes apply everywhere.
 */
internal object OpfBookReader {

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    /** EPUB: locate content.opf through META-INF/container.xml. */
    fun findOpfPath(entries: Map<String, ByteArray>): String {
        val containerBytes = entries.lookup("META-INF/container.xml")
            ?: throw EBookParseException("META-INF/container.xml is missing")
        val doc = parseXml(containerBytes, "container.xml")
        val rootfile = doc.elementsByLocalName("rootfile").firstOrNull()
            ?: throw EBookParseException("container.xml has no rootfile entry")
        val fullPath = rootfile.getAttribute("full-path")
        if (fullPath.isBlank()) throw EBookParseException("rootfile declares no full-path")
        return ZipEntries.normalizePath(fullPath)
    }

    fun parseBook(id: String, entries: Map<String, ByteArray>, opfPath: String, fallbackTitle: String): Book {
        val opfBytes = entries.lookup(opfPath)
            ?: throw EBookParseException("OPF not found in container: $opfPath")
        val opf = parseOpf(opfBytes, opfPath)
        if (opf.spineHrefs.isEmpty()) throw EBookParseException("spine is empty")

        val toc = loadTocTitles(entries, opf)
        val chapters = mutableListOf<Chapter>()
        var chapterIndex = 0
        for (href in opf.spineHrefs) {
            val htmlBytes = entries.lookup(href) ?: continue // broken spine: skip, never crash
            val html = String(htmlBytes, Charsets.UTF_8)
            val passages = extractParagraphs(html).map(::TextPassage)
            if (passages.isEmpty()) continue // decorative/blank chapter
            chapters += Chapter(chapterIndex++, toc[href] ?: firstHeading(html), passages)
        }
        if (chapters.isEmpty()) throw EBookParseException("no readable chapters in spine")

        return Book(
            id = id,
            title = opf.title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            authors = opf.authors,
            chapters = chapters,
        )
    }

    // ------------------------------------------------------------------
    // OPF
    // ------------------------------------------------------------------

    private data class Opf(
        val title: String?,
        val authors: List<String>,
        val spineHrefs: List<String>,
        val ncxHref: String?,
        val navHref: String?,
    )

    private fun parseOpf(bytes: ByteArray, opfPath: String): Opf {
        val doc = parseXml(bytes, "content.opf")
        val opfDir = opfPath.substringBeforeLast('/', "")

        val manifest = linkedMapOf<String, String>()
        var ncxHref: String? = null
        var navHref: String? = null
        for (item in doc.elementsByLocalName("item")) {
            val id = item.getAttribute("id")
            val href = item.getAttribute("href")
            if (id.isNotEmpty() && href.isNotEmpty()) {
                val resolved = resolvePath(opfDir, href)
                manifest[id] = resolved
                if (item.getAttribute("media-type") == "application/x-dtbncx+xml") ncxHref = resolved
                if (item.attributeTokens("properties").contains("nav")) navHref = resolved
            }
        }

        val spineHrefs = doc.elementsByLocalName("itemref").mapNotNull { manifest[it.getAttribute("idref")] }
        val title = doc.elementsByLocalName("title").firstNotNullOfOrNull { it.textContent.trim().ifEmpty { null } }
        val authors = doc.elementsByLocalName("creator").mapNotNull { it.textContent.trim().ifEmpty { null } }
        return Opf(title, authors, spineHrefs, ncxHref, navHref)
    }

    // ------------------------------------------------------------------
    // TOC (EPUB2 NCX + EPUB3 nav), chapter titles
    // ------------------------------------------------------------------

    private fun loadTocTitles(entries: Map<String, ByteArray>, opf: Opf): Map<String, String> {
        val titles = linkedMapOf<String, String>()

        opf.ncxHref?.let { href ->
            entries.lookup(href)?.let { bytes ->
                runCatching { parseXml(bytes, "toc.ncx") }.getOrNull()?.let { doc ->
                    val dir = href.substringBeforeLast('/', "")
                    for (point in doc.elementsByLocalName("navPoint")) {
                        // <navPoint><navLabel><text>…</text></navLabel><content src="…"/></navPoint>
                        val src = point.descendantsByLocalName("content").firstOrNull()
                            ?.getAttribute("src")?.substringBefore('#')?.trim()
                        val label = point.descendantsByLocalName("text").firstOrNull()?.textContent?.trim()
                        if (!src.isNullOrEmpty() && !label.isNullOrEmpty()) {
                            titles[resolvePath(dir, src)] = label
                        }
                    }
                }
            }
        }

        opf.navHref?.let { href ->
            entries.lookup(href)?.let { bytes ->
                runCatching { parseXml(bytes, "nav") }.getOrNull()?.let { doc ->
                    val dir = href.substringBeforeLast('/', "")
                    val tocNav = doc.elementsByLocalName("nav").firstOrNull { it.attributeTokens("type").contains("toc") }
                        ?: doc.elementsByLocalName("nav").firstOrNull()
                    if (tocNav != null) {
                        for (link in tocNav.descendantsByLocalName("a")) {
                            val target = link.getAttribute("href").substringBefore('#').trim()
                            val label = link.textContent.trim()
                            if (target.isNotEmpty() && label.isNotEmpty()) {
                                titles[resolvePath(dir, target)] = label
                            }
                        }
                    }
                }
            }
        }

        return titles
    }

    private fun firstHeading(html: String): String? {
        val match = HEADING_RE.find(html) ?: return null
        val text = decodeEntities(TAG_RE.replace(match.groupValues[1], "")).trim()
        return text.ifEmpty { null }
    }

    // ------------------------------------------------------------------
    // Chapter text extraction (lenient — malformed XHTML is common)
    // ------------------------------------------------------------------

    fun extractParagraphs(html: String): List<String> {
        var s = html
        s = SKIP_BLOCKS.replace(s, "") // head/script/style content must never leak into text
        s = CONTROL_CHARS.replace(s, " ") // MOBI control bytes and friends
        // Block boundaries get a sentinel first, so pretty-printed XHTML (a <p> spanning
        // lines) stays ONE passage: raw whitespace collapses to single spaces afterwards.
        s = BLOCK_BOUNDARY.replace(s, BOUNDARY)
        s = TAG_RE.replace(s, "")
        s = WHITESPACE.replace(s, " ")
        s = decodeEntities(s) // after tag strip: attribute entities are gone with their tags
        s = s.replace(BOUNDARY, "\n")
        return s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------
    // XML (XXE-hardened) + entities
    // ------------------------------------------------------------------

    private fun parseXml(bytes: ByteArray, what: String): Document {
        // Decode HTML-style entities first: real OPFs/NCXs use &nbsp; &rsquo; … which
        // are not valid XML entities and would otherwise fail the parse.
        val decoded = decodeEntities(String(bytes, Charsets.UTF_8))
        try {
            return newDocumentBuilder().parse(ByteArrayInputStream(decoded.toByteArray(Charsets.UTF_8)))
        } catch (e: SAXException) {
            throw EBookParseException("malformed $what", e)
        } catch (e: Exception) {
            throw EBookParseException("could not read $what", e)
        }
    }

    private fun newDocumentBuilder(): DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        } catch (_: ParserConfigurationException) {
            // Feature unsupported: parse anyway; none of the external-entity features
            // below can then be active either (single factory instance).
        }
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        return factory.newDocumentBuilder()
    }

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to "\u00A0", "copy" to "\u00A9", "reg" to "\u00AE", "trade" to "\u2122",
        "mdash" to "\u2014", "ndash" to "\u2013", "hellip" to "\u2026",
        "lsquo" to "\u2018", "rsquo" to "\u2019", "ldquo" to "\u201C", "rdquo" to "\u201D",
        "bull" to "\u2022", "middot" to "\u00B7", "deg" to "\u00B0",
        "agrave" to "\u00E0", "eacute" to "\u00E9", "egrave" to "\u00E8", "iacute" to "\u00ED",
        "oacute" to "\u00F3", "uacute" to "\u00FA", "auml" to "\u00E4", "ouml" to "\u00F6",
        "uuml" to "\u00FC", "ccedil" to "\u00E7", "ntilde" to "\u00F1", "szlig" to "\u00DF",
        "laquo" to "\u00AB", "raquo" to "\u00BB", "times" to "\u00D7", "divide" to "\u00F7",
        "para" to "\u00B6", "sect" to "\u00A7", "dagger" to "\u2020", "Dagger" to "\u2021",
        "permil" to "\u2030", "prime" to "\u2032", "Prime" to "\u2033",
    )

    private val ENTITY_RE = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);")

    private fun decodeEntities(s: String): String = ENTITY_RE.replace(s) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#x") -> body.substring(2).toIntOrNull(16)?.let(::codepointString)
            body.startsWith("#") -> body.substring(1).toIntOrNull(10)?.let(::codepointString)
            else -> NAMED_ENTITIES[body]
        } ?: match.value
    }

    private fun codepointString(codepoint: Int): String? =
        if (codepoint in 0x1..0x10FFFF) String(Character.toChars(codepoint)) else null

    // ------------------------------------------------------------------
    // DOM helpers + path utils + regexes
    // ------------------------------------------------------------------

    private fun Document.elementsByLocalName(name: String): List<Element> {
        val out = mutableListOf<Element>()
        fun walk(root: Element) {
            for (i in 0 until root.childNodes.length) {
                val node = root.childNodes.item(i)
                if (node is Element) {
                    if (node.localName == name || node.nodeName == name) out += node
                    walk(node)
                }
            }
        }
        documentElement?.let(::walk)
        return out
    }

    private fun Element.descendantsByLocalName(name: String): List<Element> {
        val out = mutableListOf<Element>()
        fun walk(node: org.w3c.dom.Node) {
            for (i in 0 until node.childNodes.length) {
                val child = node.childNodes.item(i)
                if (child is Element) {
                    if (child.localName == name || child.nodeName == name) out += child
                    walk(child)
                }
            }
        }
        walk(this)
        return out
    }

    /** Token list of the given attribute (prefix-agnostic: "properties" matches "properties",
     *  "type" matches "epub:type"). Namespace-aware DOM safe. */
    private fun Element.attributeTokens(attributeLocalName: String): Set<String> {
        for (i in 0 until attributes.length) {
            val attribute = attributes.item(i)
            if (attribute?.nodeName?.substringAfterLast(':') == attributeLocalName) {
                return attribute.nodeValue.split(' ').filter { it.isNotEmpty() }.toSet()
            }
        }
        return emptySet()
    }

    private fun resolvePath(baseDir: String, href: String): String =
        ZipEntries.normalizePath(if (href.startsWith('/')) href else "$baseDir/$href")

    /** Private-use char unlikely in real text; scrubbed by CONTROL_CHARS if the source had it. */
    private const val BOUNDARY = "\u0001"

    private val SKIP_BLOCKS = Regex("(?is)<(head|script|style)[\\s\\S]*?</\\1>")
    private val BLOCK_BOUNDARY = Regex(
        "(?i)<(p|div|li|blockquote|section|article|h[1-6]|tr)[^>]*>|" +
            "</(p|div|li|blockquote|section|article|h[1-6]|tr|br)>|<br\\s*/?>",
    )
    private val TAG_RE = Regex("<[^>]+>")
    private val CONTROL_CHARS = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]")
    private val HEADING_RE = Regex("(?i)<h[1-6][^>]*>([\\s\\S]*?)</h[1-6]>")
    private val WHITESPACE = Regex("\\s+")
}
