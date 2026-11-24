package com.geoconvert.studio

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.geoconvert.studio.model.GeoFeature
import com.geoconvert.studio.model.GeoFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF0B0B14)
private val Panel = Color(0xFF171725)
private val Purple = Color(0xFF9B7BFF)
private val Muted = Color(0xFFAAA8BD)
private val Line = Color(0xFF303044)

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
        var expandedFolders by remember(document) { mutableStateOf(setOf<String>()) }
        var expandedFeatures by remember(document) { mutableStateOf(setOf<String>()) }
        val scope = rememberCoroutineScope()

        val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                busy = true
                status = "Reading geographical data and folder structure…"
                scope.launch {
                    try {
                        document = withContext(Dispatchers.IO) {
                            GeoParser.parse(contentResolver, uri, displayName(uri))
                        }
                        status = "Imported ${document?.features?.size ?: 0} features in ${document?.folders?.size ?: 0} top-level folders."
                    } catch (exception: Exception) {
                        status = "Import failed: ${exception.message ?: "Unknown error"}"
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
                        status = "$exportType export completed with folder hierarchy."
                    } catch (exception: Exception) {
                        status = "Export failed: ${exception.message ?: "Unknown error"}"
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

                    Spacer(Modifier.height(22.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Panel),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Text("Import geographical data", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(5.dp))
                            Text("KML · KMZ · GeoJSON · GPX", color = Muted, fontSize = 13.sp)
                            Spacer(Modifier.height(14.dp))
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
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    ) {
                        Column(Modifier.fillMaxSize().padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Folder structure", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        "Browse folders first • Open an item for coordinates",
                                        color = Muted,
                                        fontSize = 11.sp
                                    )
                                }
                                document?.let {
                                    Text("${it.features.size} items", color = Purple, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            Spacer(Modifier.height(10.dp))

                            val current = document
                            if (current == null) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Your folders and files will appear here.", color = Muted, fontSize = 13.sp)
                                }
                            } else {
                                val hierarchy = current.folders + if (current.unfiledFeatures.isNotEmpty()) {
                                    listOf(GeoFolder("Unfiled items", current.unfiledFeatures))
                                } else emptyList()
                                if (hierarchy.isEmpty()) {
                                    Text("No folders or features found in this file.", color = Muted, fontSize = 13.sp)
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxWidth().weight(1f),
                                        contentPadding = PaddingValues(bottom = 8.dp)
                                    ) {
                                        items(hierarchy) { folder ->
                                            FolderTreeNode(
                                                folder = folder,
                                                path = folder.name,
                                                depth = 0,
                                                expandedFolders = expandedFolders,
                                                onToggleFolder = { key ->
                                                    expandedFolders = if (key in expandedFolders) expandedFolders - key else expandedFolders + key
                                                },
                                                expandedFeatures = expandedFeatures,
                                                onToggleFeature = { key ->
                                                    expandedFeatures = if (key in expandedFeatures) expandedFeatures - key else expandedFeatures + key
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("EXPORT DOCUMENT", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Spacer(Modifier.height(7.dp))
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

                    Spacer(Modifier.height(9.dp))
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Purple)
                    Text(status, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
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

@Composable
private fun FolderTreeNode(
    folder: GeoFolder,
    path: String,
    depth: Int,
    expandedFolders: Set<String>,
    onToggleFolder: (String) -> Unit,
    expandedFeatures: Set<String>,
    onToggleFeature: (String) -> Unit
) {
    val expanded = path in expandedFolders
    val totalFeatures = folder.features.size + folder.folders.sumOf { it.totalFeatures() }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleFolder(path) }
                .padding(start = (depth * 13).dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (expanded) "▾" else "▸", color = Purple, fontSize = 17.sp)
            Spacer(Modifier.width(7.dp))
            Text(if (expanded) "📂" else "📁", fontSize = 18.sp)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(folder.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    "$totalFeatures features • ${folder.folders.size} subfolders",
                    color = Muted,
                    fontSize = 10.sp
                )
            }
        }

        if (expanded) {
            folder.folders.forEach { child ->
                FolderTreeNode(
                    folder = child,
                    path = "$path/${child.name}",
                    depth = depth + 1,
                    expandedFolders = expandedFolders,
                    onToggleFolder = onToggleFolder,
                    expandedFeatures = expandedFeatures,
                    onToggleFeature = onToggleFeature
                )
            }
            folder.features.forEach { feature ->
                FeatureTreeNode(
                    feature = feature,
                    path = "$path/${feature.id}",
                    depth = depth + 1,
                    expanded = "$path/${feature.id}" in expandedFeatures,
                    onToggle = { onToggleFeature("$path/${feature.id}") }
                )
            }
        }
        HorizontalDivider(color = Line)
    }
}

@Composable
private fun FeatureTreeNode(
    feature: GeoFeature,
    path: String,
    depth: Int,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = (depth * 13).dp, top = 9.dp, bottom = 9.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (expanded) "▾" else "▸", color = Muted, fontSize = 15.sp)
            Spacer(Modifier.width(7.dp))
            Text("📍", fontSize = 15.sp)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(feature.name?.ifBlank { feature.id } ?: feature.id, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    "${feature.geometryType} • ${feature.coordinates.size} coordinates",
                    color = Muted,
                    fontSize = 10.sp
                )
            }
            Text("Details", color = Purple, fontSize = 10.sp)
        }

        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = ((depth + 1) * 13).dp, end = 8.dp, bottom = 10.dp)) {
                if (feature.description?.isNotBlank() == true) {
                    Text(feature.description, color = Muted, fontSize = 11.sp)
                }
                if (feature.coordinates.isNotEmpty()) {
                    Text("COORDINATES", color = Purple, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    feature.coordinates.take(50).forEachIndexed { index, coordinate ->
                        Text(
                            "${index + 1}. Lat ${coordinate.latitude}   Lon ${coordinate.longitude}" +
                                (coordinate.altitude?.let { "   Alt $it" } ?: ""),
                            color = Muted,
                            fontSize = 10.sp
                        )
                    }
                    if (feature.coordinates.size > 50) {
                        Text("Showing first 50 of ${feature.coordinates.size} coordinates.", color = Muted, fontSize = 10.sp)
                    }
                } else {
                    Text("No coordinates available for this feature.", color = Muted, fontSize = 10.sp)
                }
                feature.properties.forEach { (key, value) ->
                    Text("$key: $value", color = Muted, fontSize = 10.sp)
                }
            }
        }
    }
}

private fun GeoFolder.totalFeatures(): Int =
    features.size + folders.sumOf { it.totalFeatures() }
