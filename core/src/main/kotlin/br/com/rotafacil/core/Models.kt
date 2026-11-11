package br.com.rotafacil.core

/** Campos que o importador sabe reconhecer numa planilha. */
enum class Field(val label: String, val required: Boolean) {
    TRACKING("Código de rastreamento (SPX)", true),
    ADDRESS("Endereço", true),
    STOP("Parada (Stop)", false),
    SEQUENCE("Ordem (Sequence)", false),
    NEIGHBORHOOD("Bairro", false),
    CITY("Cidade", false),
    ZIP("CEP", false),
    LATITUDE("Latitude", false),
    LONGITUDE("Longitude", false),
    BATCH("ID do lote (AT ID)", false),
}

/** Tabela crua, exatamente como lida do arquivo (tudo texto). */
data class RawTable(
    val headers: List<String>,
    val rows: List<List<String>>,
    val sheetName: String? = null,
)

enum class Confidence { HIGH, MEDIUM, LOW }

/** Mapeamento campo -> índice de coluna. */
data class ColumnMapping(val columns: Map<Field, Int>) {
    fun indexOf(field: Field): Int? = columns[field]
    fun has(field: Field): Boolean = columns.containsKey(field)
}

data class MappingResult(
    val mapping: ColumnMapping,
    val confidence: Map<Field, Confidence>,
    val missingRequired: List<Field>,
) {
    val isComplete: Boolean get() = missingRequired.isEmpty()
}

enum class Severity { ERROR, WARNING, INFO }

enum class IssueCode {
    MISSING_TRACKING,
    TRACKING_TRIMMED,
    SUSPICIOUS_CODE,
    DUPLICATE_CODE_IN_FILE,
    MISSING_ADDRESS,
    MISSING_STOP,
    INVALID_STOP,
    INVALID_SEQUENCE,
    DUPLICATE_SEQUENCE,
    INVALID_COORDINATES,
    STOP_NO_COORDINATES,
    STOP_COORDINATE_CONFLICT,
    STOP_ADDRESS_MISMATCH,
    STOP_FIELD_CONFLICT,
    GROUPED_BY_ADDRESS,
    IDENTICAL_ROMANEIO_ALREADY_IMPORTED,
    ALL_CODES_ALREADY_IMPORTED,
    PARTIAL_OVERLAP_WITH_IMPORTED,
    EMPTY_FILE,
}

data class Issue(
    val severity: Severity,
    val code: IssueCode,
    val message: String,
    val row: Int? = null,
    val stop: Int? = null,
)

/** Pacote como veio da planilha. [trackingCode] nunca é alterado além de remover espaços nas pontas. */
data class ParsedPackage(
    val sourceRow: Int,
    val trackingCode: String,
    val lookupKey: String,
    /** Endereço original completo, intacto. */
    val fullAddress: String,
    /** Parte do endereço que difere do endereço-base da parada (ex.: "Bloco 13 apto 102"). */
    val complement: String,
    val neighborhood: String,
    val city: String,
    val zip: String,
    val latitude: Double?,
    val longitude: Double?,
    val stop: Int,
    val sequence: Int,
    val batchId: String,
)

data class ParsedStop(
    val stopNumber: Int,
    val address: String,
    val neighborhood: String,
    val city: String,
    val zip: String,
    val latitude: Double?,
    val longitude: Double?,
    val packages: List<ParsedPackage>,
)

enum class GroupingMode { BY_STOP_COLUMN, BY_ADDRESS }

data class ImportPreview(
    val fileName: String,
    val mapping: ColumnMapping,
    val groupingMode: GroupingMode,
    val packages: List<ParsedPackage>,
    val stops: List<ParsedStop>,
    val issues: List<Issue>,
    val contentHash: String,
) {
    val totalPackages: Int get() = packages.size
    val totalStops: Int get() = stops.size
    val errors: List<Issue> get() = issues.filter { it.severity == Severity.ERROR }
    val warnings: List<Issue> get() = issues.filter { it.severity == Severity.WARNING }
    val canConfirm: Boolean get() = packages.isNotEmpty() && errors.isEmpty()
}
