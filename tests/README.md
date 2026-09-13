# Checks

From the repository root, with JDK 17 and Node.js installed:

```sh
mkdir -p /tmp/pvzge-version-tests
java -m jdk.compiler/com.sun.tools.javac.Main --release 11 -d /tmp/pvzge-version-tests app/src/main/java/com/pvzge/gardendless/UpdateVersion.java tests/UpdateVersionTest.java
java -cp /tmp/pvzge-version-tests com.pvzge.gardendless.UpdateVersionTest
node --test tests/game-bridge.test.cjs
./gradlew :app:compileDebugKotlin :app:lintDebug
```

## Update installation on Android

Use two release APKs with the same application ID and signing key, and increasing version codes. The release workflow now generates version codes from its run number; previously published APKs are not changed by this fix.

- Offer a newer stable version, but not an older version with a larger patch component or an equal version.
- Start offline, then reconnect and relaunch: the failed check must not suppress another check for 24 hours.
- Download with notifications disabled: progress must appear inside the app.
- Interrupt the download: no partial APK should launch, and Retry should check again.
- Disable installation from this source, download, grant permission and return: the downloaded APK should open in the installer without another download.
- Deny permission: show an actionable error; Retry should return to permission settings.
- Background the app while downloading: installer launch should wait until the activity resumes.
- Reject an APK with a wrong application ID or a non-increasing version code. Android must also reject incompatible signing certificates.
- After installation, verify the new version opens and the game-assets update flow runs.

Android compilation and device checks were not executed in the restricted workspace because the Gradle distribution could not be downloaded.

## Performance regression checks

```sh
node --test tests/*.test.cjs
java -m jdk.compiler/com.sun.tools.javac.Main --release 11 -d /tmp/pvzge-version-tests app/src/main/java/com/pvzge/gardendless/RenderSize.java tests/RenderSizeTest.java
java -cp /tmp/pvzge-version-tests com.pvzge.gardendless.RenderSizeTest
```

On a low-end device, compare the same saved level and wave in Original and Low-end mode after assets finish loading. Record the device, Android/WebView versions, canvas drawing-buffer dimensions, frame times over at least 60 seconds, and thermal state. Do not compare an idle menu against active gameplay.

- Use Back → Performance to change modes while playing; check persistence after relaunch.
- Verify 30/60 FPS limits through Cocos/WebView profiling, including after changing in-game frame-rate settings.
- Check plant placement, dragging, shovel/right-click and scrolling at the center and all four corners in each mode, both letterboxed and fullscreen.
- Confirm lower viewport/canvas dimensions in reduced modes, and restoration in Original mode.
- Background and resume during gameplay and while manually paused; a manual pause must remain paused.
- Run on Android 8.1–12 to verify the Android 13 darkening API is not invoked.

The automated checks cover frame-rate control and render sizing, not real GPU throughput or Android touch dispatch. Device performance and Android compilation remain unverified in this workspace.
