package com.example.youtubemusicminibrowser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.ServiceConnection
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var errorPanel: LinearLayout
    private lateinit var splashPanel: LinearLayout
    private var hasPageContent = false
    private var activityVisible = false
    private var skipTapPending = false
    private val playbackHandler = Handler(Looper.getMainLooper())
    private var playbackServiceRunning = false
    private var playbackService: BackgroundPlaybackService? = null
    private var playbackServiceBound = false
    private val playbackConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            playbackService = (binder as BackgroundPlaybackService.LocalBinder).service.apply {
                onCommand = ::controlWebPlayback
                onSeek = ::seekWebPlayback
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            playbackService = null
            playbackServiceRunning = false
        }
    }
    private var lastPlayingAt = 0L
    private val checkPlayback = object : Runnable {
        override fun run() {
            webView.evaluateJavascript(
                """
                (() => {
                    if (location.origin !== 'https://music.youtube.com') return null;
                    const media = document.querySelector('#movie_player video,#movie_player audio');
                    if (!media || !media.currentSrc) return null;
                    const next = Array.from(document.querySelectorAll(
                        'ytmusic-miniplayer button[aria-label="Next"], ytmusic-player-page button[aria-label="Next"], ' +
                        'ytmusic-player-bar .next-button'
                    )).some(button => !button.closest(':disabled,[disabled],[aria-disabled="true"]'));
                    const metadata = navigator.mediaSession?.metadata;
                    const playerText = selector => document.querySelector(selector)?.textContent.trim();
                    const title = playerText('ytmusic-player-controls .title') ||
                        playerText('ytmusic-miniplayer .ytmusicTrackInfoTitle') ||
                        playerText('ytmusic-player-bar .title') || metadata?.title || '';
                    const artist = playerText('ytmusic-player-controls .byline') ||
                        playerText('ytmusic-miniplayer .ytmusicTrackInfoBylineItem') ||
                        playerText('ytmusic-player-bar .byline') || metadata?.artist || '';
                    const imageUrl = selector => {
                        const image = document.querySelector(selector);
                        return image?.currentSrc || image?.getAttribute('src');
                    };
                    const artwork = imageUrl('ytmusic-player #song-image img') ||
                        imageUrl('ytmusic-miniplayer img.ytmusicTrackInfoThumbnail') ||
                        imageUrl('ytmusic-player-bar .thumbnail img') || metadata?.artwork?.[0]?.src || '';
                    const playing = !media.paused && !media.ended;
                    const song = !media.closest('.ad-showing,.ad-interrupting') &&
                        Number.isFinite(media.duration) && media.duration > 0;
                    return [playing, next, title, artist, artwork,
                        song ? Math.round(media.currentTime * 1000) : -1,
                        song ? Math.round(media.duration * 1000) : 0,
                        song && playing && !media.seeking && media.readyState >= 3 ? media.playbackRate : 0,
                        song && media.seekable.length > 0];
                })()
                """.trimIndent()
            ) { result ->
                if (isDestroyed) return@evaluateJavascript
                val state = result?.takeIf { it.startsWith("[") }?.let(::JSONArray)
                val playing = state?.optBoolean(0) == true
                playbackService?.updatePlayback(playing, state?.optBoolean(1) == true,
                    state?.optString(2).orEmpty().take(160), state?.optString(3).orEmpty().take(160),
                    state?.optString(4).orEmpty(), PlaybackTimeline(
                        state?.optLong(5, PlaybackState.PLAYBACK_POSITION_UNKNOWN)
                            ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        state?.optLong(6) ?: 0,
                        state?.optDouble(7, 0.0)?.toFloat() ?: 0f,
                        state?.optBoolean(8) == true
                    ))
                if (playing) {
                    lastPlayingAt = SystemClock.elapsedRealtime()
                    if (activityVisible && !playbackServiceRunning) {
                        startPlaybackService()
                    }
                } else if (SystemClock.elapsedRealtime() - lastPlayingAt > 15_000) {
                    stopPlaybackService()
                    if (state == null) playbackService?.clearNotification()
                }
                if (activityVisible || playbackServiceRunning || playbackService?.hasNotification == true) {
                    playbackHandler.postDelayed(this, if (activityVisible || playbackServiceRunning) 750 else 2500)
                }
            }
        }
    }
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            webView.evaluateJavascript(
                "window.__musicMiniScreenOff = ${!powerManager.isInteractive};",
                null
            )
        }
    }

    private val prefs by lazy { getSharedPreferences("mini_browser_settings", Context.MODE_PRIVATE) }
    private val homeUrl = "https://music.youtube.com/"
    private val allowedHosts = setOf(
        "music.youtube.com", "youtube.com", "www.youtube.com", "m.youtube.com",
        "accounts.google.com", "consent.youtube.com"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!prefs.getBoolean("max_speed_256_migrated", false)) {
            prefs.edit().putInt("max_speed", 256).putBoolean("max_speed_256_migrated", true).apply()
        }
        window.statusBarColor = Color.rgb(17, 17, 17)
        window.navigationBarColor = Color.rgb(17, 17, 17)
        buildUi()
        playbackServiceBound = bindService(playbackServiceIntent(), playbackConnection, Context.BIND_AUTO_CREATE)
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                if (consoleMessage.message() != "MusicMini:skip-ready") {
                    return super.onConsoleMessage(consoleMessage)
                }
                tapAdSkipControl()
                return true
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                if (newProgress >= 100) {
                    applyMediaSettings()
                }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (isAllowed(request.url)) return false
                Toast.makeText(this@MainActivity, "Blocked external destination", Toast.LENGTH_SHORT).show()
                return true
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                errorPanel.visibility = View.GONE
                if (!hasPageContent) splashPanel.visibility = View.VISIBLE
                progress.visibility = View.VISIBLE
            }
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                hasPageContent = true
                splashPanel.visibility = View.GONE
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                if (url != null) {
                    if (!hasPageContent && isAllowed(Uri.parse(url))) {
                        hasPageContent = true
                        splashPanel.visibility = View.GONE
                    }
                }
                applyMediaSettings()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showError("Couldn't load YouTube Music. Check your connection and try again.")
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {
                if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                    showError("YouTube Music couldn't be opened (HTTP ${errorResponse.statusCode}). Try again.")
                }
            }
        }
        val restored = savedInstanceState?.getBundle("web_view_state")?.let(webView::restoreState)
        if (restored == null) {
            val previousUrl = savedInstanceState?.getString("web_view_url")
            webView.loadUrl(previousUrl?.takeIf { isAllowed(Uri.parse(it)) } ?: homeUrl)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 8, 8))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(10), dp(6))
            setBackgroundColor(Color.rgb(18, 18, 18))
        }
        val brandIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_music_play)
            contentDescription = "YouTube Music Mini"
        }
        toolbar.addView(brandIcon, LinearLayout.LayoutParams(dp(34), dp(34)))
        val title = TextView(this).apply {
            text = "Music Mini"
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(9), 0, dp(4), 0)
        }
        toolbar.addView(title, LinearLayout.LayoutParams(0, dp(44), 1f))
        listOf(
            toolbarButton("Home", "Go to YouTube Music home") { webView.loadUrl(homeUrl) },
            toolbarButton("Reload", "Reload YouTube Music") { webView.reload() }
        ).forEach { button ->
            toolbar.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply {
                leftMargin = dp(4)
            })
        }
        root.addView(toolbar, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(255, 0, 51))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(50, 20, 27))
        }
        root.addView(progress, LinearLayout.LayoutParams.MATCH_PARENT, dp(3))

        val frame = FrameLayout(this)
        webView = object : WebView(this) {
            private var wasVisible = false

            override fun onWindowVisibilityChanged(visibility: Int) {
                if (visibility == View.VISIBLE) wasVisible = true
                // Keep the player active when Android hides the window, including on screen lock.
                if (wasVisible && visibility != View.VISIBLE) return
                super.onWindowVisibilityChanged(visibility)
            }
        }
        frame.addView(webView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        errorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(18, 18, 18))
            visibility = View.GONE
        }
        val message = TextView(this).apply {
            text = "Unable to load the page."
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        errorPanel.addView(message, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        errorPanel.addView(toolbarButton("Try again", "Retry loading YouTube Music") {
            errorPanel.visibility = View.GONE
            webView.reload()
        })
        frame.addView(errorPanel, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        splashPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(8, 8, 8))
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        splashPanel.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_music_play)
            contentDescription = "YouTube Music Mini"
        }, LinearLayout.LayoutParams(dp(76), dp(76)).apply { bottomMargin = dp(20) })
        splashPanel.addView(TextView(this).apply {
            text = "Music Mini"
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        splashPanel.addView(TextView(this).apply {
            text = "Getting YouTube Music ready…"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(20))
        }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        splashPanel.addView(ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.rgb(255, 0, 51))
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        frame.addView(splashPanel, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        setContentView(root)
    }

    private fun showSettings() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        fun toggle(label: String, key: String, defaultValue: Boolean) = Switch(this).apply {
            text = label
            isChecked = prefs.getBoolean(key, defaultValue)
        }
        val enabledSwitch = toggle("Enable ad controls", "extension_enabled", true)
        val speedSwitch = toggle("Adaptive ad speed", "adaptive_speed", true)
        val muteSwitch = toggle("Mute ads automatically", "auto_mute", true)
        val skipSwitch = toggle("Click skip when available", "auto_skip", true)
        listOf(enabledSwitch, speedSwitch, muteSwitch, skipSwitch).forEach(layout::addView)

        val targetOptions = doubleArrayOf(0.15, 0.25, 0.5, 1.0, 1.5)
        val targetLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(2))
        }
        val targetSeek = SeekBar(this).apply {
            max = targetOptions.lastIndex
            val saved = prefs.getFloat("target_seconds", 0.15f).toDouble()
            progress = targetOptions.indices.minByOrNull { kotlin.math.abs(targetOptions[it] - saved) } ?: 0
        }
        fun updateTargetLabel() {
            targetLabel.text = "Target ad time: ${targetOptions[targetSeek.progress]} seconds"
        }
        updateTargetLabel()
        targetSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) = updateTargetLabel()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        layout.addView(targetLabel)
        layout.addView(targetSeek)

        val speedOptions = intArrayOf(16, 32, 64, 128, 256)
        val maxSpeedLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(2))
        }
        val maxSpeedSeek = SeekBar(this).apply {
            max = speedOptions.lastIndex
            progress = speedOptions.indexOf(prefs.getInt("max_speed", 256)).coerceAtLeast(0)
        }
        fun updateMaxSpeedLabel() { maxSpeedLabel.text = "Maximum speed: ${speedOptions[maxSpeedSeek.progress]}×" }
        updateMaxSpeedLabel()
        maxSpeedSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) = updateMaxSpeedLabel()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        layout.addView(maxSpeedLabel)
        layout.addView(maxSpeedSeek)

        val note = TextView(this).apply {
            text = "Ad detection, playback-rate changes, and skip-button detection are best-effort. The app only clicks visible, enabled skip controls. 256× is an attempted limit; YouTube or WebView may cap playback speed, and unskippable ads cannot be skipped."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(12), 0, dp(4))
        }
        layout.addView(note)
        AlertDialog.Builder(this)
            .setTitle("YouTube ad controls")
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putBoolean("extension_enabled", enabledSwitch.isChecked)
                    .putBoolean("adaptive_speed", speedSwitch.isChecked)
                    .putBoolean("auto_mute", muteSwitch.isChecked)
                    .putBoolean("auto_skip", skipSwitch.isChecked)
                    .putFloat("target_seconds", targetOptions[targetSeek.progress].toFloat())
                    .putInt("max_speed", speedOptions[maxSpeedSeek.progress])
                    .apply()
                applyMediaSettings()
            }
            .show()
    }

    override fun onStart() {
        super.onStart()
        updateActivityVisibility(true)
        playbackHandler.removeCallbacks(checkPlayback)
        playbackHandler.post(checkPlayback)
    }

    override fun onStop() {
        updateActivityVisibility(false)
        super.onStop()
    }

    private fun updateActivityVisibility(visible: Boolean) {
        activityVisible = visible
        webView.evaluateJavascript("window.__musicMiniActivityVisible = $visible;", null)
    }

    private fun playbackServiceIntent() = Intent(this, BackgroundPlaybackService::class.java)

    private fun startPlaybackService(): Boolean {
        if (playbackServiceRunning) return true
        return try {
            ContextCompat.startForegroundService(this, playbackServiceIntent())
            playbackServiceRunning = true
            true
        } catch (_: IllegalStateException) {
            false
        }
    }

    private fun stopPlaybackService() {
        if (!playbackServiceRunning) return
        playbackService?.leaveForeground()
        stopService(playbackServiceIntent())
        playbackServiceRunning = false
    }

    private fun controlWebPlayback(command: String) {
        val url = webView.url?.let(Uri::parse) ?: return
        if (url.scheme != "https" || url.host != "music.youtube.com") return
        if (command != "pause") {
            lastPlayingAt = SystemClock.elapsedRealtime()
            if (!startPlaybackService()) return
        }
        val action = when (command) {
            "play" -> "media.play().catch(() => {});"
            "pause" -> "media.pause();"
            "next" -> """
                const controls = document.querySelectorAll(
                    'ytmusic-miniplayer button[aria-label="Next"], ytmusic-player-page button[aria-label="Next"], ' +
                    'ytmusic-player-bar .next-button'
                );
                for (const button of controls) {
                    if (!button.getClientRects().length ||
                        button.closest(':disabled,[disabled],[aria-disabled="true"]')) continue;
                    button.click();
                    break;
                }
            """.trimIndent()
            else -> return
        }
        webView.evaluateJavascript(
            """
            (() => {
                if (location.origin !== 'https://music.youtube.com') return;
                const media = document.querySelector('#movie_player video,#movie_player audio');
                if (!media) return;
                $action
            })()
            """.trimIndent(), null
        )
    }

    private fun seekWebPlayback(positionMs: Long) {
        if (positionMs < 0) return
        webView.evaluateJavascript(
            """
            (() => {
                if (location.origin !== 'https://music.youtube.com') return;
                const media = document.querySelector('#movie_player video,#movie_player audio');
                if (!media || media.closest('.ad-showing,.ad-interrupting') ||
                    !Number.isFinite(media.duration) || !media.seekable.length) return;
                media.currentTime = Math.min(media.duration, $positionMs / 1000);
            })()
            """.trimIndent(), null
        )
    }

    private fun tapAdSkipControl() {
        val page = webView.url ?: return
        val uri = Uri.parse(page)
        if (uri.scheme != "https" || uri.host != "music.youtube.com" ||
            !canTapAdSkipControl() || skipTapPending) return

        skipTapPending = true
        webView.evaluateJavascript("window.__ytAdaptiveAdController?.skipTarget()") { value ->
            skipTapPending = false
            if (!canTapAdSkipControl() || webView.url != page ||
                value == null || !value.startsWith("[")) return@evaluateJavascript
            val coordinates = runCatching { JSONArray(value) }.getOrNull() ?: return@evaluateJavascript
            val x = coordinates.optDouble(0)
            val y = coordinates.optDouble(1)
            if (!(x > 0 && x < 1 && y > 0 && y < 1)) return@evaluateJavascript
            val time = SystemClock.uptimeMillis()
            for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(
                    time, time, action, (x * webView.width).toFloat(), (y * webView.height).toFloat(), 0
                ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try {
                    webView.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
        }
    }

    private fun canTapAdSkipControl(): Boolean =
        !isDestroyed && webView.isAttachedToWindow &&
                ((activityVisible && webView.hasWindowFocus()) || playbackServiceRunning)

    private fun applyMediaSettings() {
        if (!::webView.isInitialized || webView.url == null) return
        val enabled = prefs.getBoolean("extension_enabled", true)
        val adaptiveSpeed = prefs.getBoolean("adaptive_speed", true)
        val autoMute = prefs.getBoolean("auto_mute", true)
        val autoSkip = prefs.getBoolean("auto_skip", true)
        val targetSeconds = prefs.getFloat("target_seconds", 0.15f).coerceIn(0.15f, 1.5f)
        val maxSpeed = prefs.getInt("max_speed", 256).coerceIn(16, 256)
        val script = """
            (function() {
              'use strict';
              window.__musicMiniScreenOff = ${!powerManager.isInteractive};
              window.__musicMiniActivityVisible = $activityVisible;
              if (location.hostname === 'music.youtube.com' && !window.__musicMiniQueueFallback) {
                window.__musicMiniQueueFallback = true;
                document.addEventListener('ended', function(event) {
                  const media = event.target;
                  if (!(media instanceof HTMLMediaElement) ||
                      (!window.__musicMiniScreenOff && window.__musicMiniActivityVisible)) return;
                  if (!media.closest('#movie_player, .html5-video-player, ytmusic-player') ||
                      media.closest('.ad-showing,.ad-interrupting')) return;
                  const source = media.currentSrc;
                  const track = location.href;
                  function advanceIfStillEnded() {
                    if ((!window.__musicMiniScreenOff && window.__musicMiniActivityVisible) ||
                        !media.isConnected || !media.ended ||
                        media.currentSrc !== source || location.href !== track ||
                        media.closest('.ad-showing,.ad-interrupting')) return;
                    const nextButtons = document.querySelectorAll(
                      'ytmusic-miniplayer button[aria-label="Next"], ' +
                      'ytmusic-player-page button[aria-label="Next"], ' +
                      'ytmusic-player-bar .next-button, #movie_player .ytp-next-button'
                    );
                    for (const control of nextButtons) {
                      const button = control.querySelector('button,[role="button"]') || control;
                      if (!button.getClientRects().length ||
                          button.closest(':disabled,[disabled],[aria-disabled="true"],[hidden],[inert]')) continue;
                      const style = getComputedStyle(button);
                      if (style.visibility !== 'visible' || Number(style.opacity) === 0) continue;
                      button.click();
                      break;
                    }
                  }
                  if (window.__musicMiniScreenOff) {
                    Promise.resolve().then(advanceIfStillEnded);
                  } else {
                    setTimeout(advanceIfStillEnded, 500);
                  }
                }, true);
              }
              const incoming = {
                enabled: $enabled,
                adaptiveSpeed: $adaptiveSpeed,
                autoMute: $autoMute,
                autoSkip: $autoSkip,
                targetSeconds: $targetSeconds,
                maxSpeed: $maxSpeed
              };
              const key = '__ytAdaptiveAdController';
              let controller = window[key];
              if (!controller) {
                const defaults = { enabled:true, adaptiveSpeed:true, autoMute:true, autoSkip:true, targetSeconds:0.15, maxSpeed:256 };
                const saved = new WeakMap();
                let adWasActive = false;
                let timer = null;
                let lastSkip = 0;
                let observer = null;
                let poller = null;
                const adMarkers = [
                  '.ytp-ad-player-overlay', '.ytp-ad-text', '.ytp-ad-preview-container',
                  '.ytp-ad-skip-button', '.ytp-ad-skip-button-modern', '.ytp-skip-ad-button',
                  '.ytp-skip-ad-button-modern', '.ytp-ad-overlay-container'
                ];
                const skipSelectors = [
                  '.ytp-ad-skip-button-slot button',
                  '.ytp-ad-skip-button-container button',
                  '.ytp-skip-ad-button-container button',
                  '.ytp-skip-ad-button:not([disabled])',
                  '.ytp-skip-ad-button-modern:not([disabled])',
                  '.ytp-ad-skip-button:not([disabled])',
                  '.ytp-ad-skip-button-modern:not([disabled])',
                  "button[aria-label*='Skip ad' i]:not([disabled])",
                  "button[aria-label*='Skip ads' i]:not([disabled])",
                  "button[title*='Skip ad' i]:not([disabled])",
                  "[role='button'][aria-label*='Skip ad' i]:not([aria-disabled='true'])",
                  "[role='button'][title*='Skip ad' i]:not([aria-disabled='true'])"
                ];
                let settings = Object.assign({}, defaults);
                function visible(el) {
                  if (!el) return false;
                  const style = getComputedStyle(el), rect = el.getBoundingClientRect();
                  return style.display !== 'none' && style.visibility !== 'hidden' && Number(style.opacity) !== 0 && rect.width > 0 && rect.height > 0;
                }
                function playerRoots() {
                  return [document.querySelector('#movie_player'), document.querySelector('.html5-video-player'),
                    document.querySelector('ytmusic-player'), document.querySelector('ytmusic-player-page')].filter(Boolean);
                }
                function isAd() {
                  for (const root of playerRoots()) {
                    const cls = typeof root.className === 'string' ? root.className : (root.getAttribute('class') || '');
                    if (/\bad-showing\b|\bad-interrupting\b/i.test(cls) || root.matches('.ad-showing,.ad-interrupting')) return true;
                  }
                  for (const selector of adMarkers) {
                    for (const element of document.querySelectorAll(selector)) if (visible(element)) return true;
                  }
                  return false;
                }
                function mediaItems() { return Array.from(document.querySelectorAll('video,audio')); }
                function remember(media) {
                  if (!saved.has(media)) saved.set(media, { rate:media.playbackRate || 1, muted:!!media.muted, volume:Number.isFinite(media.volume) ? media.volume : 1 });
                }
                function speed(media) {
                  if (!settings.adaptiveSpeed || !media.isConnected) return;
                  remember(media);
                  const remaining = Number.isFinite(media.duration) && media.duration > 0 && Number.isFinite(media.currentTime)
                    ? Math.max(0.1, media.duration - media.currentTime) : null;
                  const target = Math.max(0.15, Math.min(1.5, Number(settings.targetSeconds) || 0.15));
                  const cap = Math.max(1, Math.min(16, Number(settings.maxSpeed) || 16));
                  const desired = remaining === null ? Math.min(cap, 16) : Math.max(1, Math.min(cap, remaining / target));
                  if (media.playbackRate >= desired - 0.05) return;
                  for (let rate = desired; rate > 1; rate = Math.max(1, rate / 2)) {
                    if (media.playbackRate >= rate - 0.05) return;
                    try {
                      media.playbackRate = rate;
                      if (media.playbackRate > 1) return;
                    } catch (_) {}
                  }
                }
                function mute(media) {
                  if (!settings.autoMute) return;
                  remember(media);
                  try { media.muted = true; } catch (_) {}
                }
                function skipTarget() {
                  if (location.origin !== 'https://music.youtube.com' || !settings.enabled ||
                      !settings.autoSkip || !isAd()) return null;
                  const candidates = new Set();
                  for (const selector of skipSelectors) {
                    for (const element of document.querySelectorAll(selector)) {
                      candidates.add(element.closest('button,[role="button"]') ||
                        element.querySelector('button,[role="button"]') || element);
                    }
                  }
                  for (const player of document.querySelectorAll(
                    '#movie_player.ad-showing, #movie_player.ad-interrupting, ytmusic-player.ad-showing'
                  )) {
                    for (const button of player.querySelectorAll('button,[role="button"]')) {
                      const labels = [button.getAttribute('aria-label'), button.getAttribute('title'), button.textContent];
                      if (labels.some(label => /^skip(?: ads?)?$/i.test((label || '').trim()))) {
                        candidates.add(button);
                      }
                    }
                  }
                  for (const button of candidates) {
                    if (!visible(button) ||
                        button.closest(':disabled,[disabled],[aria-disabled="true"],[hidden],[inert]')) continue;
                    const viewport = window.visualViewport;
                    if (!viewport) return null;
                    const rect = button.getBoundingClientRect();
                    const x = (Math.max(rect.left, viewport.offsetLeft) +
                      Math.min(rect.right, viewport.offsetLeft + viewport.width)) / 2;
                    const y = (Math.max(rect.top, viewport.offsetTop) +
                      Math.min(rect.bottom, viewport.offsetTop + viewport.height)) / 2;
                    const hit = document.elementFromPoint(x, y);
                    if (!hit || !button.contains(hit)) continue;
                    return [(x - viewport.offsetLeft) / viewport.width, (y - viewport.offsetTop) / viewport.height];
                  }
                  return null;
                }
                function skip() {
                  if (Date.now() - lastSkip < 1000 || !skipTarget()) return;
                  lastSkip = Date.now();
                  console.info('MusicMini:skip-ready');
                }
                function restore() {
                  for (const media of mediaItems()) {
                    const prior = saved.get(media);
                    if (!prior) continue;
                    try { media.playbackRate = prior.rate; } catch (_) {}
                    try { media.muted = prior.muted; } catch (_) {}
                    try { media.volume = prior.volume; } catch (_) {}
                    saved.delete(media);
                  }
                }
                function scan() {
                  if (!settings.enabled) {
                    if (adWasActive) restore();
                    adWasActive = false;
                    return;
                  }
                  const active = isAd();
                  skip();
                  if (active) {
                    for (const media of mediaItems()) { speed(media); mute(media); }
                  } else if (adWasActive) {
                    restore();
                  }
                  adWasActive = active;
                }
                function schedule() {
                  if (timer !== null) return;
                  timer = setTimeout(function() { timer = null; scan(); }, 120);
                }
                controller = {
                  skipTarget,
                  update: function(next) {
                    settings = Object.assign({}, defaults, settings, next);
                    if (!settings.enabled) { restore(); adWasActive = false; }
                    schedule();
                  }
                };
                window[key] = controller;
                observer = new MutationObserver(function() {
                  if (settings.enabled) skip();
                  schedule();
                });
                observer.observe(document.documentElement, { childList:true, characterData:true, subtree:true,
                  attributes:true, attributeFilter:['class','aria-label','aria-disabled','title','disabled','hidden','inert','style'] });
                ['play','playing','loadedmetadata','timeupdate','durationchange','ratechange','emptied'].forEach(function(name) {
                  document.addEventListener(name, schedule, true);
                });
                poller = setInterval(function() { if (settings.enabled) scan(); }, 300);
                schedule();
              }
              controller.update(incoming);
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun toolbarButton(label: String, description: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        contentDescription = description
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(10), 0, dp(10), 0)
        background = GradientDrawable().apply {
            setColor(Color.rgb(39, 39, 39))
            cornerRadius = dp(12).toFloat()
        }
        setOnClickListener { action() }
    }

    private fun isAllowed(uri: Uri): Boolean =
        uri.scheme.equals("https", true) && uri.host?.lowercase() in allowedHosts

    private fun showError(message: String) {
        splashPanel.visibility = View.GONE
        errorPanel.visibility = View.VISIBLE
        (errorPanel.getChildAt(0) as? TextView)?.text = message
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val state = Bundle()
        webView.saveState(state)
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(state)
            if (parcel.dataSize() <= 128 * 1024) outState.putBundle("web_view_state", state)
        } finally {
            parcel.recycle()
        }
        webView.url?.takeIf { it.length <= 8192 }?.let { outState.putString("web_view_url", it) }
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android, kept for compatibility with older SDKs")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        playbackHandler.removeCallbacks(checkPlayback)
        stopService(playbackServiceIntent())
        playbackService?.onCommand = null
        playbackService?.onSeek = null
        if (playbackServiceBound) unbindService(playbackConnection)
        unregisterReceiver(screenStateReceiver)
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading(); webView.loadUrl("about:blank"); webView.clearHistory()
            webView.removeAllViews(); webView.destroy()
        }
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

internal data class PlaybackTimeline(
    val positionMs: Long = PlaybackState.PLAYBACK_POSITION_UNKNOWN,
    val durationMs: Long = 0,
    val rate: Float = 0f,
    val seekable: Boolean = false
)

class BackgroundPlaybackService : Service() {
    inner class LocalBinder : Binder() {
        val service get() = this@BackgroundPlaybackService
    }

    private lateinit var session: MediaSession
    private lateinit var openPlayer: PendingIntent
    private lateinit var notifications: NotificationManager
    private var playing = false
    private var nextAvailable = false
    private var title = ""
    private var artist = ""
    private var timeline = PlaybackTimeline()
    private val artworkExecutor = Executors.newSingleThreadExecutor()
    private val artworkHandler = Handler(Looper.getMainLooper())
    private var artworkRequest: Future<*>? = null
    private var artworkUrl = ""
    private var artwork: Bitmap? = null
    var onCommand: ((String) -> Unit)? = null
    var onSeek: ((Long) -> Unit)? = null
    var hasNotification = false
        private set

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Music Mini playback", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        openPlayer = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession(this, "MusicMini").apply {
            setSessionActivity(openPlayer)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { onCommand?.invoke("play") }
                override fun onPause() { onCommand?.invoke("pause") }
                override fun onSkipToNext() { onCommand?.invoke("next") }
                override fun onSeekTo(pos: Long) { onSeek?.invoke(pos) }
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "play" || action == "pause" || action == "next") {
            onCommand?.invoke(action)
            return START_NOT_STICKY
        }
        hasNotification = true
        session.isActive = true
        publishState()
        publishMetadata()
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    internal fun updatePlayback(
        playing: Boolean, nextAvailable: Boolean, title: String, artist: String, artworkUrl: String,
        timeline: PlaybackTimeline
    ) {
        val cardChanged = this.playing != playing || this.nextAvailable != nextAvailable ||
                this.title != title || this.artist != artist || this.artworkUrl != artworkUrl ||
                this.timeline.durationMs != timeline.durationMs
        val stateChanged = cardChanged || this.timeline != timeline
        this.playing = playing
        this.nextAvailable = nextAvailable
        this.title = title
        this.artist = artist
        this.timeline = timeline
        if (this.artworkUrl != artworkUrl) updateArtwork(artworkUrl)
        if (hasNotification && stateChanged) publishState()
        if (cardChanged) refreshMediaCard()
    }

    private fun refreshMediaCard() {
        if (hasNotification) {
            publishMetadata()
            notifications.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun updateArtwork(url: String) {
        artworkRequest?.cancel(true)
        artworkUrl = url
        artwork = null
        if (url.length !in 1..4096) return
        artworkRequest = artworkExecutor.submit {
            val bitmap = runCatching { downloadArtwork(url) }.getOrNull() ?: return@submit
            artworkHandler.post {
                if (artworkUrl == url && !artworkExecutor.isShutdown) {
                    artwork = bitmap
                    refreshMediaCard()
                }
            }
        }
    }

    private fun downloadArtwork(address: String): Bitmap? {
        val url = URL(address)
        if (url.protocol != "https" || url.host !in ARTWORK_HOSTS ||
            url.userInfo != null || (url.port != -1 && url.port != 443)) return null
        val connection = url.openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            if (connection.responseCode != 200 ||
                connection.contentType?.startsWith("image/") != true) return null
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (!Thread.currentThread().isInterrupted) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (output.size() + count > 2 * 1024 * 1024) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            val longestEdge = maxOf(options.outWidth, options.outHeight)
            if (longestEdge <= 0) return null
            var sample = 1
            while (longestEdge / sample > 256) sample *= 2
            options.inJustDecodeBounds = false
            options.inSampleSize = sample
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } finally {
            connection.disconnect()
        }
    }

    private fun publishState() {
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
        if (nextAvailable) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (timeline.seekable) actions = actions or PlaybackState.ACTION_SEEK_TO
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    timeline.positionMs, timeline.rate, SystemClock.elapsedRealtime())
                .build()
        )
    }

    private fun publishMetadata() {
        val metadata = MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, trackTitle())
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
        if (timeline.durationMs > 0) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, timeline.durationMs)
        artwork?.let { metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(metadata.build())
    }

    private fun trackTitle() = title.ifBlank { getString(R.string.app_name) }

    private fun buildNotification(): Notification {
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val style = Notification.MediaStyle().setMediaSession(session.sessionToken)
        artwork?.let { notification.setLargeIcon(it) }
        notification.addAction(if (playing) mediaAction(android.R.drawable.ic_media_pause, "Pause", "pause")
        else mediaAction(android.R.drawable.ic_media_play, "Play", "play"))
        if (nextAvailable) {
            notification.addAction(mediaAction(android.R.drawable.ic_media_next, "Next", "next"))
            style.setShowActionsInCompactView(0, 1)
        } else {
            style.setShowActionsInCompactView(0)
        }
        return notification.setSmallIcon(R.drawable.ic_music_play)
            .setContentTitle(trackTitle())
            .setContentText(artist.ifBlank { getString(R.string.app_name) })
            .setContentIntent(openPlayer)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setStyle(style)
            .build()
    }

    private fun mediaAction(icon: Int, title: String, action: String): Notification.Action {
        val intent = Intent(this, BackgroundPlaybackService::class.java).setAction(action)
        return Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), title,
            PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)).build()
    }

    fun leaveForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_DETACH)
        else @Suppress("DEPRECATION") stopForeground(false)
    }

    fun clearNotification() {
        if (!hasNotification) return
        hasNotification = false
        session.isActive = false
        notifications.cancel(NOTIFICATION_ID)
    }

    override fun onDestroy() {
        clearNotification()
        artworkRequest?.cancel(true)
        artworkExecutor.shutdownNow()
        artworkHandler.removeCallbacksAndMessages(null)
        session.release()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL_ID = "music_mini_playback"
        const val NOTIFICATION_ID = 1
        val ARTWORK_HOSTS = setOf(
            "yt3.googleusercontent.com", "yt3.ggpht.com", "lh3.googleusercontent.com",
            "i.ytimg.com", "i9.ytimg.com", "img.youtube.com"
        )
    }
}
