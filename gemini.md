# Gemini Project Context: MTBAnalyzer

This document provides context for the Gemini agent to work with the MTBAnalyzer Android project.

## Project Overview

- **Type**: Android Application
- **Primary Language**: Kotlin
- **Build System**: Gradle

## Key Directories and Files

- **Project Root**: `/Users/shez/AndroidStudioProjects/MTBAnalyzer`
- **Main Application Module**: `app/`
- **Source Code**: `app/src/main/java/`
- **Resources**: `app/src/main/res/`
- **Android Manifest**: `app/src/main/AndroidManifest.xml`
- **App-level Build File**: `app/build.gradle.kts`
- **Project-level Build File**: `build.gradle.kts`
- **Gradle Version Catalog**: `gradle/libs.versions.toml`

## Common Commands

The Gradle wrapper (`./gradlew`) should be used for all build and test operations.

- **Build the project**:
  ```bash
  ./gradlew build
  ```
- **Run unit tests**:
  ```bash
  ./gradlew test
  ```
- **Create a debug APK**:
  ```bash
  ./gradlew assembleDebug
  ```
  The output APK can be found at `app/build/outputs/apk/debug/app-debug.apk`.

- **Install the debug APK on a connected device/emulator**:
  ```bash
  ./gradlew installDebug
  ```
