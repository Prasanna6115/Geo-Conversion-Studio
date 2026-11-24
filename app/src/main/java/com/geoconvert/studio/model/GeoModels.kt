package com.geoconvert.studio.model

data class GeoDocument(
    val fileName: String,
    val sourceFormat: String,
    val features: List<GeoFeature>,
    val folders: List<GeoFolder> = emptyList(),
    val unfiledFeatures: List<GeoFeature> = features.filter { it.folderPath.isEmpty() }
)

data class GeoFolder(
    val name: String,
    val features: List<GeoFeature> = emptyList(),
    val folders: List<GeoFolder> = emptyList()
)

data class GeoFeature(
    val id: String,
    val name: String?,
    val geometryType: String,
    val coordinates: List<Coordinate>,
    val properties: Map<String, String> = emptyMap(),
    val description: String? = null,
    val folderPath: List<String> = emptyList()
)

data class Coordinate(
    val longitude: Double,
    val latitude: Double,
    val altitude: Double? = null,
    val timestamp: String? = null
)
