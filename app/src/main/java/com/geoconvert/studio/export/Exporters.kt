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
        val pdf = PdfDocument()
        try {
            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 10.5f
                color = android.graphics.Color.BLACK
            }
            val titlePaint = Paint(bodyPaint).apply {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = 18f
            }
            val lines = pdfLines(doc)
            var pageNumber = 1
            var index = 0
            do {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
                page.canvas.drawText("GeoConvert Studio Report", 40f, 48f, titlePaint)
                var y = 78f
                while (index < lines.size && y < 805f) {
                    val line = lines[index].replace("📁", "[Folder]").replace("…", "...")
                    page.canvas.drawText(line.take(105), 40f, y, bodyPaint)
                    y += 15f
                    index++
                }
                page.canvas.drawText("Page $pageNumber", 500f, 825f, bodyPaint)
                pdf.finishPage(page)
                pageNumber++
            } while (index < lines.size)
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    fun writeDocx(doc: GeoDocument, out: OutputStream) {
        val body = StringBuilder()
        fun paragraph(text: String, style: String = "Normal") {
            body.append(
                "<w:p><w:pPr><w:pStyle w:val=\"$style\"/></w:pPr>" +
                    "<w:r><w:t xml:space=\"preserve\">${xml(text)}</w:t></w:r></w:p>"
            )
        }
        fun appendFeature(feature: GeoFeature, depth: Int) {
            val prefix = "  ".repeat(depth)
            paragraph("$prefix• ${feature.name?.ifBlank { feature.id } ?: feature.id} [${feature.geometryType}]")
            paragraph("$prefix  Coordinate count: ${feature.coordinates.size}")
            feature.description?.takeIf { it.isNotBlank() }?.let { paragraph("$prefix  Description: $it") }
            feature.properties.forEach { (key, value) -> paragraph("$prefix  $key: $value") }
            feature.coordinates.forEachIndexed { index, coordinate ->
                paragraph(
                    "$prefix  ${index + 1}. Longitude: ${coordinate.longitude} | Latitude: ${coordinate.latitude}" +
                        (coordinate.altitude?.let { " | Altitude: $it" } ?: "")
                )
            }
        }
        fun appendFolder(folder: GeoFolder, depth: Int) {
            paragraph("${folder.name} (${countFeatures(folder)} features)", if (depth == 0) "Heading1" else "Heading2")
            folder.features.forEach { appendFeature(it, depth + 1) }
            folder.folders.forEach { appendFolder(it, depth + 1) }
        }

        paragraph("GeoConvert Studio Report", "Title")
        paragraph("Source: ${doc.fileName}")
        paragraph("Format: ${doc.sourceFormat}")
        paragraph("Total features: ${doc.features.size}")
        paragraph("Folder hierarchy", "Heading1")
        doc.folders.forEach { appendFolder(it, 0) }
        if (doc.unfiledFeatures.isNotEmpty()) {
            paragraph("Unfiled items (${doc.unfiledFeatures.size} features)", "Heading1")
            doc.unfiledFeatures.forEach { appendFeature(it, 1) }
        }
        if (doc.features.isEmpty()) paragraph("No geographical features were found.")

        val documentXml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                "<w:body>$body<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr></w:body></w:document>"
        ZipOutputStream(out).use { zip ->
            addEntry(zip, "[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
            addEntry(zip, "_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            addEntry(zip, "word/document.xml", documentXml)
        }
    }

    fun writeXlsx(doc: GeoDocument, out: OutputStream) {
        val sheets = listOf(
            "Overview" to listOf(
                listOf("Field", "Value"),
                listOf("Source file", doc.fileName),
                listOf("Source format", doc.sourceFormat),
                listOf("Feature count", doc.features.size.toString()),
                listOf("Top-level folders", doc.folders.size.toString())
            ),
            "Folders" to buildList {
                add(listOf("Folder path", "Parent folder", "Direct features", "Subfolders", "Total features"))
                addAll(folderRows(doc))
            },
            "Features" to buildList {
                add(listOf("ID", "Name", "Folder path", "Geometry", "Coordinate count", "Description"))
                doc.features.forEach { feature ->
                    add(listOf(feature.id, feature.name ?: "", featurePath(feature), feature.geometryType, feature.coordinates.size.toString(), feature.description ?: ""))
                }
            },
            "Coordinates" to buildList {
                add(listOf("Feature ID", "Feature name", "Folder path", "Geometry", "Index", "Longitude", "Latitude", "Altitude", "Timestamp"))
                doc.features.forEach { feature ->
                    feature.coordinates.forEachIndexed { index, coordinate ->
                        add(listOf(feature.id, feature.name ?: "", featurePath(feature), feature.geometryType, (index + 1).toString(), coordinate.longitude.toString(), coordinate.latitude.toString(), coordinate.altitude?.toString() ?: "", coordinate.timestamp ?: ""))
                    }
                }
            },
            "Properties" to buildList {
                add(listOf("Feature ID", "Feature name", "Folder path", "Property", "Value"))
                doc.features.forEach { feature ->
                    feature.properties.forEach { (key, value) ->
                        add(listOf(feature.id, feature.name ?: "", featurePath(feature), key, value))
                    }
                }
            }
        )

        ZipOutputStream(out).use { zip ->
            val overrides = sheets.indices.joinToString("") {
                "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
            }
            addEntry(zip, "[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>$overrides</Types>""")
            addEntry(zip, "_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            val workbookSheets = sheets.indices.joinToString("") { index ->
                "<sheet name=\"${sheets[index].first}\" sheetId=\"${index + 1}\" r:id=\"rId${index + 1}\"/>"
            }
            addEntry(zip, "xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>$workbookSheets</sheets></workbook>""")
            val relationships = sheets.indices.joinToString("") { index ->
                "<Relationship Id=\"rId${index + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${index + 1}.xml\"/>"
            }
            addEntry(zip, "xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$relationships</Relationships>""")

            sheets.forEachIndexed { sheetIndex, sheet ->
                val rows = sheet.second.mapIndexed { rowIndex, row ->
                    val cells = row.mapIndexed { columnIndex, value ->
                        "<c r=\"${columnName(columnIndex)}${rowIndex + 1}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>"
                    }.joinToString("")
                    "<row r=\"${rowIndex + 1}\">$cells</row>"
                }.joinToString("")
                addEntry(zip, "xl/worksheets/sheet${sheetIndex + 1}.xml", """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$rows</sheetData></worksheet>""")
            }
        }
    }

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
