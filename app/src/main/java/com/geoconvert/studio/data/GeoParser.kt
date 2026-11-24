package com.geoconvert.studio.data

import android.content.ContentResolver
import android.net.Uri
import android.util.Xml
import com.geoconvert.studio.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipInputStream

object GeoParser {
    fun parse(resolver: ContentResolver, uri: Uri, displayName: String): GeoDocument {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val bytes = resolver.openInputStream(uri)?.use {
            readLimited(it, 100L * 1024 * 1024)
        } ?: error("Unable to open selected file")
        require(bytes.isNotEmpty()) { "The selected file is empty" }

        return when (ext) {
            "kmz" -> {
                val kml = ZipInputStream(bytes.inputStream()).use { zip ->
                    var found: ByteArray? = null
                    var entry = zip.nextEntry
                    var count = 0
                    while (entry != null) {
                        require(++count <= 500) { "KMZ contains too many archive entries" }
                        if (!entry.isDirectory && entry.name.lowercase().endsWith(".kml")) {
                            require(!entry.name.startsWith("/") && !entry.name.split('/').contains("..")) {
                                "Unsafe KMZ entry path"
                            }
                            found = readLimited(zip, 50L * 1024 * 1024)
                            break
                        }
                        entry = zip.nextEntry
                    }
                    found
                } ?: error("No KML document found inside KMZ")
                parseKml(kml, displayName)
            }
            "kml" -> parseKml(bytes, displayName)
            "gpx" -> parseGpx(bytes, displayName)
            "geojson", "json" -> parseGeoJson(bytes, displayName)
            else -> error("Unsupported format: .$ext")
        }
    }

    private fun readLimited(input: java.io.InputStream, maxBytes: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            require(total <= maxBytes) {
                "File exceeds the supported ${maxBytes / (1024 * 1024)} MB processing limit"
            }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun parser(bytes: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        runCatching { setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
        setInput(bytes.inputStream(), "UTF-8")
    }

    private data class FolderBuilder(
        var name: String? = null,
        val features: MutableList<GeoFeature> = mutableListOf(),
        val folders: MutableList<FolderBuilder> = mutableListOf()
    ) {
        fun build(): GeoFolder = GeoFolder(
            name = name?.takeIf { it.isNotBlank() } ?: "Untitled folder",
            features = features.toList(),
            folders = folders.map { it.build() }
        )
    }

    private fun parseKml(bytes: ByteArray, fileName: String): GeoDocument {
        val xml = parser(bytes)
        val rootFolders = mutableListOf<FolderBuilder>()
        val folderStack = mutableListOf<FolderBuilder>()
        val allFeatures = mutableListOf<GeoFeature>()
        val unfiled = mutableListOf<GeoFeature>()

        var event = xml.eventType
        var inPlacemark = false
        var placemarkName: String? = null
        var description: String? = null
        var geometry = "UNKNOWN"
        var coordinates = mutableListOf<Coordinate>()
        var id = 0

        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (xml.name.lowercase()) {
                    "folder" -> folderStack.add(FolderBuilder())
                    "placemark" -> {
                        inPlacemark = true
                        placemarkName = null
                        description = null
                        geometry = "UNKNOWN"
                        coordinates = mutableListOf()
                    }
                    "name" -> {
                        if (inPlacemark && placemarkName == null) {
                            placemarkName = xml.nextText().trim()
                        } else if (!inPlacemark && folderStack.isNotEmpty() &&
                            folderStack.last().name.isNullOrBlank()
                        ) {
                            folderStack.last().name = xml.nextText().trim()
                        }
                    }
                    "description" -> if (inPlacemark) description = xml.nextText().trim()
                    "point" -> if (inPlacemark && geometry == "UNKNOWN") geometry = "POINT"
                    "linestring" -> if (inPlacemark) geometry = "LINESTRING"
                    "polygon" -> if (inPlacemark) geometry = "POLYGON"
                    "multigeometry" -> if (inPlacemark) geometry = "MULTI_GEOMETRY"
                    "coordinates" -> if (inPlacemark) coordinates.addAll(parseKmlCoordinates(xml.nextText()))
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (xml.name.lowercase()) {
                    "placemark" -> {
                        val path = folderStack.map { it.name?.takeIf(String::isNotBlank) ?: "Untitled folder" }
                        val feature = GeoFeature(
                            id = "feature-${++id}",
                            name = placemarkName,
                            geometryType = geometry,
                            coordinates = coordinates.toList(),
                            description = description,
                            folderPath = path
                        )
                        allFeatures += feature
                        if (folderStack.isEmpty()) unfiled += feature else folderStack.last().features += feature
                        inPlacemark = false
                    }
                    "folder" -> if (folderStack.isNotEmpty()) {
                        val completed = folderStack.removeAt(folderStack.lastIndex)
                        if (folderStack.isEmpty()) rootFolders += completed
                        else folderStack.last().folders += completed
                    }
                }
            }
            event = xml.next()
        }

        return GeoDocument(
            fileName = fileName,
            sourceFormat = "KML/KMZ",
            features = allFeatures,
            folders = rootFolders.map { it.build() },
            unfiledFeatures = unfiled
        )
    }

    private fun parseKmlCoordinates(raw: String): List<Coordinate> =
        raw.trim().split(Regex("\\s+")).mapNotNull { token ->
            val values = token.split(',')
            if (values.size < 2) null else runCatching {
                val longitude = values[0].toDouble()
                val latitude = values[1].toDouble()
                require(longitude in -180.0..180.0 && latitude in -90.0..90.0)
                Coordinate(longitude, latitude, values.getOrNull(2)?.toDoubleOrNull())
            }.getOrNull()
        }

    private fun parseGpx(bytes: ByteArray, fileName: String): GeoDocument {
        val xml = parser(bytes)
        val features = mutableListOf<GeoFeature>()
        var event = xml.eventType
        var kind = ""
        var currentName: String? = null
        var points = mutableListOf<Coordinate>()
        var id = 0

        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (xml.name.lowercase()) {
                    "wpt" -> {
                        kind = "WAYPOINT"
                        points = mutableListOf(readLatLon(xml))
                        currentName = null
                    }
                    "rte" -> {
                        kind = "ROUTE"
                        points = mutableListOf()
                        currentName = null
                    }
                    "trk" -> {
                        kind = "TRACK"
                        points = mutableListOf()
                        currentName = null
                    }
                    "rtept", "trkpt" -> if (kind.isNotEmpty()) points.add(readLatLon(xml))
                    "name" -> if (kind.isNotEmpty()) currentName = xml.nextText().trim()
                    "ele" -> if (points.isNotEmpty()) {
                        val altitude = xml.nextText().toDoubleOrNull()
                        val last = points.last()
                        points[points.lastIndex] = last.copy(altitude = altitude)
                    }
                    "time" -> if (points.isNotEmpty()) {
                        val timestamp = xml.nextText().trim()
                        val last = points.last()
                        points[points.lastIndex] = last.copy(timestamp = timestamp)
                    }
                }
            } else if (event == XmlPullParser.END_TAG &&
                xml.name.lowercase() in setOf("wpt", "rte", "trk")
            ) {
                val path = listOf(
                    when (kind) {
                        "TRACK" -> "Tracks"
                        "ROUTE" -> "Routes"
                        else -> "Waypoints"
                    }
                )
                features += GeoFeature(
                    id = "feature-${++id}",
                    name = currentName,
                    geometryType = kind,
                    coordinates = points.toList(),
                    folderPath = path
                )
                kind = ""
            }
            event = xml.next()
        }

        return GeoDocument(
            fileName = fileName,
            sourceFormat = "GPX",
            features = features,
            folders = buildFolderTree(features),
            unfiledFeatures = emptyList()
        )
    }

    private fun readLatLon(xml: XmlPullParser): Coordinate {
        val latitude = xml.getAttributeValue(null, "lat")?.toDoubleOrNull()
            ?: error("GPX point is missing latitude")
        val longitude = xml.getAttributeValue(null, "lon")?.toDoubleOrNull()
            ?: error("GPX point is missing longitude")
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) {
            "Invalid GPX coordinate"
        }
        return Coordinate(longitude, latitude)
    }

    private fun parseGeoJson(bytes: ByteArray, fileName: String): GeoDocument {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        val features = mutableListOf<GeoFeature>()

        fun addGeometry(
            geometry: JSONObject,
            label: String?,
            properties: Map<String, String>,
            id: String
        ) {
            val type = geometry.optString("type", "UNKNOWN")
            val coordinates = mutableListOf<Coordinate>()

            fun walk(value: Any?) {
                if (value is JSONArray) {
                    if (value.length() >= 2 &&
                        value.opt(0) is Number && value.opt(1) is Number
                    ) {
                        val longitude = (value.opt(0) as Number).toDouble()
                        val latitude = (value.opt(1) as Number).toDouble()
                        if (longitude in -180.0..180.0 && latitude in -90.0..90.0) {
                            coordinates += Coordinate(
                                longitude,
                                latitude,
                                (value.opt(2) as? Number)?.toDouble()
                            )
                        }
                    } else {
                        for (index in 0 until value.length()) walk(value.opt(index))
                    }
                }
            }

            walk(geometry.opt("coordinates"))
            val rawPath = properties["folderPath"] ?: properties["folder"]
            val folderPath = rawPath?.split('/')
                ?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() } ?: listOf("Features")
            features += GeoFeature(
                id = id,
                name = label,
                geometryType = type.uppercase(),
                coordinates = coordinates,
                properties = properties,
                folderPath = folderPath
            )
        }

        if (root.optString("type") == "FeatureCollection") {
            val array = root.optJSONArray("features") ?: JSONArray()
            for (index in 0 until array.length()) {
                val feature = array.optJSONObject(index) ?: continue
                val geometry = feature.optJSONObject("geometry") ?: continue
                val propertyObject = feature.optJSONObject("properties")
                val properties = mutableMapOf<String, String>()
                propertyObject?.keys()?.forEach { key ->
                    properties[key] = propertyObject.opt(key)?.toString() ?: ""
                }
                addGeometry(
                    geometry,
                    propertyObject?.optString("name")?.takeIf { it.isNotBlank() },
                    properties,
                    feature.optString("id", "feature-${index + 1}")
                )
            }
        } else if (root.optString("type") == "Feature") {
            val geometry = root.optJSONObject("geometry") ?: error("Feature has no geometry")
            val propertyObject = root.optJSONObject("properties")
            val properties = mutableMapOf<String, String>()
            propertyObject?.keys()?.forEach { key ->
                properties[key] = propertyObject.opt(key)?.toString() ?: ""
            }
            addGeometry(geometry, propertyObject?.optString("name"), properties, root.optString("id", "feature-1"))
        } else {
            addGeometry(root, null, emptyMap(), "feature-1")
        }

        return GeoDocument(
            fileName = fileName,
            sourceFormat = "GeoJSON",
            features = features,
            folders = buildFolderTree(features),
            unfiledFeatures = emptyList()
        )
    }

    private fun buildFolderTree(features: List<GeoFeature>): List<GeoFolder> {
        class Node(val name: String) {
            val features = mutableListOf<GeoFeature>()
            val children = linkedMapOf<String, Node>()
            fun build(): GeoFolder = GeoFolder(name, features.toList(), children.values.map { it.build() })
        }

        val roots = linkedMapOf<String, Node>()
        features.forEach { feature ->
            var children = roots
            var node: Node? = null
            feature.folderPath.forEach { segment ->
                node = children.getOrPut(segment) { Node(segment) }
                children = node!!.children
            }
            if (node == null) roots.getOrPut("Features") { Node("Features") }.features += feature
            else node!!.features += feature
        }
        return roots.values.map { it.build() }
    }
}
