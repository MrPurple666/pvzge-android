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
