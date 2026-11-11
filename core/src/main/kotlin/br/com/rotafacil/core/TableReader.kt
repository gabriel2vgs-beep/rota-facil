package br.com.rotafacil.core

/** Leitor de .xls (Excel 97-2003). Implementado no app Android (biblioteca JXL), pois o core não tem dependências. */
fun interface XlsReader {
    fun read(bytes: ByteArray): RawTable
}

/** Detecta o formato pelo conteúdo do arquivo (assinatura), não só pela extensão. */
class TableReader(private val xlsReader: XlsReader? = null) {

    fun read(fileName: String, bytes: ByteArray): RawTable {
        if (bytes.isEmpty()) throw UnsupportedFormatException("O arquivo está vazio.")
        return when {
            isZip(bytes) -> XlsxReader.read(bytes)
            isOle(bytes) -> (xlsReader
                ?: throw UnsupportedFormatException("Leitura de .xls não está disponível neste ambiente."))
                .read(bytes)
            looksBinary(bytes) -> throw UnsupportedFormatException(
                "Formato não reconhecido ($fileName). Use .xlsx, .xls ou .csv."
            )
            else -> CsvReader.read(bytes)
        }
    }

    private fun isZip(b: ByteArray) =
        b.size >= 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() && b[2] == 0x03.toByte() && b[3] == 0x04.toByte()

    private fun isOle(b: ByteArray) =
        b.size >= 8 && b[0] == 0xD0.toByte() && b[1] == 0xCF.toByte() && b[2] == 0x11.toByte() && b[3] == 0xE0.toByte()

    private fun looksBinary(b: ByteArray): Boolean {
        val n = minOf(b.size, 2048)
        var zeros = 0
        for (i in 0 until n) if (b[i] == 0.toByte()) zeros++
        return zeros > 0
    }
}
