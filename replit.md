# WebToApp

## Project Overview

**WebToApp** is a native Android application (Kotlin + Jetpack Compose) that lets users build, sign, and install APKs from web projects directly on their Android device — no desktop build queue required.

**This is not a web application.** It produces Android APK files and must be built with Android Studio + JDK 17, then installed on an Android device (API 23 / Android 6.0+).

## What It Does

- Packages websites (URL), static HTML/frontend builds, Node.js, PHP, Python, Go, and WordPress projects into installable Android APKs
- On-device APK signing with configurable keystores and signature schemes
- Extension module market for JS/CSS addons without rebuilding
- AI coding assistant integration
- APK patching and app cloning/rebranding support

## Build Instructions

### Requirements
- Android Studio Hedgehog or newer
- JDK 17
- Android SDK with API 23+ build tools

### Build
```bash
# Debug APK
./gradlew assembleDebug

# Release APK (requires signing config in local.properties)
./gradlew assembleRelease
```

### Signing (Release)
Add to `local.properties`:
```
signing.storeFile=/path/to/keystore.jks
signing.storePassword=...
signing.keyAlias=...
signing.keyPassword=...
```

## Project Structure

| Directory | Purpose |
|-----------|---------|
| `app/` | Main Android application module |
| `shell/` | Lightweight runtime host embedded into generated APKs |
| `clone-host/` | App cloning and rebranding functionality |
| `modules/` | Community extension module catalog |
| `app/src/main/cpp/` | Native C/C++ launchers (Node.js, Go) |
| `app/src/main/assets/` | Sample projects, AI prompts, shell templates |

## Replit Environment Note

Since this is an Android project, a simple informational web page is served at port 5000 via `serve.py` so the preview pane shows project information. The actual app must be built and run on an Android device.

## User Preferences

- Follow existing Kotlin/Gradle conventions
