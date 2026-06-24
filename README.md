# PvZ2 Gardendless — Android Port

**Plants vs Zombies 2 reimagined as an endless garden.** Native Android wrapper for the [PvZ2 Gardendless](https://github.com/Gzh0821/pvzge_web) web game.

[![Build and Release](https://github.com/MrPurple666/pvzge-android/actions/workflows/release.yml/badge.svg)](https://github.com/MrPurple666/pvzge-android/actions/workflows/release.yml)

## Download

Go to [Releases](https://github.com/MrPurple666/pvzge-android/releases) and grab the latest APK. ~1.1 GB — includes all game assets, no network required after install.

## How it works

The game is a [Cocos Creator 3.8](https://www.cocos.com/creator) web build. This project wraps it in a native Android `WebView` using `WebViewAssetLoader` to serve game files directly from local storage — no HTTP server, no Flutter engine, minimal overhead.

Touch input is converted to mouse events via a custom `MouseGameWebView`: single-finger drag = left click, two-finger swipe = scroll wheel, two-finger tap = right-click, long-press = right-click.

## Build from source

```bash
# 1. Clone with game submodule
git clone https://github.com/MrPurple666/pvzge-android.git
cd pvzge-android

# 2. Place the game files (or symlink if you have pvzge_web checked out)
#    The game files should be at ../pvzge_web/docs/ relative to this project

# 3. Create the game zip (first time only, ~75s)
./gradlew createGameZip

# 4. Build
./gradlew assembleDebug      # debug APK
./gradlew assembleRelease    # signed release APK
```

Output: `app/build/outputs/apk/debug/app-debug.apk` or `app/build/outputs/apk/release/app-release.apk`

## Automated builds

A [GitHub Actions workflow](.github/workflows/release.yml) runs daily and checks the upstream game version. When a new version is detected:
1. Updates `versionCode` and `versionName`
2. Builds a signed release APK
3. Creates a GitHub Release with the APK attached

The app also has a built-in updater that checks for new releases and offers to download + install them.

## Controls

| Gesture | Action |
|---|---|
| Single-finger tap/drag | Left click (select, place, drag) |
| Two-finger swipe | Scroll wheel (vertical & horizontal) |
| Two-finger tap | Right click (shovel, context menu) |
| Long-press | Right click (one-finger alternative) |

## Credits

This project builds on the excellent work of others:

- **[Gzh0821/pvzge_web](https://github.com/Gzh0821/pvzge_web)** — the original PvZ2 Gardendless web game. An extraordinary reverse-engineering effort that brings Plants vs Zombies 2 to the web using Cocos Creator.
- **[Cateners/gardendless-android](https://github.com/Cateners/gardendless-android)** — the reference Android port that pioneered the `WebViewAssetLoader` + `MouseGameWebView` architecture. Their touch-to-mouse conversion and 16:9 letterboxing are the foundation this port is built on.

## What's different from the reference

While sharing the same core architecture, this port adds several features:

- **Atomic extraction** — extracts to a temp directory first, then swaps into place. If the app is killed mid-extraction, it rolls back to the previous working version instead of leaving a corrupted install.
- **Extraction progress** — determinate progress bar with percentage, rather than an indeterminate spinner.
- **Pre-update save-data warning** — warns about potential save incompatibility before updating, with the option to postpone.
- **Horizontal scrolling** — two-finger swipe detects both axes, not just vertical.
- **Long-press for right-click** — single-finger accessibility for shovel and context menus.
- **Instant touch blocking** — injected into the HTML before the page loads via `shouldInterceptRequest`, rather than polling after page load.
- **In-app updater** — checks GitHub Releases and offers to download + install new versions.
- **Automated CI/CD** — GitHub Actions detects upstream game updates, builds, and releases automatically.
- **Gesture tutorial** — first-launch overlay explaining the touch controls.
- **Remote debugging** — `WebView` debugging enabled in debug builds.
- **GPU optimizations** — disables algorithmic darkening, force-dark, and unnecessary safe browsing checks.
- **Timestamped save exports** — each export gets a unique filename instead of overwriting the previous one.
- **Lifecycle safety** — uses `lifecycleScope` instead of `GlobalScope` to prevent crashes on activity destruction.

## License

This project is licensed under GPL-3.0, matching the reference port. The game files are from [Gzh0821/pvzge_web](https://github.com/Gzh0821/pvzge_web) and carry their own license.
