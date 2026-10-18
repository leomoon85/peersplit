package com.peersplit.app.util

data class ImportFailure(val expense: ParsedImportExpense, val reason: String)
data class ImportOutcome(val imported: Int, val skipped: Int, val failures: List<ImportFailure>)
data class ImportProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val outcome: ImportOutcome? = null
)
