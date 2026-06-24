# PvZ2 Gardendless — Android

**Plants vs Zombies 2, reimagined as an endless garden — now on Android.**

A native Android wrapper for the [PvZ2 Gardendless](https://github.com/Gzh0821/pvzge_web) web game. No emulation, no cloud streaming — the full game runs locally on your device.

<p align="center">
  <a href="https://github.com/MrPurple666/pvzge-android/releases/latest"><img src="https://img.shields.io/github/v/release/MrPurple666/pvzge-android?label=latest&color=4CAF50" alt="Latest release"></a>
  <a href="https://github.com/MrPurple666/pvzge-android/actions/workflows/release.yml"><img src="https://img.shields.io/github/actions/workflow/status/MrPurple666/pvzge-android/release.yml?label=CI" alt="Build status"></a>
  <img src="https://img.shields.io/badge/min%20SDK-27%20(Android%208.1)-5277C3" alt="Min SDK">
  <img src="https://img.shields.io/badge/size-~1.1%20GB-orange" alt="APK size">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="License"></a>
</p>

> **Disclaimer:** This is an unofficial fan port of [PvZ2 Gardendless](https://github.com/Gzh0821/pvzge_web), which is itself an unofficial fan project. Neither project is affiliated with, endorsed by, or connected to PopCap Games, Electronic Arts, or the original Plants vs Zombies development team. All game assets, characters, and trademarks belong to their respective owners. These projects exist for preservation and educational purposes.

---

## Download

Get the latest APK from **[GitHub Releases](https://github.com/MrPurple666/pvzge-android/releases/latest)**.

> **~1.1 GB** — includes all game assets. No network required after install. The app checks for updates automatically and can install them in-app.

## Controls

The game expects mouse input. Touch gestures are translated automatically:

| You do this | Game sees this | Use for |
|---|---|---|
| **Tap / drag** with one finger | Left click + drag | Select plants, place them, drag sun |
| **Swipe** with two fingers | Scroll wheel | Zoom, browse the plant deck |
| **Tap** with two fingers | Right click | Shovel, context menus |
| **Long-press** with one finger | Right click | Same as above, one-handed |

A gesture guide appears on first launch.

## Under the hood

```
Game (JS/WASM) → WebViewAssetLoader → local files
Touch input    → MouseGameWebView   → MouseEvent injection
Rendering      → WebGL2 canvas      → 16:9 letterboxed
```

- **[Cocos Creator 3.8](https://www.cocos.com/creator)** engine compiled to web, running in Android's `WebView`
- **[WebViewAssetLoader](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader)** serves files from internal storage — no HTTP server needed
- **[MouseGameWebView](app/src/main/java/com/pvzge/gardendless/MouseGameWebView.kt)** converts touch → mouse events at the native level
- **16:9 letterboxing** keeps the game's 1024×640 design resolution centered on any screen

## Build

```bash
git clone https://github.com/MrPurple666/pvzge-android.git
cd pvzge-android

# Make sure the game files are at ../pvzge_web/docs/
# (clone https://github.com/Gzh0821/pvzge_web if needed)

./gradlew createGameZip   # one-time, ~75s — creates the 1.1 GB game bundle
./gradlew assembleDebug   # fast — ~12s after the zip exists
```

Requires **Java 17** and **Android SDK 35**.

## Automation

A [GitHub Actions workflow](.github/workflows/release.yml) checks the upstream game version daily. When it changes:

1. Bumps `versionCode` and `versionName`
2. Builds a signed release APK
3. Publishes a [GitHub Release](https://github.com/MrPurple666/pvzge-android/releases) with the APK

No manual intervention needed — the release just appears.

## Credits

This project stands on the shoulders of two remarkable efforts:

**[Gzh0821/pvzge_web](https://github.com/Gzh0821/pvzge_web)** — The original PvZ2 Gardendless web game. An extraordinary reverse-engineering project that faithfully recreates Plants vs Zombies 2 using only web technologies. Without this, none of the ports would exist.

**[Cateners/gardendless-android](https://github.com/Cateners/gardendless-android)** — The first Android port that figured out the hard problems: `WebViewAssetLoader` for serving local game files, `MouseGameWebView` for touch→mouse conversion, and 16:9 aspect ratio handling. This project adopts that proven architecture.

## Improvements

Built on the same foundation, with these additions:

| Area | What |
|---|---|
| **Reliability** | Atomic extraction with rollback, lifecycle-safe coroutines, retry-on-error dialogs |
| **UX** | Determinate extraction progress, pre-update save-data warning, gesture tutorial on first launch |
| **Input** | Horizontal scrolling, long-press for right-click, `ACTION_CANCEL` handling, right-click cursor positioning |
| **Performance** | Instant touch blocking via HTML injection, GPU darkening disabled, force-dark prevented |
| **Automation** | CI/CD pipeline auto-builds and releases, in-app updater checks GitHub Releases |
| **Dev** | `BuildConfig.DEBUG` remote WebView debugging, `onConsoleMessage` logging, `onReceivedError` tracking |
| **Polish** | Timestamped save exports, version shown during extraction, `resizeableActivity=false` for layout stability |

## FAQ

**Why is the APK so large?**  
It includes all game assets (~1.2 GB of textures, sounds, and animations). This means the game runs entirely offline after install — no additional downloads, no waiting.

**Will my save data survive an update?**  
Probably, but game version changes can occasionally break save compatibility. You'll see a warning before updating, and we recommend exporting your save first (in-game option).

**Why not on the Play Store?**  
The game is a fan project using copyrighted material. It's distributed via GitHub Releases to avoid store takedowns.

**Can I use a controller?**  
Not yet — the game is touch/mouse only. The touch→mouse conversion handles all input.

## License

GPL-3.0. The game files from [Gzh0821/pvzge_web](https://github.com/Gzh0821/pvzge_web) carry their own license.
