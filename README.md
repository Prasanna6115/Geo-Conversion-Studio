# GeoConvert Studio

Offline-first Android geospatial file converter.

## Inputs
- KML / KMZ (including nested KML Folder hierarchy)
- GeoJSON (feature properties can supply a `folder` or slash-separated `folderPath`)
- GPX (grouped into Tracks, Routes and Waypoints)

## Outputs
- DOCX
- PDF
- XLSX

## Folder-first workspace
The main app view is an expandable folder tree. Open a folder to browse its child folders and features. Open an individual feature to see its latitude/longitude coordinates and properties. Exports retain folder paths and include a folder summary.

## Build an APK with GitHub Actions
Every push to `main` and every pull request targeting `main` runs the Android build. You can also start a build manually from **Actions → Android APK → Run workflow**.

The workflow installs Java 17, Android SDK 35 and Gradle 8.9, builds the debug APK and publishes it as the downloadable artifact named `GeoConvertStudio-debug-apk`.

## Current implementation notes
Offline file parsing and document generation are performed locally. KML hierarchy is preserved. GPX files are grouped by Tracks, Routes and Waypoints. GeoJSON is grouped into a Features folder by default; a string property named `folder` or `folderPath` can define a folder path (separate nested levels using `/`).

The PDF is a text-based folder and feature report; it does not include an offline basemap. Test diverse real-world files and exported documents before relying on the app for production surveying.
