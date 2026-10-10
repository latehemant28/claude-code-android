package com.example.hinglishpdf.pipeline.units

import com.example.hinglishpdf.pipeline.segment.PlaceholderTag
import com.example.hinglishpdf.pipeline.segment.PlaceholderToken
import com.example.hinglishpdf.pipeline.segment.Placeholders
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/**
 * Writes text units as XLIFF 2.1 (OASIS, core namespace
 * urn:oasis:names:tc:xliff:document:2.0): one <file> pointing at the
 * skeleton, <group>s for lists, tables and rows, one <unit> per text unit.
 * Inline codes keep their original markup in <originalData>: a paired code
 * {1}…{/1} becomes <pc dataRefStart dataRefEnd>, a standalone [[IMG_3]]
 * becomes <ph dataRef>; both may be reordered, since word order changes in
 * translation. A unit's kind, level, code labels and the heading a contents
 * entry is linked to go in the Metadata module (mda), which other tools
 * ignore safely. Translations go in <target> with state "translated".
 */
object XliffWriter {

    const val NAMESPACE = "urn:oasis:names:tc:xliff:document:2.0"
    const val METADATA = "urn:oasis:names:tc:xliff:metadata:2.0"

    /**
     * @param targets translations by unit id (placeholder text, one segment
     *   per unit until stage 4 segments them).
     */
    fun write(
        units: List<TextUnit>,
        sourceName: String,
        skeletonHref: String,
        srcLang: String,
        trgLang: String,
        targets: Map<String, String> = emptyMap(),
    ): String {
        val out = StringBuilder()
        out.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        out.append("""<xliff xmlns="$NAMESPACE" xmlns:mda="$METADATA" version="2.1" srcLang="${attr(srcLang)}" trgLang="${attr(trgLang)}">""").append('\n')
        out.append("""<file id="f1" original="${attr(sourceName)}">""").append('\n')
        out.append("""<skeleton href="${attr(skeletonHref)}"/>""").append('\n')

        val (heads, body) = units.partition { it.kind == UnitKind.RUNNING_HEAD }
        val open = ArrayDeque<String>()
        for (unit in body) {
            // Close the groups this unit is not in, open the ones it is in.
            var shared = 0
            while (shared < open.size && shared < unit.groups.size && open[shared] == unit.groups[shared]) shared++
            while (open.size > shared) {
                open.removeLast()
                out.append("</group>\n")
            }
            for (g in unit.groups.drop(shared)) {
                open.addLast(g)
                out.append("""<group id="g-${attr(g)}" name="${attr(g)}">""").append('\n')
            }
            unit(out, unit, targets[unit.id])
        }
        repeat(open.size) { out.append("</group>\n") }
        if (heads.isNotEmpty()) {
            out.append("""<group id="furniture" name="running-heads">""").append('\n')
            heads.forEach { unit(out, it, targets[it.id]) }
            out.append("</group>\n")
        }
        out.append("</file>\n</xliff>\n")
        return out.toString()
    }

    private fun unit(out: StringBuilder, unit: TextUnit, target: String?) {
        out.append("""<unit id="${attr(unit.id)}"""")
        unit.elementId?.let { out.append(""" name="${attr(it)}"""") }
        if (!unit.translate) out.append(""" translate="no"""")
        out.append(""" xml:space="preserve">""")
        out.append("<mda:metadata>")
        out.append("""<mda:metaGroup category="unit">""")
        out.append("""<mda:meta type="kind">${unit.kind.name.lowercase()}</mda:meta>""")
        if (unit.level != 0) out.append("""<mda:meta type="level">${unit.level}</mda:meta>""")
        unit.linkedTo?.let { out.append("""<mda:meta type="sameAs">${text(it)}</mda:meta>""") }
        out.append("</mda:metaGroup>")
        if (unit.codes.isNotEmpty()) {
            out.append("""<mda:metaGroup category="codes">""")
            unit.codes.forEach { out.append("""<mda:meta type="label-${it.id}">${text(it.label)}</mda:meta>""") }
            out.append("</mda:metaGroup>")
        }
        out.append("</mda:metadata>")
        if (unit.codes.isNotEmpty()) {
            out.append("<originalData>")
            for (tag in unit.codes) {
                if (tag.kind == PlaceholderTag.Kind.PAIRED) {
                    val (start, end) = split(tag.markup)
                    out.append("""<data id="d${tag.id}">${data(start)}</data><data id="d${tag.id}e">${data(end)}</data>""")
                } else {
                    out.append("""<data id="d${tag.id}">${data(tag.markup)}</data>""")
                }
            }
            out.append("</originalData>")
        }
        out.append("""<segment id="s1" state="${if (target != null) "translated" else "initial"}">""")
        out.append("<source>").append(inline(unit.text, unit.codes)).append("</source>")
        if (target != null) out.append("<target>").append(inline(target, unit.codes)).append("</target>")
        out.append("</segment></unit>\n")
    }

    /** Placeholder text as XLIFF inline content: <pc> when properly nested, otherwise <sc>/<ec>. */
    internal fun inline(text: String, codes: List<PlaceholderTag>): String {
        val byId = codes.associateBy { it.id }
        val balanced = Placeholders.balanceProblem(text) == null
        val out = StringBuilder()
        for (token in Placeholders.tokenize(text)) {
            when (token) {
                is PlaceholderToken.Text -> out.append(text(token.text))
                is PlaceholderToken.Open -> {
                    val tag = byId[token.id] ?: continue
                    val type = typeOf(tag.label)?.let { """ type="$it"""" }.orEmpty()
                    out.append(
                        if (balanced) """<pc id="${token.id}" dataRefStart="d${token.id}" dataRefEnd="d${token.id}e"$type>"""
                        else """<sc id="${token.id}" dataRef="d${token.id}" isolated="yes"$type/>""",
                    )
                }
                is PlaceholderToken.Close -> {
                    if (token.id !in byId) continue
                    out.append(if (balanced) "</pc>" else """<ec id="${token.id}e" dataRef="d${token.id}e" isolated="yes"/>""")
                }
                is PlaceholderToken.Standalone -> {
                    val tag = byId[token.id] ?: continue
                    val type = (if (tag.label.equals("IMG", ignoreCase = true)) "image" else null)?.let { """ type="$it"""" }.orEmpty()
                    val equiv = tag.markup.takeIf { !it.trimStart().startsWith("<") }?.let { """ equiv="${attr(it)}"""" }.orEmpty()
                    out.append("""<ph id="${token.id}" dataRef="d${token.id}"$type$equiv/>""")
                }
            }
        }
        return out.toString()
    }

    /** "<a href='x'></a>" → "<a href='x'>" and "</a>"; nested "<b><i></i></b>" → "<b><i>" and "</i></b>". */
    internal fun split(markup: String): Pair<String, String> {
        val i = markup.indexOf("</")
        return if (i < 0) markup to "" else markup.substring(0, i) to markup.substring(i)
    }

    private fun typeOf(label: String): String? = when (label.lowercase()) {
        "b", "i", "bi", "u", "em", "strong", "small", "sup", "sub", "span" -> "fmt"
        "a" -> "link"
        "img", "image" -> "image"
        else -> null
    }

    private fun text(s: String): String = escape(s, quotes = false)
    private fun attr(s: String): String = escape(s, quotes = true)

    /** Original data: characters XML cannot hold go in <cp hex>. */
    private fun data(s: String): String {
        val out = StringBuilder()
        for (c in s) {
            if (allowed(c)) out.append(escape(c.toString(), quotes = false)) else out.append("""<cp hex="${"%04X".format(c.code)}"/>""")
        }
        return out.toString()
    }

    private fun escape(s: String, quotes: Boolean): String {
        val out = StringBuilder(s.length)
        for (c in s) {
            when {
                c == '&' -> out.append("&amp;")
                c == '<' -> out.append("&lt;")
                c == '>' -> out.append("&gt;")
                c == '"' && quotes -> out.append("&quot;")
                !allowed(c) -> Unit // not representable outside <data>
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private fun allowed(c: Char) = c == '\t' || c == '\n' || c == '\r' || c.code >= 0x20 && c.code != 0xFFFE && c.code != 0xFFFF
}

/** Reads back what [XliffWriter] wrote (and any XLIFF 2 file using pc / ph / sc / ec). */
object XliffReader {

    data class Unit(
        val id: String,
        val name: String?,
        val translate: Boolean,
        val kind: String?,
        val linkedTo: String?,
        val text: String,
        val codes: List<PlaceholderTag>,
        val target: String?,
    )

    fun read(xml: String): List<Unit> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        return doc.getElementsByTag("unit").map { unit ->
            val meta = unit.getElementsByTag("mda:meta").associate { it.attr("type") to it.text() }
            val data = unit.getElementsByTag("data").associate { it.id() to dataText(it) }
            val ids = data.keys.filter { !it.endsWith("e") }.mapNotNull { it.removePrefix("d").toIntOrNull() }
            val codes = ids.sorted().map { id ->
                val label = meta["label-$id"] ?: "X"
                val end = data["d${id}e"]
                if (end != null) PlaceholderTag(id, PlaceholderTag.Kind.PAIRED, label, data.getValue("d$id") + end)
                else PlaceholderTag(id, PlaceholderTag.Kind.STANDALONE, label, data.getValue("d$id"))
            }
            val labels = codes.associate { it.id to it.label }
            val segments = unit.getElementsByTag("segment") + unit.getElementsByTag("ignorable")
            val ordered = unit.children().filter { it.tagName() == "segment" || it.tagName() == "ignorable" }
            val source = ordered.joinToString("") { s -> s.children().firstOrNull { it.tagName() == "source" }?.let { inline(it, labels) }.orEmpty() }
            val targets = ordered.map { s -> s.children().firstOrNull { it.tagName() == "target" }?.let { inline(it, labels) } }
            val target = if (segments.isNotEmpty() && targets.all { it != null }) targets.joinToString("") else null
            Unit(
                id = unit.id(),
                name = unit.attr("name").ifEmpty { null },
                translate = unit.attr("translate") != "no",
                kind = meta["kind"],
                linkedTo = meta["sameAs"],
                text = source,
                codes = codes,
                target = target,
            )
        }
    }

    private fun dataText(e: Element): String = e.childNodes().joinToString("") { n ->
        when {
            n is TextNode -> n.wholeText
            n is Element && n.tagName() == "cp" -> n.attr("hex").toInt(16).toChar().toString()
            else -> ""
        }
    }

    private fun inline(e: Element, labels: Map<Int, String>): String = e.childNodes().joinToString("") { n ->
        when {
            n is TextNode -> n.wholeText
            n is Element && n.tagName() == "pc" -> "{${n.id()}}" + inline(n, labels) + "{/${n.id()}}"
            n is Element && n.tagName() == "ph" -> n.id().toIntOrNull()?.let { "[[${labels[it] ?: "X"}_$it]]" }.orEmpty()
            n is Element && n.tagName() == "sc" -> "{${n.id()}}"
            n is Element && n.tagName() == "ec" -> "{/${n.attr("startRef").ifEmpty { n.id().removeSuffix("e") }}}"
            n is Element && n.tagName() == "cp" -> n.attr("hex").toInt(16).toChar().toString()
            n is Element -> inline(n, labels) // mrk and the like: keep the text
            else -> ""
        }
    }
}
