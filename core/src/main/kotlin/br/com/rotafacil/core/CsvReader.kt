package br.com.rotafacil.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

class UnsupportedFormatException(message: String) : Exception(message)

object CsvReader {

    /** UTF-8 (com ou sem BOM); se não for UTF-8 válido, assume Windows-1252 (comum em CSV do Excel BR). */
    fun decode(bytes: ByteArray): String {
        var start = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) start = 3
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, start, bytes.size - start)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, start, bytes.size - start, Charset.forName("windows-1252"))
        }
    }

    /** Escolhe o separador mais frequente na primeira linha (fora de aspas). */
    fun detectDelimiter(text: String): Char {
        val counts = mutableMapOf(',' to 0, ';' to 0, '\t' to 0)
        var inQuotes = false
        for (ch in text) {
            if (ch == '"') inQuotes = !inQuotes
            else if (!inQuotes && (ch == '\n' || ch == '\r')) break
            else if (!inQuotes && counts.containsKey(ch)) counts[ch] = counts.getValue(ch) + 1
        }
        return counts.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: ','
    }

    /** Parser compatível com RFC 4180 (aspas, aspas duplas escapadas, quebras de linha dentro de campo). */
    fun parse(text: String, delimiter: Char = detectDelimiter(text)): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        fun endCell() { row.add(cell.toString()); cell.setLength(0) }
        fun endRow() {
            endCell()
            if (row.any { it.isNotBlank() }) rows.add(row)
            row = ArrayList()
        }
        while (i < text.length) {
            val ch = text[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else inQuotes = false
                } else cell.append(ch)
            } else when (ch) {
                '"' -> if (cell.isEmpty()) inQuotes = true else cell.append(ch)
                delimiter -> endCell()
                '\r' -> { if (i + 1 < text.length && text[i + 1] == '\n') i++; endRow() }
                '\n' -> endRow()
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    fun read(bytes: ByteArray): RawTable {
        val rows = parse(decode(bytes))
        if (rows.isEmpty()) return RawTable(emptyList(), emptyList())
        return RawTable(rows.first().map { it.trim() }, rows.drop(1))
    }
}
