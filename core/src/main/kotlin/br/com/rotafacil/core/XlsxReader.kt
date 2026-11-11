package br.com.rotafacil.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/**
 * Leitor mínimo de .xlsx (Open XML) sem dependências externas: lê a primeira planilha da pasta de trabalho.
 * Todos os valores são devolvidos como texto, exatamente como estão armazenados no arquivo.
 */
object XlsxReader {
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    fun read(bytes: ByteArray): RawTable {
        val entries = unzip(bytes)
        val sheetPath = findFirstSheetPath(entries)
        val sheetXml = entries[sheetPath]
            ?: throw UnsupportedFormatException("Não encontrei nenhuma planilha dentro do arquivo .xlsx.")
        val shared = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val rows = parseSheet(sheetXml, shared)
        val sheetName = firstSheetName(entries)
        if (rows.isEmpty()) return RawTable(emptyList(), emptyList(), sheetName)
        return RawTable(rows.first().map { it.trim() }, rows.drop(1), sheetName)
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name
                if (!(name.startsWith("xl/") || name == "[Content_Types].xml")) continue
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var total = 0L
                while (true) {
                    val n = zip.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > MAX_ENTRY_BYTES) throw UnsupportedFormatException("Arquivo .xlsx grande demais ou corrompido.")
                    buf.write(chunk, 0, n)
                }
                out[name] = buf.toByteArray()
            }
        }
        return out
    }

    private fun newParser() = SAXParserFactory.newInstance().apply {
        isNamespaceAware = false
        try { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) { }
    }.newSAXParser()

    private fun parse(xml: ByteArray, handler: DefaultHandler) =
        newParser().parse(InputSource(ByteArrayInputStream(xml)), handler)

    private class WorkbookHandler : DefaultHandler() {
        var firstName: String? = null
        var firstRid: String? = null
        override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
            if (qName.substringAfter(':') == "sheet" && firstRid == null) {
                firstName = a.getValue("name")
                firstRid = a.getValue("r:id") ?: a.getValue("id")
            }
        }
    }

    private class RelsHandler : DefaultHandler() {
        val targets = HashMap<String, String>()
        override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
            if (qName == "Relationship") {
                val id = a.getValue("Id"); val t = a.getValue("Target")
                if (id != null && t != null) targets[id] = t
            }
        }
    }

    private fun firstSheetName(entries: Map<String, ByteArray>): String? =
        entries["xl/workbook.xml"]?.let { WorkbookHandler().also { h -> parse(it, h) }.firstName }

    private fun findFirstSheetPath(entries: Map<String, ByteArray>): String {
        val wb = entries["xl/workbook.xml"]
        val rels = entries["xl/_rels/workbook.xml.rels"]
        if (wb != null && rels != null) {
            val wh = WorkbookHandler().also { parse(wb, it) }
            val rh = RelsHandler().also { parse(rels, it) }
            val target = wh.firstRid?.let { rh.targets[it] }
            if (target != null) {
                val clean = target.trimStart('/')
                return if (clean.startsWith("xl/")) clean else "xl/$clean"
            }
        }
        return entries.keys.filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }.minOrNull()
            ?: "xl/worksheets/sheet1.xml"
    }

    private fun parseSharedStrings(xml: ByteArray): List<String> {
        val list = ArrayList<String>()
        parse(xml, object : DefaultHandler() {
            var inSi = false; var inT = false; var phoneticDepth = 0
            val sb = StringBuilder()
            override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                when (qName) {
                    "si" -> { inSi = true; sb.setLength(0) }
                    "rPh" -> phoneticDepth++
                    "t" -> if (inSi && phoneticDepth == 0) inT = true
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (inT) sb.append(ch, start, length) }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (qName) {
                    "t" -> inT = false
                    "rPh" -> phoneticDepth--
                    "si" -> { inSi = false; list.add(sb.toString()) }
                }
            }
        })
        return list
    }

    private fun columnIndex(ref: String): Int {
        var n = 0
        for (c in ref) {
            if (c in 'A'..'Z') n = n * 26 + (c - 'A' + 1)
            else if (c in 'a'..'z') n = n * 26 + (c - 'a' + 1)
            else break
        }
        return n - 1
    }

    private fun parseSheet(xml: ByteArray, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        parse(xml, object : DefaultHandler() {
            var cur: MutableMap<Int, String>? = null
            var colIdx = 0; var nextCol = 0
            var type: String? = null
            var inV = false; var inT = false
            val v = StringBuilder(); val t = StringBuilder()
            override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                when (qName) {
                    "row" -> { cur = HashMap(); nextCol = 0 }
                    "c" -> {
                        val ref = a.getValue("r")
                        colIdx = if (ref != null) columnIndex(ref) else nextCol
                        nextCol = colIdx + 1
                        type = a.getValue("t"); v.setLength(0); t.setLength(0)
                    }
                    "v" -> { inV = true; v.setLength(0) }
                    "t" -> inT = true
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inV) v.append(ch, start, length) else if (inT) t.append(ch, start, length)
            }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (qName) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "c" -> {
                        val text = when (type) {
                            "s" -> v.toString().trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            "inlineStr" -> t.toString()
                            "b" -> if (v.toString().trim() == "1") "TRUE" else "FALSE"
                            else -> v.toString().trim()
                        }
                        if (text.isNotEmpty()) cur?.put(colIdx, text)
                    }
                    "row" -> {
                        val m = cur
                        if (m != null && m.isNotEmpty()) {
                            val width = (m.keys.maxOrNull() ?: -1) + 1
                            rows.add(List(width) { m[it] ?: "" })
                        }
                        cur = null
                    }
                }
            }
        })
        return rows
    }
}
