package com.rdplayer.app.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rdplayer.app.R
import com.rdplayer.app.data.AppPreferences
import com.rdplayer.app.data.PlayerMode
import com.rdplayer.app.ui.theme.*
import com.rdplayer.app.util.ServerUrlUtils
import com.rdplayer.app.webview.DownloadHandler
import com.rdplayer.app.webview.RDWebChromeClient
import com.rdplayer.app.webview.RDWebViewClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * JS injected after page load to catch dynamically created video elements
 * and send their source to the Android bridge.
 */
private const val VIDEO_INTERCEPT_JS = """
(function() {
    if (window._rdPlayerInjected) return;
    window._rdPlayerInjected = true;

    function sendVideoUrl(url) {
        if (!url || url === '' || url.startsWith('blob:')) return;
        try { RDPlayerBridge.onVideoDetected(url); } catch(e) {}
    }

    function checkVideo(el) {
        if (el.tagName === 'VIDEO') {
            if (el.currentSrc && !el.currentSrc.startsWith('blob:')) { sendVideoUrl(el.currentSrc); return; }
            if (el.src && !el.src.startsWith('blob:')) { sendVideoUrl(el.src); return; }
            var sources = el.querySelectorAll('source');
            for (var i = 0; i < sources.length; i++) {
                if (sources[i].src && !sources[i].src.startsWith('blob:')) { sendVideoUrl(sources[i].src); return; }
            }
            el.addEventListener('loadstart', function() {
                var s = el.currentSrc || el.src;
                if (s && !s.startsWith('blob:')) sendVideoUrl(s);
            }, {once: true});
            el.addEventListener('play', function() {
                var s = el.currentSrc || el.src;
                if (s && !s.startsWith('blob:')) sendVideoUrl(s);
            }, {once: true});
        }
    }

    document.querySelectorAll('video').forEach(checkVideo);

    var observer = new MutationObserver(function(mutations) {
        mutations.forEach(function(m) {
            m.addedNodes.forEach(function(node) {
                if (node.nodeType !== 1) return;
                if (node.tagName === 'VIDEO') checkVideo(node);
                var vids = node.querySelectorAll ? node.querySelectorAll('video') : [];
                vids.forEach(checkVideo);
            });
        });
    });
    observer.observe(document.body || document.documentElement, {childList: true, subtree: true});
})();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MainScreen(
    serverUrl: String,
    navigationPath: String? = null,
    navigationRequestId: Long = 0L,
    preferences: AppPreferences,
    onNavigateToSettings: () -> Unit,
    onNavigationHandled: (Long) -> Unit
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var pendingPlayerChoiceUrl by remember { mutableStateOf<String?>(null) }
    var lastDispatchedMediaUrl by remember { mutableStateOf<String?>(null) }
    var lastDispatchedMediaAtMs by remember { mutableLongStateOf(0L) }

    var backPressedOnce by remember { mutableStateOf(false) }
    var lastHandledNavigationRequestId by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else if (!backPressedOnce) {
            backPressedOnce = true
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.press_again_to_exit))
                delay(2000)
                backPressedOnce = false
            }
        } else {
            (context as? android.app.Activity)?.finish()
        }
    }

    fun normalizeMediaUrl(url: String): String {
        var cleanUrl = url.trim()
        if (cleanUrl.startsWith("intent://")) {
            try {
                val parsedIntent = Intent.parseUri(cleanUrl, Intent.URI_INTENT_SCHEME)
                cleanUrl = parsedIntent.dataString ?: cleanUrl
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return cleanUrl
    }

    fun rememberMediaDispatch(url: String) {
        lastDispatchedMediaUrl = url
        lastDispatchedMediaAtMs = SystemClock.elapsedRealtime()
    }

    fun shouldIgnoreMediaRequest(url: String): Boolean {
        if (pendingPlayerChoiceUrl == url) {
            return true
        }
        return lastDispatchedMediaUrl == url &&
            SystemClock.elapsedRealtime() - lastDispatchedMediaAtMs < 1500L
    }

    fun guessExternalMimeType(url: String): String? {
        val lowerUrl = url.lowercase()
        return when {
            lowerUrl.endsWith(".m3u8") -> "application/x-mpegURL"
            lowerUrl.endsWith(".mpd") -> "application/dash+xml"
            lowerUrl.endsWith(".mp3") ||
                lowerUrl.endsWith(".flac") ||
                lowerUrl.endsWith(".aac") ||
                lowerUrl.endsWith(".ogg") ||
                lowerUrl.endsWith(".wav") ||
                lowerUrl.endsWith(".m4a") -> "audio/*"
            else -> "video/*"
        }
    }

    fun openInInternalPlayer(url: String) {
        try {
            val intent = Intent(context, PlayerActivity::class.java)
            intent.putExtra("EXTRA_VIDEO_URL", url)
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Failed to start internal player: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun openInExternalPlayer(url: String) {
        val uri = Uri.parse(url)
        val mimeType = guessExternalMimeType(url)
        Toast.makeText(context, "Opening in external player...", Toast.LENGTH_SHORT).show()

        // Prefer known media players first so Android does not hand the URL to a browser/downloader.
        for (pkg in listOf(
            "is.xyz.mpv",
            "io.mpv.mpv",
            "org.videolan.vlc",
            "com.mxtech.videoplayer.ad",
            "com.mxtech.videoplayer.pro"
        )) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setPackage(pkg)
                    if (mimeType != null) {
                        setDataAndType(uri, mimeType)
                    } else {
                        data = uri
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }

        // Generic media intent with MIME type as a fallback.
        if (mimeType != null) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }

        // Last resort: raw URL view.
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (_: Exception) {
        }

        Toast.makeText(context, "No video player found for: $url", Toast.LENGTH_LONG).show()
    }

    fun requestMediaPlayback(url: String) {
        val cleanUrl = normalizeMediaUrl(url)
        if (cleanUrl.isBlank() || shouldIgnoreMediaRequest(cleanUrl)) {
            return
        }

        when (preferences.playerMode) {
            PlayerMode.INTERNAL -> {
                rememberMediaDispatch(cleanUrl)
                openInInternalPlayer(cleanUrl)
            }
            PlayerMode.EXTERNAL -> {
                rememberMediaDispatch(cleanUrl)
                openInExternalPlayer(cleanUrl)
            }
            PlayerMode.ASK_EVERY_TIME -> {
                pendingPlayerChoiceUrl = cleanUrl
            }
        }
    }

    // Error overlay
    if (hasError) {
        ErrorScreen(
            serverUrl = serverUrl,
            errorMessage = errorMessage,
            onRetry = {
                hasError = false
                errorMessage = ""
                webView?.reload()
            },
            onOpenSettings = onNavigateToSettings
        )
        return
    }

    pendingPlayerChoiceUrl?.let { mediaUrl ->
        AlertDialog(
            onDismissRequest = { pendingPlayerChoiceUrl = null },
            title = {
                Text(
                    text = stringResource(R.string.player_choice_title),
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.player_choice_message),
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingPlayerChoiceUrl = null
                        rememberMediaDispatch(mediaUrl)
                        openInInternalPlayer(mediaUrl)
                    }
                ) {
                    Text(stringResource(R.string.player_choice_internal))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { pendingPlayerChoiceUrl = null }) {
                        Text(stringResource(R.string.player_choice_cancel))
                    }
                    TextButton(
                        onClick = {
                            pendingPlayerChoiceUrl = null
                            rememberMediaDispatch(mediaUrl)
                            openInExternalPlayer(mediaUrl)
                        }
                    ) {
                        Text(stringResource(R.string.player_choice_external))
                    }
                }
            },
            containerColor = DarkCard
        )
    }

    Scaffold(
        containerColor = DarkBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setBackgroundColor(0xFF0d0d0f.toInt())

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            cacheMode = WebSettings.LOAD_DEFAULT
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            mediaPlaybackRequiresUserGesture = false
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            allowFileAccess = false
                            allowContentAccess = false
                            userAgentString = settings.userAgentString + " RDPlayerApp/${com.rdplayer.app.BuildConfig.VERSION_NAME}"
                        }

                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        // JS bridge: receives video URLs from injected script
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onVideoDetected(videoUrl: String) {
                                post { requestMediaPlayback(videoUrl) }
                            }
                        }, "RDPlayerBridge")

                        webViewClient = RDWebViewClient(
                            serverUrl = serverUrl,
                            onPageStarted = { isLoading = true },
                            onPageFinished = {
                                isLoading = false
                                CookieManager.getInstance().flush()
                                // Inject JS to catch video elements
                                evaluateJavascript(VIDEO_INTERCEPT_JS, null)
                            },
                            onError = { msg ->
                                hasError = true
                                errorMessage = msg
                            },
                            onMediaUrl = { mediaUrl ->
                                requestMediaPlayback(mediaUrl)
                            }
                        )

                        webChromeClient = RDWebChromeClient(
                            onProgressChanged = { progress ->
                                loadProgress = progress
                                if (progress >= 100) isLoading = false
                            }
                        )

                        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                            // If server sends a video/audio stream → open in external player
                            if (mimeType?.startsWith("video/") == true ||
                                mimeType?.startsWith("audio/") == true ||
                                mimeType == "application/x-mpegURL" ||
                                mimeType == "application/vnd.apple.mpegurl" ||
                                mimeType == "application/octet-stream") {
                                requestMediaPlayback(url)
                            } else {
                                DownloadHandler.handleDownload(
                                    context = ctx,
                                    url = url,
                                    contentDisposition = contentDisposition,
                                    mimeType = mimeType,
                                    userAgent = userAgent
                                )
                            }
                        }

                        loadUrl(ServerUrlUtils.buildTargetUrl(serverUrl, navigationPath))

                        webView = this
                    }
                },
                update = { view ->
                    webView = view

                    if (navigationPath != null &&
                        navigationRequestId != 0L &&
                        navigationRequestId != lastHandledNavigationRequestId
                    ) {
                        val targetUrl = ServerUrlUtils.buildTargetUrl(serverUrl, navigationPath)
                        if (view.url != targetUrl) {
                            hasError = false
                            errorMessage = ""
                            view.loadUrl(targetUrl)
                        }
                        lastHandledNavigationRequestId = navigationRequestId
                        onNavigationHandled(navigationRequestId)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Full-screen native Splash transition to mask webview parsing flashes
            AnimatedVisibility(
                visible = isLoading || loadProgress < 90,
                enter = fadeIn(),
                exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(400)),
                modifier = Modifier.fillMaxSize()
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "splash")
                val scale by infiniteTransition.animateFloat(
                    initialValue = 0.85f,
                    targetValue = 1.15f,
                    animationSpec = infiniteRepeatable(
                        animation = androidx.compose.animation.core.tween(1200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ), label = "splash_scale"
                )
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = androidx.compose.animation.core.tween(1200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ), label = "splash_alpha"
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DarkBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(140.dp)
                                .scale(scale * 1.3f)
                                .background(
                                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                        colors = listOf(AccentRed.copy(alpha = 0.3f * alpha), androidx.compose.ui.graphics.Color.Transparent)
                                    ),
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                        )
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Loading...",
                            modifier = Modifier
                                .size(72.dp)
                                .scale(scale),
                            tint = AccentRed.copy(alpha = alpha)
                        )
                    }
                }
            }

            // FAB menu
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                FloatingActionButton(
                    onClick = { showMenu = !showMenu },
                    containerColor = DarkCard.copy(alpha = 0.92f),
                    contentColor = TextPrimary,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(Icons.Rounded.MoreVert, "Menu", Modifier.size(20.dp))
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(DarkCard)
                ) {
                    DropdownMenuItem(
                        text = { Text("Refresh", color = TextPrimary) },
                        onClick = { showMenu = false; webView?.reload() },
                        leadingIcon = { Icon(Icons.Rounded.Refresh, null, tint = TextSecondary) }
                    )
                    DropdownMenuItem(
                        text = { Text("Open in Player", color = TextPrimary) },
                        onClick = {
                            showMenu = false
                            requestMediaPlayback(webView?.url ?: serverUrl)
                        },
                        leadingIcon = { Icon(Icons.Rounded.PlayArrow, null, tint = AccentRed) }
                    )
                    DropdownMenuItem(
                        text = { Text("Open in Browser", color = TextPrimary) },
                        onClick = {
                            showMenu = false
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webView?.url ?: serverUrl)))
                        },
                        leadingIcon = { Icon(Icons.Rounded.OpenInNew, null, tint = TextSecondary) }
                    )
                    DropdownMenuItem(
                        text = { Text("Settings", color = TextPrimary) },
                        onClick = { showMenu = false; onNavigateToSettings() },
                        leadingIcon = { Icon(Icons.Rounded.Settings, null, tint = TextSecondary) }
                    )
                }
            }
        }
    }
}
