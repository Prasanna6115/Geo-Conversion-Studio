package com.geoconvert.studio.export

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.geoconvert.studio.model.GeoDocument
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Exporters {
    fun writePdf(doc: GeoDocument, out: OutputStream) {
        val pdf = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = android.graphics.Color.BLACK }
        val bold = Paint(paint).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 18f }
        val lines = buildList {
            add("Source: ${doc.fileName}")
            add("Format: ${doc.sourceFormat}")
            add("Features: ${doc.features.size}")
            add("")
            doc.features.forEachIndexed { index, feature ->
                add("${index + 1}. ${feature.name ?: feature.id} — ${feature.geometryType}")
                feature.coordinates.take(12).forEach { c -> add("   Lon: ${c.longitude}, Lat: ${c.latitude}${c.altitude?.let { a -> ", Alt: $a" } ?: ""}") }
                if (feature.coordinates.size > 12) add("   ... ${feature.coordinates.size - 12} more coordinates")
                feature.properties.forEach { (key, value) -> add("   $key: $value") }
                add("")
            }
        }
        var pageNumber = 1
        var index = 0
        while (index < lines.size || pageNumber == 1) {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            val canvas = page.canvas
            canvas.drawText("GeoConvert Studio Report", 40f, 48f, bold)
            var y = 78f
            while (index < lines.size && y < 805f) { canvas.drawText(lines[index].take(105), 40f, y, paint); y += 16f; index++ }
            canvas.drawText("Page $pageNumber", 500f, 825f, paint)
            pdf.finishPage(page)
            pageNumber++
            if (index >= lines.size) break
        }
        pdf.writeTo(out)
        pdf.close()
    }

    fun writeDocx(doc: GeoDocument, out: OutputStream) {
        val body = StringBuilder()
        fun paragraph(text: String, style: String = "Normal") {
            body.append("<w:p><w:pPr><w:pStyle w:val=\"$style\"/></w:pPr><w:r><w:t xml:space=\"preserve\">${xml(text)}</w:t></w:r></w:p>")
        }
        paragraph("GeoConvert Studio Report", "Title")
        paragraph("Source: ${doc.fileName}")
        paragraph("Format: ${doc.sourceFormat}")
        paragraph("Total features: ${doc.features.size}")
        doc.features.forEachIndexed { index, feature ->
            paragraph("${index + 1}. ${feature.name ?: feature.id} (${feature.geometryType})", "Heading1")
            feature.description?.let { paragraph("Description: $it") }
            feature.properties.forEach { (key, value) -> paragraph("$key: $value") }
            feature.coordinates.forEachIndexed { n, coordinate ->
                paragraph("${n + 1}. Longitude: ${coordinate.longitude} | Latitude: ${coordinate.latitude}${coordinate.altitude?.let { " | Altitude: $it" } ?: ""}")
            }
        }
        val documentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>$body<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr></w:body></w:document>"
        ZipOutputStream(out).use { zip ->
            addEntry(zip, "[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
            addEntry(zip, "_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            addEntry(zip, "word/document.xml", documentXml)
        }
    }

    fun writeXlsx(doc: GeoDocument, out: OutputStream) {
        val sheets = listOf(
            "Overview" to listOf(listOf("Field", "Value"), listOf("Source file", doc.fileName), listOf("Source format", doc.sourceFormat), listOf("Feature count", doc.features.size.toString())),
            "Features" to buildList { add(listOf("ID", "Name", "Geometry", "Coordinate count", "Description")); doc.features.forEach { add(listOf(it.id, it.name ?: "", it.geometryType, it.coordinates.size.toString(), it.description ?: "")) } },
            "Coordinates" to buildList { add(listOf("Feature ID", "Feature name", "Geometry", "Index", "Longitude", "Latitude", "Altitude", "Timestamp")); doc.features.forEach { f -> f.coordinates.forEachIndexed { i,c -> add(listOf(f.id, f.name ?: "", f.geometryType, (i+1).toString(), c.longitude.toString(), c.latitude.toString(), c.altitude?.toString() ?: "", c.timestamp ?: "")) } } },
            "Properties" to buildList { add(listOf("Feature ID", "Feature name", "Property", "Value")); doc.features.forEach { f -> f.properties.forEach { (k,v) -> add(listOf(f.id, f.name ?: "", k, v)) } } }
        )
        ZipOutputStream(out).use { zip ->
            addEntry(zip, "[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/> ${sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" }}</Types>""")
            addEntry(zip, "_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            val workbook = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>${sheets.indices.joinToString("") { "<sheet name=\"${sheets[it].first}\" sheetId=\"${it + 1}\" r:id=\"rId${it + 1}\"/>" }}</sheets></workbook>"
            addEntry(zip, "xl/workbook.xml", workbook)
            addEntry(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">${sheets.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" }}</Relationships>")
            sheets.forEachIndexed { sheetIndex, sheet ->
                val rows = sheet.second.mapIndexed { rowIndex, row ->
                    "<row r=\"${rowIndex + 1}\">${row.mapIndexed { colIndex, value -> "<c r=\"${columnName(colIndex)}${rowIndex + 1}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>" }.joinToString("")}</row>"
                }.joinToString("")
                addEntry(zip, "xl/worksheets/sheet${sheetIndex + 1}.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>$rows</sheetData></worksheet>")
            }
        }
    }

    private fun columnName(number: Int): String {
        var value = number + 1
        var result = ""
        while (value > 0) { val remainder = (value - 1) % 26; result = ('A'.code + remainder).toChar() + result; value = (value - 1) / 26 }
        return result
    }
    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun addEntry(zip: ZipOutputStream, name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
}
