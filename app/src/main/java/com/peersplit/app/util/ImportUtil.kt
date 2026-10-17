package com.peersplit.app.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.peersplit.app.blockchain.SplitItem
import com.peersplit.app.data.model.ExpenseCategory
import com.peersplit.app.data.repository.GroupDetail
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class ParsedImportExpense(
    val description: String,
    val amount: Double,
    val category: String,
    val payerName: String,
    val dateString: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class ImportAnalysisResult(
    val totalFound: Int,
    val newExpenses: List<ParsedImportExpense>,
    val skippedDuplicates: Int,
    val totalAmount: Double,
    val detectedPayers: Set<String>,
    val errorMessage: String? = null
)

object ImportUtil {
    private const val TAG = "ImportUtil"

    private val commonDateFormats = listOf(
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()),
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()),
        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()),
        SimpleDateFormat("MM/dd/yyyy", Locale.getDefault()),
        SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
    )

    /**
     * Parses an imported CSV, TSV, or XLSX spreadsheet and prepares expenses for addition.
     */
    fun analyzeFile(
        context: Context,
        uri: Uri,
        groupDetail: GroupDetail
    ): ImportAnalysisResult {
        return try {
            val contentResolver = context.contentResolver
            val mimeType = contentResolver.getType(uri) ?: ""
            val fileName = uri.lastPathSegment?.lowercase() ?: ""

            val rows: List<List<String>> = if (fileName.endsWith(".xlsx") || mimeType.contains("spreadsheetml")) {
                parseXlsx(contentResolver.openInputStream(uri) ?: return ImportAnalysisResult(0, emptyList(), 0, 0.0, emptySet(), "Could not open file"))
            } else {
                parseCsv(contentResolver.openInputStream(uri) ?: return ImportAnalysisResult(0, emptyList(), 0, 0.0, emptySet(), "Could not open file"))
            }

            if (rows.isEmpty()) {
                return ImportAnalysisResult(0, emptyList(), 0, 0.0, emptySet(), "File is empty or could not be read.")
            }

            parseRowsToExpenses(rows, groupDetail)
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing import file", e)
            ImportAnalysisResult(0, emptyList(), 0, 0.0, emptySet(), "Failed to read file: ${e.message}")
        }
    }

    private fun parseCsv(inputStream: InputStream): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                if (currentLine.isBlank()) continue
                rows.add(parseCsvLine(currentLine))
            }
        }
        return rows
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        val delimiter = if (line.contains("\t") && !line.contains(",")) '\t' else ','

        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\"') {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '\"') {
                    sb.append('\"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
            } else if (c == delimiter && !inQuotes) {
                result.add(cleanCellValue(sb.toString()))
                sb.clear()
            } else {
                sb.append(c)
            }
            i++
        }
        result.add(cleanCellValue(sb.toString()))
        return result
    }

    private fun cleanCellValue(value: String): String {
        var trimmed = value.trim()
        if (trimmed.startsWith("'") && trimmed.length > 1) {
            trimmed = trimmed.substring(1)
        }
        return trimmed
    }

    /**
     * Parses modern Excel (.xlsx) files natively using ZipInputStream and XmlPullParser.
     * Zero external dependencies required.
     */
    private fun parseXlsx(inputStream: InputStream): List<List<String>> {
        val sharedStrings = mutableListOf<String>()
        val sheetRows = mutableListOf<List<String>>()

        // Step 1: Read shared strings and worksheet from XLSX ZIP package
        val bytes = inputStream.readBytes()

        // Pass 1: Extract shared strings if present
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry: ZipEntry?
            while (zip.nextEntry.also { entry = it } != null) {
                if (entry!!.name.equals("xl/sharedStrings.xml", ignoreCase = true)) {
                    sharedStrings.addAll(extractSharedStrings(zip))
                    break
                }
            }
        }

        // Pass 2: Extract first sheet data
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry: ZipEntry?
            while (zip.nextEntry.also { entry = it } != null) {
                if (entry!!.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml", RegexOption.IGNORE_CASE))) {
                    sheetRows.addAll(extractSheetRows(zip, sharedStrings))
                    break
                }
            }
        }

        return sheetRows
    }

    private fun extractSharedStrings(inputStream: InputStream): List<String> {
        val strings = mutableListOf<String>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(inputStream, "UTF-8")

        var eventType = parser.eventType
        var inText = false
        val currentText = StringBuilder()

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name.equals("t", ignoreCase = true)) {
                        inText = true
                        currentText.clear()
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inText) {
                        currentText.append(parser.text)
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("t", ignoreCase = true)) {
                        strings.add(currentText.toString())
                        inText = false
                    }
                }
            }
            eventType = parser.next()
        }
        return strings
    }

    private fun extractSheetRows(inputStream: InputStream, sharedStrings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(inputStream, "UTF-8")

        var eventType = parser.eventType
        var currentRow = mutableListOf<String>()
        var cellType: String? = null
        var inVal = false
        val cellVal = StringBuilder()

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase()) {
                        "row" -> {
                            currentRow = mutableListOf()
                        }
                        "c" -> {
                            cellType = parser.getAttributeValue(null, "t")
                            cellVal.clear()
                        }
                        "v" -> {
                            inVal = true
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inVal) {
                        cellVal.append(parser.text)
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name.lowercase()) {
                        "v" -> {
                            inVal = false
                            val raw = cellVal.toString().trim()
                            val value = if (cellType == "s") {
                                val idx = raw.toIntOrNull()
                                if (idx != null && idx in sharedStrings.indices) sharedStrings[idx] else raw
                            } else {
                                raw
                            }
                            currentRow.add(value)
                        }
                        "row" -> {
                            if (currentRow.any { it.isNotBlank() }) {
                                rows.add(currentRow)
                            }
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return rows
    }

    /**
     * Inspects column headers and maps rows into structured expenses.
     */
    private fun parseRowsToExpenses(
        rows: List<List<String>>,
        groupDetail: GroupDetail
    ): ImportAnalysisResult {
        if (rows.isEmpty()) return ImportAnalysisResult(0, emptyList(), 0, 0.0, emptySet(), "No data found")

        val header = rows.first().map { it.trim().lowercase() }
        var descIdx = header.indexOfFirst { it.contains("desc") || it.contains("item") || it.contains("title") || it.contains("for") }
        var amountIdx = header.indexOfFirst { it.contains("amount") || it.contains("cost") || it.contains("total") || it.contains("price") }
        var payerIdx = header.indexOfFirst { it.contains("payer") || it.contains("paid by") || it.contains("who paid") || it.contains("paid") }
        var categoryIdx = header.indexOfFirst { it.contains("cat") }
        var dateIdx = header.indexOfFirst { it.contains("date") || it.contains("time") }
        var statusIdx = header.indexOfFirst { it.contains("status") }

        // If no explicit header was matched, fall back to default column index assumptions:
        // Col 0 = Date/Desc, Col 1 = Desc/Amount, etc.
        val dataStartIndex: Int
        if (descIdx == -1 && amountIdx == -1) {
            dataStartIndex = 0
            descIdx = 0
            amountIdx = 1
            payerIdx = 2
            categoryIdx = 3
        } else {
            dataStartIndex = 1
            if (descIdx == -1) descIdx = 0
            if (amountIdx == -1) amountIdx = 1
        }

        val existingExpenses = groupDetail.expenses.filter { !it.isVoided }
        val newExpenses = mutableListOf<ParsedImportExpense>()
        var skippedDuplicates = 0
        val detectedPayers = mutableSetOf<String>()

        for (r in dataStartIndex until rows.size) {
            val row = rows[r]
            if (row.all { it.isBlank() }) continue

            // If status is VOIDED / CANCELLED, skip
            if (statusIdx != -1 && statusIdx < row.size) {
                val statusVal = row[statusIdx].trim().uppercase()
                if (statusVal == "VOIDED" || statusVal == "CANCELLED" || statusVal == "DELETED") {
                    continue
                }
            }

            val desc = if (descIdx < row.size) row[descIdx].trim() else ""
            val rawAmount = if (amountIdx < row.size) row[amountIdx].replace("[^0-9.]".toRegex(), "").trim() else ""
            val amount = rawAmount.toDoubleOrNull() ?: 0.0

            if (desc.isBlank() || amount <= 0.0) continue

            val rawPayer = if (payerIdx != -1 && payerIdx < row.size) row[payerIdx].trim() else ""
            val resolvedPayer = if (rawPayer.isNotBlank()) rawPayer else groupDetail.group.creatorPubkey
            if (rawPayer.isNotBlank()) detectedPayers.add(rawPayer)

            val rawCategory = if (categoryIdx != -1 && categoryIdx < row.size) row[categoryIdx].trim() else "General"
            val category = ExpenseCategory.fromName(rawCategory).displayName

            val dateStr = if (dateIdx != -1 && dateIdx < row.size) row[dateIdx].trim() else ""
            var parsedTimestamp = System.currentTimeMillis()
            if (dateStr.isNotBlank()) {
                for (fmt in commonDateFormats) {
                    try {
                        val parsed = fmt.parse(dateStr)
                        if (parsed != null) {
                            parsedTimestamp = parsed.time
                            break
                        }
                    } catch (_: Exception) {
                    }
                }
            }

            // Check if duplicate of an existing active expense in group (matching description + amount within 0.01)
            val isDuplicate = existingExpenses.any {
                it.description.equals(desc, ignoreCase = true) && kotlin.math.abs(it.totalAmount - amount) < 0.01
            }

            if (isDuplicate) {
                skippedDuplicates++
            } else {
                newExpenses.add(
                    ParsedImportExpense(
                        description = desc,
                        amount = amount,
                        category = category,
                        payerName = resolvedPayer,
                        dateString = dateStr,
                        timestamp = parsedTimestamp
                    )
                )
            }
        }

        if (newExpenses.isEmpty() && skippedDuplicates == 0) {
            return ImportAnalysisResult(0, emptyList(), 0, 0.0, emptySet(), "No valid expenses found in file.")
        }

        val totalAmount = newExpenses.sumOf { it.amount }
        return ImportAnalysisResult(
            totalFound = newExpenses.size + skippedDuplicates,
            newExpenses = newExpenses,
            skippedDuplicates = skippedDuplicates,
            totalAmount = totalAmount,
            detectedPayers = detectedPayers
        )
    }
}
