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
        val bytes = resolver.openInputStream(uri)?.use { readLimited(it, 100L * 1024 * 1024) } ?: error("Unable to open selected file")
        require(bytes.isNotEmpty()) { "The selected file is empty" }
        return when (ext) {
            "kmz" -> {
                val kml = ZipInputStream(bytes.inputStream()).use { zip ->
                    var found: ByteArray? = null
                    var entry = zip.nextEntry
                    var count = 0
                    while (entry != null) {
                        require(++count <= 500) { "KMZ contains too many entries" }
                        if (!entry.isDirectory && entry.name.lowercase().endsWith(".kml")) {
                            require(!entry.name.startsWith("/") && !entry.name.split('/').contains("..")) { "Unsafe KMZ entry path" }
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
            require(total <= maxBytes) { "File exceeds the supported ${maxBytes / (1024 * 1024)} MB processing limit" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun parser(bytes: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        runCatching { setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
        setInput(bytes.inputStream(), "UTF-8")
    }

    private fun parseKml(bytes: ByteArray, name: String): GeoDocument {
        val p = parser(bytes)
        val out = mutableListOf<GeoFeature>()
        var event = p.eventType
        var inPlacemark = false
        var placemarkName: String? = null
        var description: String? = null
        var geometry = "UNKNOWN"
        var coords = mutableListOf<Coordinate>()
        var id = 0
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) when (p.name.lowercase()) {
                "placemark" -> { inPlacemark = true; placemarkName = null; description = null; geometry = "UNKNOWN"; coords = mutableListOf() }
                "name" -> if (inPlacemark) { val value = p.nextText().trim(); if (placemarkName == null) placemarkName = value }
                "description" -> if (inPlacemark) description = p.nextText().trim()
                "point" -> if (inPlacemark) geometry = "POINT"
                "linestring" -> if (inPlacemark) geometry = "LINESTRING"
                "polygon" -> if (inPlacemark) geometry = "POLYGON"
                "coordinates" -> if (inPlacemark) coords.addAll(parseKmlCoordinates(p.nextText()))
            }
            if (event == XmlPullParser.END_TAG && p.name.equals("Placemark", true)) {
                out += GeoFeature("feature-${++id}", placemarkName, geometry, coords.toList(), description = description)
                inPlacemark = false
            }
            event = p.next()
        }
        return GeoDocument(name, "KML/KMZ", out)
    }

    private fun parseKmlCoordinates(raw: String): List<Coordinate> =
        raw.trim().split(Regex("\\s+")).mapNotNull { token ->
            val values = token.split(',')
            if (values.size < 2) null else runCatching {
                val lon = values[0].toDouble()
                val lat = values[1].toDouble()
                require(lon in -180.0..180.0 && lat in -90.0..90.0)
                Coordinate(lon, lat, values.getOrNull(2)?.toDoubleOrNull())
            }.getOrNull()
        }

    private fun parseGpx(bytes: ByteArray, name: String): GeoDocument {
        val p = parser(bytes)
        val out = mutableListOf<GeoFeature>()
        var event = p.eventType
        var kind = ""
        var currentName: String? = null
        var points = mutableListOf<Coordinate>()
        var id = 0
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) when (p.name.lowercase()) {
                "wpt" -> { kind = "WAYPOINT"; points = mutableListOf(readLatLon(p)); currentName = null }
                "rte" -> { kind = "ROUTE"; points = mutableListOf(); currentName = null }
                "trk" -> { kind = "TRACK"; points = mutableListOf(); currentName = null }
                "rtept", "trkpt" -> if (kind.isNotEmpty()) points.add(readLatLon(p))
                "name" -> if (kind.isNotEmpty()) currentName = p.nextText().trim()
                "ele" -> if (points.isNotEmpty()) { val alt = p.nextText().toDoubleOrNull(); val last = points.last(); points[points.lastIndex] = last.copy(altitude = alt) }
                "time" -> if (points.isNotEmpty()) { val stamp = p.nextText().trim(); val last = points.last(); points[points.lastIndex] = last.copy(timestamp = stamp) }
            }
            if (event == XmlPullParser.END_TAG && p.name.lowercase() in setOf("wpt", "rte", "trk")) {
                out += GeoFeature("feature-${++id}", currentName, kind, points.toList())
                kind = ""
            }
            event = p.next()
        }
        return GeoDocument(name, "GPX", out)
    }

    private fun readLatLon(p: XmlPullParser): Coordinate {
        val lat = p.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: error("GPX point missing latitude")
        val lon = p.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: error("GPX point missing longitude")
        require(lat in -90.0..90.0 && lon in -180.0..180.0) { "Invalid GPX coordinate" }
        return Coordinate(lon, lat)
    }

    private fun parseGeoJson(bytes: ByteArray, name: String): GeoDocument {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        val features = mutableListOf<GeoFeature>()
        fun addGeometry(geometry: JSONObject, label: String?, properties: Map<String, String>, id: String) {
            val type = geometry.optString("type", "UNKNOWN")
            val coords = mutableListOf<Coordinate>()
            fun walk(value: Any?) {
                when (value) {
                    is JSONArray -> {
                        if (value.length() >= 2 && value.opt(0) is Number && value.opt(1) is Number) {
                            val lon = (value.opt(0) as Number).toDouble()
                            val lat = (value.opt(1) as Number).toDouble()
                            if (lon in -180.0..180.0 && lat in -90.0..90.0)
                                coords += Coordinate(lon, lat, (value.opt(2) as? Number)?.toDouble())
                        } else for (i in 0 until value.length()) walk(value.opt(i))
                    }
                }
            }
            walk(geometry.opt("coordinates"))
            features += GeoFeature(id, label, type.uppercase(), coords, properties)
        }
        if (root.optString("type") == "FeatureCollection") {
            val array = root.optJSONArray("features") ?: JSONArray()
            for (i in 0 until array.length()) {
                val feature = array.optJSONObject(i) ?: continue
                val geometry = feature.optJSONObject("geometry") ?: continue
                val obj = feature.optJSONObject("properties")
                val props = mutableMapOf<String, String>()
                obj?.keys()?.forEach { key -> props[key] = obj.opt(key)?.toString() ?: "" }
                addGeometry(geometry, obj?.optString("name")?.takeIf { it.isNotBlank() }, props, feature.optString("id", "feature-${i + 1}"))
            }
        } else if (root.optString("type") == "Feature") {
            val geometry = root.optJSONObject("geometry") ?: error("Feature has no geometry")
            val obj = root.optJSONObject("properties")
            val props = mutableMapOf<String, String>()
            obj?.keys()?.forEach { key -> props[key] = obj.opt(key)?.toString() ?: "" }
            addGeometry(geometry, obj?.optString("name"), props, root.optString("id", "feature-1"))
        } else addGeometry(root, null, emptyMap(), "feature-1")
        return GeoDocument(name, "GeoJSON", features)
    }
}
