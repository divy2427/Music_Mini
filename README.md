# Music Mini

A small Android WebView app for YouTube Music with an adapted YouTube Adaptive Ad Controller.

## Features

- Opens YouTube Music directly in an embedded WebView.
- Shows a branded loading screen until the first page content is visible, and an error screen if the page cannot load.
- Keeps the app toolbar clear of Android status-bar and display-cutout areas.
- Provides Home and Reload controls. The Settings button is currently removed.
- Enables best-effort adaptive ad playback speed, ad muting, and clicks on visible, enabled skip buttons. These controls are enabled by default, with a 256× maximum attempted speed.

The app only clicks a visible skip control. It does not bypass unskippable ads. YouTube or WebView may cap playback speed, and the ad markers and media elements can change.

## Build

Requirements: Android Studio, JDK 17 or newer, and Android SDK Platform 35.

From the project root, run:

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

## Privacy and limitations

The adapted controller stores no settings outside Android shared preferences and makes no separate network requests; YouTube Music itself still uses the network normally. JavaScript is enabled for the website, but no JavaScript bridge is exposed to page content. Navigation is restricted to the HTTPS hosts in the app allow-list.

Login, playback, background audio, DRM, and redirects can differ from the official YouTube Music app. The debug APK has not been live-tested against YouTube Music ads or on a physical device.
