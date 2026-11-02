# VitaSync Nexus

**Wearable signals, report context, and live visualization in one connected prototype.**

VitaSync brings together an ESP8266 healthcare wearable, an Android application, and a website dashboard. Firebase Realtime Database carries vital measurements and device status. The Android app displays trends, processes uploaded medical reports with on-device OCR, and explains a simulated monitoring-priority result using explicit rules.

## Explore the project

- [Live website](https://health-monitor-dashboard-murex.vercel.app/)
- [Android source](android/)
- [Website source](website/)
- Latest debug APK: `HealthMonitor-History-Fix.apk` is available on the development computer. Binary upload is pending Git authentication.
- [12 fictional sample reports](samples/)

## Features

- Heart rate, SpO2, ECG, temperature and accelerometer displays
- Firebase Health and Simulator node integration
- Battery, charging, worn state, connectivity and sensor availability
- Session trend charts
- PDF and image OCR with Google ML Kit
- Diagnosis-heading and clinical-term matching with source evidence
- Local uploaded-file storage and report history
- Transparent rule-based monitoring-priority demonstration

## Technology

Android: Kotlin, Jetpack Compose, Material 3, ViewModel, Coroutines, StateFlow, Firebase Android SDK, ML Kit OCR, PdfRenderer, SharedPreferences, Gradle and JUnit.

Website: HTML, CSS, JavaScript, canvas charts, Firebase Realtime Database REST reads, and Vercel static hosting. The dashboard does not require React or the Firebase Web SDK.

Hardware integration: ESP8266/Wi-Fi and heart-rate, SpO2, ECG, temperature and accelerometer inputs. Hardware firmware is not included in this release because the final flashed sketch has not been identified.

## Build the Android application

1. Open `android/` in Android Studio and install Android SDK 35 and a compatible JDK (the supplied build was compiled with JDK 21).
2. Register `com.example.healthmonitor` in your Firebase project. Place its downloaded `google-services.json` in `android/app/`.
3. Configure your Firebase Realtime Database access rules for your deployment.
4. Run `gradlew.bat assembleDebug testDebugUnitTest` from `android/` on Windows, or `./gradlew assembleDebug testDebugUnitTest` on other systems.

Firebase client configuration, signing keys, local SDK paths and build caches are excluded from source control. The locally supplied APK is the existing configured debug build; rebuilding uses your own Firebase configuration and signing identity.

## Run the website

Serve `website/` with any static HTTP server. The database URL and supported data paths are documented in [website/README.md](website/README.md). For a new Vercel project, select `website` as the root directory and use the included static configuration.

## Demonstration order

Show wearable components, then the wearing/movement demonstration alongside the Android app, followed by the website dashboard. Upload a fictional report in the app to demonstrate extraction and stored results. Explicitly identify simulated readings during presentations.

## Current scope and validation

This is a hackathon prototype, not a clinically validated diagnostic system. Current report output uses rule-based simulation, not a trained disease-prediction model. An experimental Isolation Forest implementation remains in source but is not the active report-results predictor. OCR clinical-term matching is limited and can miss context or negation; displayed confidence values are heuristic, not measured accuracy.

Android compilation, existing unit tests and APK signature checks passed for the supplied build. Full upload testing on the user's phone and clinical validation have not been completed. The latest build fixes invalid numeric values when saving older history records. Sample reports are fictional and must not be treated as patient evidence.

Reports are stored locally in app-private storage; dedicated encryption, authentication and a cloud medical-record backend are not implemented. No production security or clinical-readiness claim is made.

