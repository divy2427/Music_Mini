package com.example.youtubemusicminibrowser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
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

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var errorPanel: LinearLayout
    private lateinit var splashPanel: LinearLayout
    private var hasPageContent = false

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
        if (savedInstanceState != null) webView.restoreState(savedInstanceState) else webView.loadUrl(homeUrl)
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
        webView = WebView(this)
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
                  '.ytp-skip-ad-button:not([disabled])',
                  '.ytp-skip-ad-button-modern:not([disabled])',
                  '.ytp-skip-ad-button-container button:not([disabled])',
                  '.ytp-ad-skip-button:not([disabled])',
                  '.ytp-ad-skip-button-modern:not([disabled])',
                  '.ytp-ad-skip-button-container button:not([disabled])',
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
                  const cap = Math.max(1, Math.min(256, Number(settings.maxSpeed) || 16));
                  const desired = remaining === null ? Math.min(cap, 16) : Math.max(1, Math.min(cap, remaining / target));
                  if (Math.abs((media.playbackRate || 1) - desired) < 0.05) return;
                  try { media.playbackRate = desired; } catch (_) {}
                }
                function mute(media) {
                  if (!settings.autoMute) return;
                  remember(media);
                  try { media.muted = true; } catch (_) {}
                }
                function skip() {
                  if (!settings.autoSkip || Date.now() - lastSkip < 300) return;
                  for (const selector of skipSelectors) {
                    for (const button of document.querySelectorAll(selector)) {
                      if (!visible(button) || button.disabled || button.getAttribute('aria-disabled') === 'true') continue;
                      lastSkip = Date.now();
                      try { button.click(); } catch (_) {}
                      return;
                    }
                  }
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
                  update: function(next) {
                    settings = Object.assign({}, defaults, settings, next);
                    if (!settings.enabled) { restore(); adWasActive = false; }
                    schedule();
                  }
                };
                window[key] = controller;
                observer = new MutationObserver(schedule);
                observer.observe(document.documentElement, { childList:true, subtree:true, attributes:true,
                  attributeFilter:['class','aria-label','title','disabled','style'] });
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
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android, kept for compatibility with older SDKs")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading(); webView.loadUrl("about:blank"); webView.clearHistory()
            webView.removeAllViews(); webView.destroy()
        }
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
