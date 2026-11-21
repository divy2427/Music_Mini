# Music Mini

Music Mini is a lightweight, independent Android app that opens the YouTube Music website in a WebView. Start a song in the app, then keep listening while you use another app or lock your screen.

## Features

| Feature | What it does |
| --- | --- |
| Background playback | Keeps music playing and advances to the next track in the queue when Music Mini is in the background or the screen is locked. |
| Android media controls | Shows the current song, artist, and cover art with **Play**, **Pause**, and **Next**. Next is available when the player exposes an enabled next-track control. |
| Playback progress | Shows elapsed time and duration. Drag the progress bar in the expanded media card to seek within a song. |
| Automatic ad handling | Mutes detected ads, requests faster playback up to **16×**, and taps **Skip** when YouTube displays an enabled button. Auto-skip can run during active background playback. |
| Simple navigation | Provides **Home**, **Reload**, a loading screen, and a retry option for page load failures. |

## Run on Android

1. Open Music Mini and start a song or playlist. Choose a playlist with at least two tracks to try background queue playback.
2. Switch apps or lock the screen once the song starts.
3. Open Quick Settings and expand the media card to pause, resume, go to the next track, or drag the time bar. Lock-screen controls depend on the device's Android and notification settings.

## Build from source

The project uses Kotlin and the Gradle wrapper. Install **JDK 17 or newer** and **Android SDK Platform 35**. The app targets API 35 and sets its minimum to API 23 (Android 6.0).

Open the project in Android Studio, let Gradle sync, and run the **app** configuration on a connected device. To build a debug APK from the project root:

**Windows PowerShell**

```powershell
.\gradlew.bat :app:assembleDebug
```

**macOS or Linux**

```bash
bash ./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## How it works

- `MainActivity` hosts YouTube Music in an Android WebView and reads the player's title, artwork, and playback state.
- `BackgroundPlaybackService` maintains Android media playback in the background and publishes a `MediaSession` for controls, artwork, and seeking.
- The ad controller observes YouTube Music's player and sends a WebView touch to a visible, enabled **Skip** button. Ad muting and playback speed are handled separately.

## Verified on a physical device

Tested on a **OnePlus CPH2943 running Android 16**:

- A queued song started and kept playing with another app open and with the screen locked.
- Real skippable ads cleared after automatic taps in both states. In those two tests, the tap came roughly **80 ms after the Skip button became available**.
- Quick Settings showed the song title and cover art, updated both on track change, and successfully sought to a new playback position.

## Limitations and network use

Music Mini relies on the YouTube Music website. Sign-in, playback, background behavior, and ad controls may vary with YouTube, Android, and the installed WebView version. Auto-skip requires YouTube to provide an enabled **Skip** button. The **16×** rate is an upper limit for requests, and actual playback speed can be lower.

The WebView uses the network and cookies to load YouTube Music. Music Mini also fetches the current cover image from an allow-listed YouTube image host for the Android media card. App preferences are stored locally.
