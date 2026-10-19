# Building and CI Pipeline

This project uses **AI Studio** for interactive authoring and **GitHub Actions** as the authoritative build environment for assembling installable APKs, compiling native components, and managing artifacts.

---

## 1. SDK & Compiler Alignment

- **Compile SDK**: Configured as `release(36) { minorApiLevel = 1 }` in `/app/build.gradle.kts`.
- **SDK Platforms**: CI explicitly provisions both `platforms;android-36` and `platforms;android-36.1` via `sdkmanager` alongside `build-tools;36.0.0` to satisfy the minor API level.
- **Java & Gradle**: Configured to Eclipse Temurin JDK 21 and Gradle 9.3.1.

---

## 2. Debug Signing & Update Continuity

The Gradle build in `/app/build.gradle.kts` expects a debug keystore at `${rootDir}/debug.keystore`:
```kotlin
create("debugConfig") {
  storeFile = file("${rootDir}/debug.keystore")
  storePassword = "android"
  keyAlias = "androiddebugkey"
  keyPassword = "android"
}
```

### Keystore Modes in CI

1. **Persistent Mode (Recommended for device updates)**:
   - Configure a GitHub Repository Secret named `CI_DEBUG_KEYSTORE_BASE64`.
   - **How to create and export your persistent debug keystore**:
     ```bash
     # Generate a stable keystore (if you don't already have one)
     keytool -genkey -v -keystore my-debug.keystore -storepass android \
       -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
       -validity 10000 -dname "CN=Android Debug,O=Android,C=US"

     # Base64-encode it for the GitHub Secret
     base64 -w 0 my-debug.keystore > keystore_base64.txt
     ```
   - In GitHub: **Settings -> Secrets and variables -> Actions -> New repository secret** -> Name: `CI_DEBUG_KEYSTORE_BASE64`, Value: paste the contents of `keystore_base64.txt`.
   - When configured, all CI builds are signed with the same certificate, enabling **seamless in-place APK updates (`adb install -r`) on your phone without uninstalling**.

2. **Ephemeral Mode (Fallback)**:
   - If `CI_DEBUG_KEYSTORE_BASE64` is not configured, the runner generates an ephemeral key for that single run.
   - **Limitation**: APKs built across different ephemeral CI runs cannot update each other (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Reinstallation requires uninstalling the prior APK from the phone first.
   - The actual signing certificate fingerprint (SHA-256) is recorded in `ci-artifacts/SIGNING_IDENTITY.txt` inside the artifact zip.

3. **AI Studio Preview**:
   - The container provides its own `${rootDir}/debug.keystore` locally. Existing preview signing remains untouched and operational.

---

## 3. Log Capture & Diagnostics

- The workflow executes Gradle tasks with `set -o pipefail` and pipes stdout/stderr through `tee` to `build-logs/`:
  - `build-logs/test-debug.log`
  - `build-logs/assemble-debug.log`
- If Gradle fails, the exact failure exit code is preserved, and the workflow uploads `test-and-build-reports` under `if: always()` with `if-no-files-found: warn` so failure diagnostics are never lost.

---

## 4. JNI Library Verification

- **Current Gate (Kotlin-only)**:
  - Transporting Termux's exec-server WebSocket stream requires no JNI shared libraries.
  - The workflow defaults `EXPECTED_JNI_LIBS` to empty and reports `JNI verification: NOT APPLICABLE`.
- **Future Native Modules**:
  - When C/C++ sources or prebuilt `.so` libraries are introduced, specify `expected_jni_libs` via workflow dispatch (e.g., `lib/arm64-v8a/libterminal.so`).
  - The workflow inspects the APK packaging and fails with a non-zero exit code if any declared library is missing.

---

## 5. Artifacts Produced

1. **`app-debug-apk` (Mandatory - fails workflow if missing)**:
   - `app-debug.apk`: Real installable APK.
   - `SHA256SUMS.txt`: Checksum manifest for download integrity.
   - `SIGNING_IDENTITY.txt`: Certificate fingerprints and keystore mode documentation.
   - `apk_contents.txt`: Complete unzipped package index audit.
2. **`test-and-build-reports` (Diagnostic - always uploaded)**:
   - Raw logs in `build-logs/`.
   - Unit and Robolectric test HTML/XML reports in `app/build/reports/`.
