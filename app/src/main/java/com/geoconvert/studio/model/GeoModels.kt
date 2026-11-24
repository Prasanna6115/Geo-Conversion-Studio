package com.geoconvert.studio.model

data class GeoDocument(val fileName: String, val sourceFormat: String, val features: List<GeoFeature>)
data class GeoFeature(
    val id: String,
    val name: String?,
    val geometryType: String,
    val coordinates: List<Coordinate>,
    val properties: Map<String, String> = emptyMap(),
    val description: String? = null
)
data class Coordinate(
    val longitude: Double,
    val latitude: Double,
    val altitude: Double? = null,
    val timestamp: String? = null
)
