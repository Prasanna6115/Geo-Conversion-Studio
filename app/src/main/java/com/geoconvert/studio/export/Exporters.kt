package com.geoconvert.studio.export

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.geoconvert.studio.model.GeoDocument
import com.geoconvert.studio.model.GeoFeature
import com.geoconvert.studio.model.GeoFolder
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Exporters {
    private fun featurePath(feature: GeoFeature): String =
        feature.folderPath.joinToString(" / ")

    private fun folderRows(doc: GeoDocument): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        fun visit(folder: GeoFolder, parent: String) {
            val path = if (parent.isBlank()) folder.name else "$parent / ${folder.name}"
            val total = folder.features.size + folder.folders.sumOf { countFeatures(it) }
            rows += listOf(path, parent, folder.features.size.toString(), folder.folders.size.toString(), total.toString())
            folder.folders.forEach { visit(it, path) }
        }
        doc.folders.forEach { visit(it, "") }
        if (doc.unfiledFeatures.isNotEmpty()) {
            rows += listOf("Unfiled items", "", doc.unfiledFeatures.size.toString(), "0", doc.unfiledFeatures.size.toString())
        }
        return rows
    }

    private fun countFeatures(folder: GeoFolder): Int =
        folder.features.size + folder.folders.sumOf { countFeatures(it) }

    private fun pdfLines(doc: GeoDocument): List<String> = buildList {
        add("Source: ${doc.fileName}")
        add("Format: ${doc.sourceFormat}")
        add("Total features: ${doc.features.size}")
        add("Top-level folders: ${doc.folders.size}")
        add("")
        fun appendFeature(feature: GeoFeature, indent: String) {
            add("$indent- ${feature.name?.ifBlank { feature.id } ?: feature.id} [${feature.geometryType}]")
            add("$indent  Coordinates: ${feature.coordinates.size}")
            feature.description?.takeIf { it.isNotBlank() }?.let { add("$indent  Description: $it") }
            feature.properties.forEach { (key, value) -> add("$indent  $key: $value") }
            feature.coordinates.take(20).forEachIndexed { index, coordinate ->
                add("$indent  ${index + 1}. Lon ${coordinate.longitude}, Lat ${coordinate.latitude}${coordinate.altitude?.let { ", Alt $it" } ?: ""}")
            }
            if (feature.coordinates.size > 20) add("$indent  … ${feature.coordinates.size - 20} more coordinates")
        }
        fun appendFolder(folder: GeoFolder, depth: Int) {
            val indent = "  ".repeat(depth)
            add("$indent📁 ${folder.name} (${countFeatures(folder)} features)")
            folder.features.forEach { appendFeature(it, "$indent  ") }
            folder.folders.forEach { appendFolder(it, depth + 1) }
        }
        doc.folders.forEach { appendFolder(it, 0) }
        if (doc.unfiledFeatures.isNotEmpty()) {
            add("📁 Unfiled items (${doc.unfiledFeatures.size} features)")
            doc.unfiledFeatures.forEach { appendFeature(it, "  ") }
        }
        if (doc.features.isEmpty()) add("No geographical features were found.")
    }

    fun writePdf(doc: GeoDocument, out: OutputStream) {
        val items = registerItems(doc)
        val staged = items.any { it.stage == "existing" || it.stage == "proposed" }
        val pdf = PdfDocument()
        try {
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 17f
                color = android.graphics.Color.BLACK
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 9f
                color = android.graphics.Color.BLACK
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; color = android.graphics.Color.BLACK }
            val lines = mutableListOf<List<String>>()
            if (staged) {
                lines += listOf("Sl.No", "Habitation", "Existing: Work / Category / Unique ID", "Proposed: Work / Category / Unique ID")
                var serial = 1
                items.filter { it.stage != null }.groupBy { it.habitation }.forEach { (habitation, group) ->
                    val existing = group.filter { it.stage == "existing" }
                    val proposed = group.filter { it.stage == "proposed" }
                    repeat(maxOf(existing.size, proposed.size)) { index ->
                        val left = existing.getOrNull(index)
                        val right = proposed.getOrNull(index)
                        lines += listOf(
                            serial++.toString(), habitation,
                            left?.let { "${it.workDescription} / ${it.category} / ${it.uniqueId}" }.orEmpty(),
                            right?.let { "${it.workDescription} / ${it.category} / ${it.uniqueId}" }.orEmpty()
                        )
                    }
                }
                items.filter { it.stage == null }.takeIf { it.isNotEmpty() }?.let { unknown ->
                    lines += listOf("", "UNCLASSIFIED WORKS", "", "")
                    unknown.forEach { lines += listOf("", it.habitation, it.workDescription, "${it.category} / ${it.uniqueId}") }
                }
            } else {
                lines += listOf("Sl.No", "Habitation", "Folder / Subfolder", "Work & Description", "Category", "Unique ID")
                items.forEachIndexed { index, item ->
                    lines += listOf(
                        (index + 1).toString(), item.habitation, item.folderPath,
                        item.workDescription, item.category, item.uniqueId
                    )
                }
            }

            val pageWidth = 842f
            val pageHeight = 595f
            val pageNoRef = intArrayOf(1)
            var page: PdfDocument.Page? = null
            var y = 0f
            fun startPage() {
                page?.let { pdf.finishPage(it) }
                page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth.toInt(), pageHeight.toInt(), pageNoRef[0]++).create())
                val canvas = page!!.canvas
                canvas.drawText("PANCHAYAT INFRASTRUCTURE WORKS REGISTER", 28f, 32f, titlePaint)
                canvas.drawText("Block: ${blockLabel(items)}", 28f, 53f, bodyPaint)
                canvas.drawText("Panchayat: ${panchayatLabel(doc, items)}", 470f, 53f, bodyPaint)
                canvas.drawLine(28f, 62f, 814f, 62f, bodyPaint)
                y = 82f
            }
            startPage()
            lines.forEachIndexed { rowIndex, row ->
                if (y > 558f) startPage()
                val canvas = page!!.canvas
                val xs = if (staged) listOf(28f, 65f, 190f, 510f) else listOf(28f, 65f, 185f, 390f, 610f, 720f)
                row.forEachIndexed { column, value ->
                    val maxChars = if (staged && column >= 2) 48 else if (!staged && column == 3) 31 else 20
                    canvas.drawText(value.take(maxChars), xs.getOrElse(column) { 28f }, y, if (rowIndex == 0) headerPaint else bodyPaint)
                }
                canvas.drawLine(28f, y + 5f, 814f, y + 5f, bodyPaint)
                y += if (rowIndex == 0) 20f else 17f
            }
            page?.let { pdf.finishPage(it) }

            val coordinates = items.flatMap { item ->
                item.feature.coordinates.mapIndexed { index, point ->
                    "${item.habitation} | ${item.workDescription} | #${index + 1} | Lat ${point.latitude} | Lon ${point.longitude}" +
                        (point.altitude?.let { " | Alt $it" } ?: "")
                }
            }
            if (coordinates.isNotEmpty()) {
                var index = 0
                while (index < coordinates.size) {
                    val coordinatePage = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth.toInt(), pageHeight.toInt(), pageNoRef[0]++).create())
                    coordinatePage.canvas.drawText("COORDINATE DETAILS", 28f, 32f, titlePaint)
                    var coordY = 60f
                    while (index < coordinates.size && coordY < 565f) {
                        coordinatePage.canvas.drawText(coordinates[index].take(130), 28f, coordY, bodyPaint)
                        coordY += 15f
                        index++
                    }
                    pdf.finishPage(coordinatePage)
                }
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    fun writeDocx(doc: GeoDocument, out: OutputStream) {
        val items = registerItems(doc)
        val staged = items.any { it.stage == "existing" || it.stage == "proposed" }
        val body = StringBuilder()
        fun paragraph(text: String, style: String = "Normal") {
            body.append("<w:p><w:pPr><w:pStyle w:val=\"$style\"/></w:pPr><w:r><w:t xml:space=\"preserve\">${xml(text)}</w:t></w:r></w:p>")
        }

        paragraph("PANCHAYAT INFRASTRUCTURE WORKS REGISTER", "Title")
        paragraph("Block: ${blockLabel(items)}     Panchayat: ${panchayatLabel(doc, items)}")
        paragraph("Folder / habitation based register", "Heading1")
        if (staged) {
            paragraph("EXISTING AND PROPOSED WORKS", "Heading1")
            var serial = 1
            items.filter { it.stage != null }.groupBy { it.habitation }.forEach { (habitation, group) ->
                paragraph("${serial++}. $habitation", "Heading2")
                paragraph("EXISTING — Work & Description | Category | Unique ID", "Heading3")
                group.filter { it.stage == "existing" }.forEach { item ->
                    paragraph("${item.workDescription} | ${item.category} | ${item.uniqueId}")
                }
                paragraph("PROPOSED — Work & Description | Category | Unique ID", "Heading3")
                group.filter { it.stage == "proposed" }.forEach { item ->
                    paragraph("${item.workDescription} | ${item.category} | ${item.uniqueId}")
                }
            }
            items.filter { it.stage == null }.forEach { item ->
                paragraph("Unclassified | ${item.habitation} | ${item.workDescription} | ${item.category} | ${item.uniqueId}")
            }
        } else {
            paragraph("Sl.No | Habitation | Folder / Subfolder | Work & Description | Category | Unique ID", "Heading2")
            items.groupBy { it.habitation }.forEach { (habitation, group) ->
                paragraph(habitation, "Heading2")
                group.forEach { item ->
                    paragraph("${item.folderPath} | ${item.workDescription} | ${item.category} | ${item.uniqueId} | ${item.feature.geometryType}")
                }
            }
        }

        paragraph("COORDINATE DETAILS", "Heading1")
        items.forEach { item ->
            paragraph("${item.habitation} / ${item.workDescription}", "Heading2")
            item.feature.coordinates.forEachIndexed { index, coordinate ->
                paragraph("${index + 1}. Latitude: ${coordinate.latitude} | Longitude: ${coordinate.longitude}" +
                    (coordinate.altitude?.let { " | Altitude: $it" } ?: ""))
            }
        }

        val documentXml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                "<w:body>$body<w:sectPr><w:pgSz w:w=\"16838\" w:h=\"11906\" w:orient=\"landscape\"/></w:sectPr></w:body></w:document>"
        ZipOutputStream(out).use { zip ->
            addEntry(zip, "[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
            addEntry(zip, "_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            addEntry(zip, "word/document.xml", documentXml)
        }
    }

    private data class RegisterItem(
        val feature: GeoFeature,
        val habitation: String,
        val folderPath: String,
        val stage: String?,
        val workDescription: String,
        val category: String,
        val uniqueId: String
    )
    private data class XCell(val value: String = "", val style: Int = 0, val numeric: Boolean = false)
    private data class XSheet(
        val name: String,
        val rows: List<List<XCell>>,
        val widths: List<Int>,
        val merges: List<String> = emptyList(),
        val freezeRows: Int = 4
    )

    private fun key(value: String) = value.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun prop(feature: GeoFeature, vararg names: String): String? {
        val wanted = names.map(::key).toSet()
        return feature.properties.entries.firstOrNull { key(it.key) in wanted }
            ?.value?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun registerItems(doc: GeoDocument): List<RegisterItem> = doc.features.map { feature ->
        val path = feature.folderPath.filter { it.isNotBlank() }
        val stageIndex = path.indexOfFirst { key(it) in setOf("existing", "proposed", "existingworks", "proposedworks") }
        val fromProperty = prop(feature, "stage", "work stage", "status", "phase")?.let(::key)
            ?.takeIf { it in setOf("existing", "proposed", "existingworks", "proposedworks") }
        val fromFolder = path.getOrNull(stageIndex)?.let(::key)
        val stage = fromProperty ?: fromFolder?.let { if (it.startsWith("existing")) "existing" else "proposed" }
        val rest = if (stageIndex >= 0) path.drop(stageIndex + 1) else path
        val habitation = prop(feature, "habitation", "hamlet", "village", "locality")
            ?: rest.firstOrNull()
            ?: "Unspecified habitation"
        val category = prop(feature, "category", "work category", "asset category", "work type", "type")
            ?: rest.getOrNull(1).orEmpty()
        RegisterItem(
            feature = feature,
            habitation = habitation,
            folderPath = path.joinToString(" / "),
            stage = stage,
            workDescription = prop(feature, "work description", "work", "asset name")
                ?: feature.name?.takeIf { it.isNotBlank() }
                ?: feature.description?.takeIf { it.isNotBlank() }
                ?: feature.id,
            category = category,
            uniqueId = prop(feature, "unique id", "uniqueid", "uid", "work id", "asset id", "code") ?: feature.id
        )
    }

    private fun blockLabel(items: List<RegisterItem>) =
        items.firstNotNullOfOrNull { prop(it.feature, "block", "block name", "union block") }.orEmpty()

    private fun panchayatLabel(doc: GeoDocument, items: List<RegisterItem>) =
        items.firstNotNullOfOrNull { prop(it.feature, "panchayat", "panchayat name", "village panchayat") }
            ?: doc.fileName.substringBeforeLast('.').ifBlank { "Not specified" }

    private fun xc(value: Any?, style: Int = 0, numeric: Boolean = false) =
        XCell(value?.toString().orEmpty(), style, numeric)

    private fun baseRegisterHeader(
        paired: Boolean,
        block: String,
        panchayat: String
    ): Pair<MutableList<List<XCell>>, MutableList<String>> {
        val rows = mutableListOf<List<XCell>>()
        val merges = mutableListOf("A1:H1", "A2:B2")
        rows += listOf(xc("PANCHAYAT INFRASTRUCTURE WORKS REGISTER", 8)) + List(7) { xc("", 8) }
        rows += listOf(
            xc("Block : $block", 7), xc("", 7), xc("", 7), xc("", 7),
            xc("", 7), xc("", 7), xc("Panchayat :", 7), xc(panchayat, 7)
        )
        if (paired) {
            merges += listOf("C3:E3", "F3:H3")
            rows += listOf(
                xc("", 2), xc("", 2), xc("EXISTING", 3), xc("", 3), xc("", 3),
                xc("PROPOSED", 3), xc("", 3), xc("", 3)
            )
            rows += listOf("Sl.No", "Habitation", "Work & Description", "Category", "Unique ID",
                "Work & Description", "Category", "Unique ID").map { xc(it, 1) }
        } else {
            merges += "C3:H3"
            rows += listOf(xc("", 2), xc("", 2), xc("WORK REGISTER", 3), xc("", 3), xc("", 3), xc("", 3), xc("", 3), xc("", 3))
            rows += listOf("Sl.No", "Habitation", "Folder / Subfolder", "Work & Description",
                "Category", "Unique ID", "Geometry", "Feature Count").map { xc(it, 1) }
        }
        return rows to merges
    }

    private fun addMerge(merges: MutableList<String>, col: String, first: Int, last: Int) {
        if (last > first) merges += "$col$first:$col$last"
    }

    private fun mergeRepeatedCategories(
        merges: MutableList<String>,
        bodyRows: List<List<XCell>>,
        columnIndex: Int,
        firstExcelRow: Int
    ) {
        var index = 0
        while (index < bodyRows.size) {
            val value = bodyRows[index].getOrNull(columnIndex)?.value.orEmpty()
            if (value.isBlank()) { index++; continue }
            var last = index
            while (last + 1 < bodyRows.size && bodyRows[last + 1].getOrNull(columnIndex)?.value == value) last++
            addMerge(merges, columnName(columnIndex), firstExcelRow + index, firstExcelRow + last)
            index = last + 1
        }
    }

    private fun makePairedRegister(doc: GeoDocument, items: List<RegisterItem>): XSheet {
        val (rows, merges) = baseRegisterHeader(true, blockLabel(items), panchayatLabel(doc, items))
        var serial = 1
        var excelRow = 5
        items.filter { it.stage != null }.groupBy { it.habitation }.forEach { (habitation, group) ->
            val existing = group.filter { it.stage == "existing" }
            val proposed = group.filter { it.stage == "proposed" }
            val count = maxOf(existing.size, proposed.size)
            if (count == 0) return@forEach
            val startRow = excelRow
            repeat(count) { index ->
                val left = existing.getOrNull(index)
                val right = proposed.getOrNull(index)
                rows += listOf(
                    xc(serial++, 6, true), xc(if (index == 0) habitation else "", 4),
                    xc(left?.workDescription, 4), xc(left?.category, 6),
                    xc(left?.uniqueId, 6), xc(right?.workDescription, 4),
                    xc(right?.category, 6), xc(right?.uniqueId, 6)
                )
                excelRow++
            }
            addMerge(merges, "B", startRow, excelRow - 1)
        }
        val body = rows.drop(4)
        mergeRepeatedCategories(merges, body, 3, 5)
        mergeRepeatedCategories(merges, body, 6, 5)
        return XSheet("Works Register", rows, listOf(8, 22, 42, 24, 17, 42, 24, 17), merges)
    }

    private fun makeFlatRegister(
        doc: GeoDocument,
        items: List<RegisterItem>,
        sheetName: String = "Works Register"
    ): XSheet {
        val (rows, merges) = baseRegisterHeader(false, blockLabel(items), panchayatLabel(doc, items))
        var serial = 1
        var excelRow = 5
        items.groupBy { it.habitation }.forEach { (habitation, group) ->
            val first = excelRow
            group.forEachIndexed { index, item ->
                rows += listOf(
                    xc(serial++, 6, true), xc(if (index == 0) habitation else "", 4),
                    xc(item.folderPath, 4), xc(item.workDescription, 4),
                    xc(item.category, 6), xc(item.uniqueId, 6),
                    xc(item.feature.geometryType, 6), xc(item.feature.coordinates.size, 6, true)
                )
                excelRow++
            }
            addMerge(merges, "B", first, excelRow - 1)
        }
        mergeRepeatedCategories(merges, rows.drop(4), 4, 5)
        return XSheet(sheetName, rows, listOf(8, 22, 34, 42, 24, 17, 18, 16), merges)
    }

    private fun makeFolderSheet(doc: GeoDocument): XSheet {
        val rows = mutableListOf<List<XCell>>()
        rows += listOf("Folder Path", "Parent Folder", "Direct Features", "Subfolders", "Total Features").map { xc(it, 1) }
        fun visit(folder: GeoFolder, parent: String) {
            val path = if (parent.isBlank()) folder.name else "$parent / ${folder.name}"
            val total = folder.features.size + folder.folders.sumOf { countFeatures(it) }
            rows += listOf(xc(path, 4), xc(parent, 4), xc(folder.features.size, 6, true),
                xc(folder.folders.size, 6, true), xc(total, 6, true))
            folder.folders.forEach { visit(it, path) }
        }
        doc.folders.forEach { visit(it, "") }
        if (doc.unfiledFeatures.isNotEmpty()) {
            rows += listOf(xc("Unfiled items", 4), xc("", 4), xc(doc.unfiledFeatures.size, 6, true),
                xc(0, 6, true), xc(doc.unfiledFeatures.size, 6, true))
        }
        return XSheet("Folder Summary", rows, listOf(42, 42, 18, 14, 18), emptyList(), 1)
    }

    private fun makeFeatureSheet(items: List<RegisterItem>): XSheet {
        val rows = mutableListOf<List<XCell>>()
        rows += listOf("Feature ID", "Habitation", "Folder Path", "Work & Description", "Category",
            "Unique ID", "Geometry", "Description", "Property", "Value").map { xc(it, 1) }
        items.forEach { item ->
            val props = item.feature.properties
            if (props.isEmpty()) {
                rows += listOf(item.feature.id, item.habitation, item.folderPath, item.workDescription,
                    item.category, item.uniqueId, item.feature.geometryType, item.feature.description.orEmpty(), "", "").map { xc(it, 4) }
            } else {
                props.forEach { (key, value) ->
                    rows += listOf(item.feature.id, item.habitation, item.folderPath, item.workDescription,
                        item.category, item.uniqueId, item.feature.geometryType, item.feature.description.orEmpty(), key, value).map { xc(it, 4) }
                }
            }
        }
        return XSheet("Feature Details", rows, listOf(18, 22, 42, 38, 22, 18, 18, 38, 20, 38), emptyList(), 1)
    }

    private fun makeCoordinatesSheet(items: List<RegisterItem>): XSheet {
        val rows = mutableListOf<List<XCell>>()
        rows += listOf("Sl.No", "Habitation", "Folder Path", "Feature", "Geometry", "Point No.",
            "Latitude", "Longitude", "Altitude", "Timestamp").map { xc(it, 1) }
        var serial = 1
        items.forEach { item ->
            item.feature.coordinates.forEachIndexed { index, point ->
                rows += listOf(
                    xc(serial++, 6, true), xc(item.habitation, 4), xc(item.folderPath, 4),
                    xc(item.workDescription, 4), xc(item.feature.geometryType, 6),
                    xc(index + 1, 6, true), xc(point.latitude, 6, true), xc(point.longitude, 6, true),
                    xc(point.altitude, 6, point.altitude != null), xc(point.timestamp, 4)
                )
            }
        }
        return XSheet("Coordinates", rows, listOf(9, 22, 42, 36, 18, 12, 18, 18, 16, 24), emptyList(), 1)
    }

    fun writeXlsx(doc: GeoDocument, out: OutputStream) {
        val items = registerItems(doc)
        val hasStages = items.any { it.stage == "existing" || it.stage == "proposed" }
        val sheets = mutableListOf<XSheet>()
        if (hasStages) {
            sheets += makePairedRegister(doc, items.filter { it.stage == "existing" || it.stage == "proposed" })
            val unknown = items.filter { it.stage == null }
            if (unknown.isNotEmpty()) sheets += makeFlatRegister(doc, unknown, "Unclassified Works")
        } else {
            sheets += makeFlatRegister(doc, items)
        }
        sheets += makeFolderSheet(doc)
        sheets += makeFeatureSheet(items)
        sheets += makeCoordinatesSheet(items)
        writeStyledWorkbook(sheets, out)
    }

    private fun writeStyledWorkbook(sheets: List<XSheet>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            val overrides = sheets.indices.joinToString("") { index ->
                "<Override PartName=\"/xl/worksheets/sheet${index + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
            }
            addEntry(zip, "[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>$overrides</Types>""")
            addEntry(zip, "_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            val workbookSheets = sheets.indices.joinToString("") { index ->
                "<sheet name=\"${xml(sheets[index].name)}\" sheetId=\"${index + 1}\" r:id=\"rId${index + 1}\"/>"
            }
            addEntry(zip, "xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>$workbookSheets</sheets></workbook>""")
            val relationships = sheets.indices.joinToString("") { index ->
                "<Relationship Id=\"rId${index + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${index + 1}.xml\"/>"
            }
            addEntry(zip, "xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$relationships<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            addEntry(zip, "xl/styles.xml", styledStylesXml())
            sheets.forEachIndexed { index, sheet ->
                addEntry(zip, "xl/worksheets/sheet${index + 1}.xml", styledSheetXml(sheet))
            }
        }
    }

    private fun styledSheetXml(sheet: XSheet): String {
        val columnXml = sheet.widths.mapIndexed { index, width ->
            "<col min=\"${index + 1}\" max=\"${index + 1}\" width=\"$width\" customWidth=\"1\"/>"
        }.joinToString("")
        val rowXml = sheet.rows.mapIndexed { rowIndex, row ->
            val height = when (rowIndex) { 0 -> 27; 1 -> 23; 2 -> 23; 3 -> 36; else -> 28 }
            val cells = row.mapIndexed { columnIndex, cell ->
                val ref = "${columnName(columnIndex)}${rowIndex + 1}"
                val value = xml(cell.value)
                if (cell.numeric && cell.value.isNotBlank())
                    "<c r=\"$ref\" s=\"${cell.style}\" t=\"n\"><v>$value</v></c>"
                else
                    "<c r=\"$ref\" s=\"${cell.style}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">$value</t></is></c>"
            }.joinToString("")
            "<row r=\"${rowIndex + 1}\" ht=\"$height\" customHeight=\"1\">$cells</row>"
        }.joinToString("")
        val lastCols = columnName((sheet.rows.maxOfOrNull { it.size } ?: 1) - 1)
        val lastRow = sheet.rows.size.coerceAtLeast(1)
        val mergeXml = if (sheet.merges.isEmpty()) "" else
            "<mergeCells count=\"${sheet.merges.size}\">" + sheet.merges.joinToString("") { "<mergeCell ref=\"$it\"/>" } + "</mergeCells>"
        val views = if (sheet.freezeRows > 0)
            "<sheetViews><sheetView showGridLines=\"0\" workbookViewId=\"0\"><pane ySplit=\"${sheet.freezeRows}\" topLeftCell=\"A${sheet.freezeRows + 1}\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>"
        else "<sheetViews><sheetView showGridLines=\"0\" workbookViewId=\"0\"/></sheetViews>"
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">$views<dimension ref="A1:$lastCols$lastRow"/><sheetFormatPr defaultRowHeight="18"/><cols>$columnXml</cols><sheetData>$rowXml</sheetData>$mergeXml<pageMargins left="0.25" right="0.25" top="0.5" bottom="0.5" header="0.2" footer="0.2"/><pageSetup orientation="landscape" paperSize="9" fitToWidth="1" fitToHeight="0"/></worksheet>"""
    }

    private fun styledStylesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="4"><font><sz val="10"/><name val="Arial"/></font><font><b/><sz val="10"/><name val="Arial"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="10"/><name val="Arial"/></font><font><b/><sz val="12"/><name val="Arial"/></font></fonts>
<fills count="5"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFE7E6E6"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFDCE6F1"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FF6C5A91"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FF333333"/></left><right style="thin"><color rgb="FF333333"/></right><top style="thin"><color rgb="FF333333"/></top><bottom style="thin"><color rgb="FF333333"/></bottom><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="9">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
<xf numFmtId="0" fontId="2" fillId="4" borderId="1" xfId="0" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
<xf numFmtId="0" fontId="2" fillId="3" borderId="1" xfId="0" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyAlignment="1"><alignment horizontal="left" vertical="center" wrapText="1"/></xf>
<xf numFmtId="0" fontId="1" fillId="0" borderId="1" xfId="0" applyAlignment="1"><alignment horizontal="left" vertical="center" wrapText="1"/></xf>
<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment horizontal="left" vertical="center"/></xf>
<xf numFmtId="0" fontId="3" fillId="3" borderId="1" xfId="0" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
</cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
    private fun columnName(number: Int): String {
        var value = number + 1
        var result = ""
        while (value > 0) {
            val remainder = (value - 1) % 26
            result = ('A'.code + remainder).toChar() + result
            value = (value - 1) / 26
        }
        return result
    }

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun addEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}

private fun countFeatures(folder: GeoFolder): Int =
    folder.features.size + folder.folders.sumOf { countFeatures(it) }
