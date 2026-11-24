# GeoConvert Studio

Offline-first Android geospatial file converter.

## Inputs
- KML / KMZ
- GeoJSON
- GPX

## Outputs
- DOCX
- PDF
- XLSX

## Build an APK with GitHub Actions
Every push to `main` and every pull request targeting `main` runs the Android build. You can also start a build manually from **Actions → Android APK → Run workflow**.

The workflow installs Java 17, Android SDK 35 and Gradle 8.9, builds the debug APK and publishes it as the downloadable artifact named `GeoConvertStudio-debug-apk`.

## Local build
Open this project in Android Studio with JDK 17 and Android SDK 35, then run:

```bash
gradle :app:assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Current implementation notes
This initial codebase includes parsers for KML/KMZ, GeoJSON and GPX, plus basic DOCX, PDF and XLSX generation. It is an initial implementation rather than a fully validated production release. Test varied GIS files and open generated documents in Microsoft Office or LibreOffice before relying on them. Offline map backgrounds are not bundled.
