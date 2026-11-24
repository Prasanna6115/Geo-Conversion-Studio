package com.geoconvert.studio

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geoconvert.studio.data.GeoParser
import com.geoconvert.studio.export.Exporters
import com.geoconvert.studio.model.GeoDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF0B0B14)
private val Panel = Color(0xFF171725)
private val Purple = Color(0xFF9B7BFF)
private val Muted = Color(0xFFAAA8BD)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GeoConvertApp() }
    }

    @Composable
    private fun GeoConvertApp() {
        var document by remember { mutableStateOf<GeoDocument?>(null) }
        var status by remember { mutableStateOf("Import a KML, KMZ, GeoJSON or GPX file to get started.") }
        var busy by remember { mutableStateOf(false) }
        var exportType by remember { mutableStateOf("PDF") }
        val scope = rememberCoroutineScope()

        val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                busy = true
                status = "Reading geographical data…"
                scope.launch {
                    try {
                        document = withContext(Dispatchers.IO) {
                            GeoParser.parse(contentResolver, uri, displayName(uri))
                        }
                        status = "Successfully imported ${document?.features?.size ?: 0} features."
                    } catch (e: Exception) {
                        status = "Import failed: ${e.message ?: "Unknown error"}"
                    } finally {
                        busy = false
                    }
                }
            }
        }

        val saveFile = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            val current = document
            if (uri != null && current != null) {
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            contentResolver.openOutputStream(uri)?.use { output ->
                                when (exportType) {
                                    "PDF" -> Exporters.writePdf(current, output)
                                    "DOCX" -> Exporters.writeDocx(current, output)
                                    else -> Exporters.writeXlsx(current, output)
                                }
                            } ?: error("Cannot create output file")
                        }
                        status = "$exportType export completed successfully."
                    } catch (e: Exception) {
                        status = "Export failed: ${e.message ?: "Unknown error"}"
                    } finally {
                        busy = false
                    }
                }
            }
        }

        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Purple,
                background = Bg,
                surface = Panel,
                onBackground = Color.White,
                onSurface = Color.White
            )
        ) {
            Surface(Modifier.fillMaxSize(), color = Bg) {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(42.dp).background(Purple, RoundedCornerShape(13.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("G", color = Bg, fontSize = 25.sp, fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("GeoConvert Studio", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "GEOSPATIAL DOCUMENT GENERATOR",
                                fontSize = 9.sp,
                                color = Muted,
                                letterSpacing = 1.3.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Panel),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Text("Import geographical data", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text("KML · KMZ · GeoJSON · GPX", color = Muted, fontSize = 13.sp)
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    pickFile.launch(
                                        arrayOf(
                                            "application/vnd.google-earth.kml+xml",
                                            "application/vnd.google-earth.kmz",
                                            "application/geo+json",
                                            "application/json",
                                            "application/gpx+xml",
                                            "application/octet-stream",
                                            "*/*"
                                        )
                                    )
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("＋  Choose file")
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Panel),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Workspace", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            val current = document
                            if (current == null) {
                                Text("Your imported file details will appear here.", color = Muted, fontSize = 13.sp)
                            } else {
                                Text(current.fileName, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${current.sourceFormat}  •  ${current.features.size} features",
                                    color = Purple,
                                    fontSize = 12.sp
                                )
                                Spacer(Modifier.height(10.dp))
                                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                                    items(current.features.take(100)) { feature ->
                                        Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                                            Text(feature.name?.ifBlank { feature.id } ?: feature.id, fontWeight = FontWeight.Medium)
                                            Text(
                                                "${feature.geometryType}  •  ${feature.coordinates.size} coordinates",
                                                color = Muted,
                                                fontSize = 11.sp
                                            )
                                        }
                                        HorizontalDivider(color = Color(0xFF303044))
                                    }
                                }
                                if (current.features.size > 100) {
                                    Text("Preview limited to first 100 features.", color = Muted, fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text("EXPORT DOCUMENT", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("DOCX", "PDF", "XLSX").forEach { type ->
                            OutlinedButton(
                                onClick = {
                                    exportType = type
                                    val baseName = document?.fileName?.substringBeforeLast('.') ?: "GeoConvert_Report"
                                    saveFile.launch("${baseName}_report.${type.lowercase()}")
                                },
                                enabled = document != null && !busy,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(type, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Purple)
                    Text(status, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("Offline processing • Files stay on your device", color = Color(0xFF77758B), fontSize = 10.sp)
                }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment ?: "imported_file"
    }
}
