package com.rdplayer.app.ui.screens

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.rdplayer.app.R
import com.rdplayer.app.data.AppPreferences
import com.rdplayer.app.ui.theme.RDPlayerTheme
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale

private const val RESUME_PROMPT_THRESHOLD_MS = 10_000L
private const val PLAYBACK_COMPLETED_THRESHOLD_MS = 5_000L
private const val UNRESOLVED_START_POSITION_MS = -1L

class PlayerActivity : ComponentActivity() {

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = AppPreferences(this)

        // Force landscape and immersive full screen
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        if (preferences.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val videoUrl = intent.getStringExtra("EXTRA_VIDEO_URL") ?: ""

        setContent {
            RDPlayerTheme {
                PlayerScreen(
                    videoUrl = videoUrl,
                    onClose = { finish() }
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(videoUrl: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val prefs = remember { AppPreferences(context) }
    val playbackKeys = remember(videoUrl) { buildPlaybackPositionKeys(videoUrl) }
    val savedPlaybackPosition = remember(videoUrl) { prefs.getPlaybackPosition(playbackKeys) }
    val shouldPromptForResume = savedPlaybackPosition >= RESUME_PROMPT_THRESHOLD_MS
    val audioManager = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager }

    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var isScreenLocked by remember { mutableStateOf(false) }
    var lockIconVisible by remember { mutableStateOf(false) }
    var lockIconHideTrigger by remember { mutableLongStateOf(0L) }
    var isControllerVisible by remember { mutableStateOf(false) }
    var leftRippleTrigger by remember { mutableLongStateOf(0L) }
    var rightRippleTrigger by remember { mutableLongStateOf(0L) }
    var showResumeDialog by remember(videoUrl) { mutableStateOf(shouldPromptForResume) }
    var canPersistPlaybackPosition by remember(videoUrl) { mutableStateOf(!shouldPromptForResume) }
    var targetPlaybackPositionMs by remember(videoUrl) {
        mutableLongStateOf(
            if (shouldPromptForResume) {
                UNRESOLVED_START_POSITION_MS
            } else {
                savedPlaybackPosition
            }
        )
    }
    var shouldAutoPlay by remember(videoUrl) { mutableStateOf(!shouldPromptForResume) }
    val currentCanPersistPlaybackPosition = rememberUpdatedState(canPersistPlaybackPosition)

    // Floating Indicator States
    var indicatorIcon by remember { mutableStateOf<androidx.compose.ui.graphics.vector.ImageVector?>(null) }
    var indicatorText by remember { mutableStateOf("") }
    var indicatorSubText by remember { mutableStateOf("") }
    var showIndicator by remember { mutableLongStateOf(0L) }


    DisposableEffect(context, videoUrl) {
        val userAgent = WebSettings.getDefaultUserAgent(context)
        val cookies = CookieManager.getInstance().getCookie(videoUrl) ?: ""

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(mapOf("Cookie" to cookies))

        val player = ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                playWhenReady = false
                val mediaItem = MediaItem.fromUri(videoUrl)
                setMediaItem(mediaItem)

                addListener(object : androidx.media3.common.Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (!isPlaying && currentCanPersistPlaybackPosition.value) {
                            persistPlaybackPosition(this@apply, prefs, playbackKeys)
                        }
                    }
                })

                prepare()
            }
        
        exoPlayer = player

        onDispose {
            if (currentCanPersistPlaybackPosition.value) {
                persistPlaybackPosition(player, prefs, playbackKeys)
            }
            player.release()
        }
    }

    androidx.compose.runtime.LaunchedEffect(exoPlayer, targetPlaybackPositionMs, shouldAutoPlay) {
        val player = exoPlayer ?: return@LaunchedEffect
        if (targetPlaybackPositionMs >= 0L) {
            player.seekTo(targetPlaybackPositionMs)
        }
        player.playWhenReady = shouldAutoPlay
    }

    // Auto-hide lock icon after a few seconds
    androidx.compose.runtime.LaunchedEffect(lockIconHideTrigger) {
        if (lockIconHideTrigger > 0L && isScreenLocked) {
            lockIconVisible = true
            kotlinx.coroutines.delay(3000)
            lockIconVisible = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                var swipeVolumeAccumulator = 0f
                var swipeSeekStartPositionMs = -1L
                var swipeSeekOffsetMs = 0L

                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    setControllerShowTimeoutMs(1800)
                    setControllerHideOnTouch(true)
                    setControllerAutoShow(false)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowFastForwardButton(true)
                    setShowRewindButton(true)
                    setBackgroundColor(android.graphics.Color.BLACK)

                    setControllerVisibilityListener(androidx.media3.ui.PlayerView.ControllerVisibilityListener { visibility ->
                        isControllerVisible = (visibility == android.view.View.VISIBLE)
                    })

                    val gestureDetector = android.view.GestureDetector(ctx, object : android.view.GestureDetector.SimpleOnGestureListener() {
                        private var isBrightnessScroll = false
                        private var isVolumeScroll = false
                        private var isSeekScroll = false
                        private var didInitiateScroll = false

                        override fun onDown(e: android.view.MotionEvent): Boolean {
                            didInitiateScroll = false
                            isBrightnessScroll = false
                            isVolumeScroll = false
                            isSeekScroll = false
                            swipeVolumeAccumulator = 0f
                            swipeSeekOffsetMs = 0L
                            swipeSeekStartPositionMs = exoPlayer?.currentPosition ?: 0L
                            return true
                        }

                        override fun onScroll(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                            if (isScreenLocked) return true
                            if (e1 == null) return false
                            if (!didInitiateScroll) {
                                if (kotlin.math.abs(distanceY) > kotlin.math.abs(distanceX)) {
                                    didInitiateScroll = true
                                    if (e1.x < width / 2) isBrightnessScroll = true else isVolumeScroll = true
                                } else if (kotlin.math.abs(distanceX) > kotlin.math.abs(distanceY)) {
                                    didInitiateScroll = true
                                    isSeekScroll = true
                                } else {
                                    didInitiateScroll = true
                                }
                            }

                            if (isBrightnessScroll) {
                                activity?.window?.let { window ->
                                    var brightness = window.attributes.screenBrightness
                                    if (brightness < 0) brightness = 0.5f
                                    brightness += distanceY * 0.002f
                                    brightness = brightness.coerceIn(0.01f, 1f)
                                    val params = window.attributes
                                    params.screenBrightness = brightness
                                    window.attributes = params

                                    indicatorIcon = Icons.Rounded.LightMode
                                    indicatorText = "${(brightness * 100).toInt()}%"
                                    showIndicator = System.currentTimeMillis()
                                }
                                return true
                            } else if (isVolumeScroll) {
                                val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                                swipeVolumeAccumulator += distanceY
                                if (kotlin.math.abs(swipeVolumeAccumulator) > 30) {
                                    val step = if (swipeVolumeAccumulator > 0) 1 else -1
                                    val current = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                                    val next = (current + step).coerceIn(0, maxVolume)
                                    audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, next, 0)
                                    swipeVolumeAccumulator = 0f

                                    indicatorIcon = Icons.Rounded.VolumeUp
                                    indicatorText = "${(next * 100) / maxVolume}%"
                                    showIndicator = System.currentTimeMillis()
                                }
                                return true
                            } else if (isSeekScroll) {
                                val p = exoPlayer ?: return false
                                val duration = p.duration
                                if (duration <= 0) return false

                                // Scale: full screen width swipe = duration/10 (max 120s)
                                val maxSeekPerSwipe = kotlin.math.min(duration / 10L, 120_000L)
                                val totalDx = e2.x - (e1?.x ?: e2.x)
                                swipeSeekOffsetMs = (totalDx / width * maxSeekPerSwipe).toLong()

                                val targetPos = (swipeSeekStartPositionMs + swipeSeekOffsetMs).coerceIn(0L, duration)
                                val offsetSec = (targetPos - swipeSeekStartPositionMs) / 1000L
                                val sign = if (offsetSec >= 0) "+" else ""

                                indicatorIcon = if (offsetSec >= 0) Icons.Rounded.FastForward else Icons.Rounded.FastRewind
                                indicatorText = "${sign}${offsetSec}s"
                                indicatorSubText = formatPlaybackPosition(targetPos)
                                showIndicator = System.currentTimeMillis()

                                if (isControllerFullyVisible) hideController()
                                return true
                            }
                            return false
                        }

                        override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                            if (isScreenLocked) {
                                // When locked, tapping toggles lock icon visibility
                                lockIconVisible = !lockIconVisible
                                if (lockIconVisible) {
                                    lockIconHideTrigger = System.currentTimeMillis()
                                }
                                return true
                            }
                            if (isControllerFullyVisible) hideController() else showController()
                            return true
                        }

                        override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                            if (isScreenLocked) return true
                            val p = exoPlayer ?: return false
                            if (isControllerFullyVisible) hideController()
                            if (e.x < width / 2) {
                                p.seekBack()
                                leftRippleTrigger = System.currentTimeMillis()
                            } else {
                                p.seekForward()
                                rightRippleTrigger = System.currentTimeMillis()
                            }
                            return true
                        }
                    })

                    setOnTouchListener { _, event ->
                        val handled = gestureDetector.onTouchEvent(event)
                        // Apply seek on finger lift
                        if (event.action == android.view.MotionEvent.ACTION_UP ||
                            event.action == android.view.MotionEvent.ACTION_CANCEL) {
                            if (swipeSeekOffsetMs != 0L && swipeSeekStartPositionMs >= 0L) {
                                val p = exoPlayer
                                if (p != null && p.duration > 0) {
                                    val targetPos = (swipeSeekStartPositionMs + swipeSeekOffsetMs).coerceIn(0L, p.duration)
                                    p.seekTo(targetPos)
                                }
                                swipeSeekOffsetMs = 0L
                                swipeSeekStartPositionMs = -1L
                                indicatorSubText = ""
                            }
                        }
                        handled
                    }
                }
            },
            update = { view ->
                view.player = exoPlayer
                // When locked, hide the controller and disable it
                if (isScreenLocked) {
                    view.hideController()
                    view.useController = false
                } else {
                    view.useController = true
                }
            },
            modifier = Modifier.fillMaxSize().background(Color.Black)
        )

        // Left Ripple Animation (only when not locked)
        if (!isScreenLocked) {
            RippleOverlay(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(0.4f),
                trigger = leftRippleTrigger,
                isRight = false
            )

            // Right Ripple Animation
            RippleOverlay(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(0.4f),
                trigger = rightRippleTrigger,
                isRight = true
            )

            // Float Indicator Overlay (Volume / Brightness / Seek)
            IndicatorOverlay(
                modifier = Modifier.align(Alignment.Center),
                trigger = showIndicator,
                icon = indicatorIcon,
                text = indicatorText,
                subText = indicatorSubText
            )
        }

        // Lock button overlay (shown when controller is visible and NOT locked)
        if (!isScreenLocked && isControllerVisible) {
            LockButtonOverlay(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 16.dp),
                onLock = {
                    isScreenLocked = true
                    lockIconVisible = true
                    lockIconHideTrigger = System.currentTimeMillis()
                }
            )
        }

        // Locked state: show lock icon overlay for unlocking
        if (isScreenLocked) {
            LockedScreenOverlay(
                modifier = Modifier.align(Alignment.Center),
                isVisible = lockIconVisible,
                onUnlock = {
                    isScreenLocked = false
                    lockIconVisible = false
                }
            )
        }
    }



    if (showResumeDialog) {
        AlertDialog(
            onDismissRequest = {
                canPersistPlaybackPosition = false
                shouldAutoPlay = false
                onClose()
            },
            title = {
                Text(
                    text = stringResource(R.string.player_resume_title),
                    color = Color.White
                )
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.player_resume_message,
                        formatPlaybackPosition(savedPlaybackPosition)
                    ),
                    color = Color.White.copy(alpha = 0.75f)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        canPersistPlaybackPosition = true
                        targetPlaybackPositionMs = savedPlaybackPosition
                        shouldAutoPlay = true
                        showResumeDialog = false
                    }
                ) {
                    Text(
                        text = stringResource(
                            R.string.player_resume_continue,
                            formatPlaybackPosition(savedPlaybackPosition)
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        canPersistPlaybackPosition = true
                        targetPlaybackPositionMs = 0L
                        shouldAutoPlay = true
                        showResumeDialog = false
                    }
                ) {
                    Text(text = stringResource(R.string.player_resume_start_over))
                }
            },
            containerColor = Color(0xFF121216)
        )
    }
}

private fun persistPlaybackPosition(
    player: ExoPlayer,
    prefs: AppPreferences,
    playbackKeys: Collection<String>
) {
    if (player.duration > 0 && player.currentPosition >= player.duration - PLAYBACK_COMPLETED_THRESHOLD_MS) {
        prefs.savePlaybackPositions(playbackKeys, 0L)
    } else {
        prefs.savePlaybackPositions(playbackKeys, player.currentPosition)
    }
}

private fun formatPlaybackPosition(positionMs: Long): String {
    val totalSeconds = (positionMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L

    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun buildPlaybackPositionKeys(videoUrl: String): List<String> {
    val uri = android.net.Uri.parse(videoUrl)
    val keys = linkedSetOf<String>()

    val fullUrl = uri.toString()
    keys += stablePlaybackId("full:$fullUrl")

    val canonicalUrl = buildString {
        append(uri.scheme.orEmpty().lowercase(Locale.ROOT))
        append("://")
        append(uri.host.orEmpty().lowercase(Locale.ROOT))
        val port = uri.port
        if (port != -1) {
            append(":")
            append(port)
        }
        append(uri.encodedPath ?: "")

        val stableQuery = uri.queryParameterNames
            .filterNot { isTransientQueryParameter(it) }
            .sorted()
            .joinToString("&") { name ->
                val values = uri.getQueryParameters(name)
                if (values.isEmpty()) {
                    name
                } else {
                    values.sorted().joinToString("&") { value -> "$name=$value" }
                }
            }

        if (stableQuery.isNotBlank()) {
            append("?")
            append(stableQuery)
        }
    }
    if (canonicalUrl.isNotBlank()) {
        keys += stablePlaybackId("canonical:$canonicalUrl")
    }

    val pathOnly = buildString {
        append(uri.host.orEmpty().lowercase(Locale.ROOT))
        val port = uri.port
        if (port != -1) {
            append(":")
            append(port)
        }
        append(uri.encodedPath ?: "")
    }
    if (pathOnly.isNotBlank()) {
        keys += stablePlaybackId("path:$pathOnly")
    }

    uri.lastPathSegment
        ?.takeIf { it.isNotBlank() }
        ?.let { segment ->
            keys += decodeUrlComponent(segment)
        }

    return keys.toList()
}

private fun stablePlaybackId(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

private fun isTransientQueryParameter(name: String): Boolean {
    return when (name.lowercase(Locale.ROOT)) {
        "token", "auth", "signature", "sig", "expires", "exp", "ts", "timestamp", "hash" -> true
        else -> false
    }
}

private fun decodeUrlComponent(value: String): String {
    return try {
        URLDecoder.decode(value, Charsets.UTF_8.name())
    } catch (_: Exception) {
        value
    }
}

@Composable
fun IndicatorOverlay(
    modifier: Modifier,
    trigger: Long,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    text: String,
    subText: String = ""
) {
    if (trigger == 0L || icon == null) return
    val alphaAnim = remember { androidx.compose.animation.core.Animatable(0f) }

    androidx.compose.runtime.LaunchedEffect(trigger) {
        alphaAnim.snapTo(1f)
        kotlinx.coroutines.delay(1000)
        alphaAnim.animateTo(0f, animationSpec = androidx.compose.animation.core.tween(400))
    }

    if (alphaAnim.value > 0) {
        Box(
            modifier = modifier
                .background(Color.Black.copy(alpha = 0.6f * alphaAnim.value), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = alphaAnim.value),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = text,
                    color = Color.White.copy(alpha = alphaAnim.value),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                if (subText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subText,
                        color = Color.White.copy(alpha = alphaAnim.value * 0.7f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
fun RippleOverlay(modifier: Modifier, trigger: Long, isRight: Boolean) {
    if (trigger == 0L) return
    val alphaAnim = remember { androidx.compose.animation.core.Animatable(0f) }

    androidx.compose.runtime.LaunchedEffect(trigger) {
        alphaAnim.snapTo(1f)
        alphaAnim.animateTo(0f, animationSpec = androidx.compose.animation.core.tween(600, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
    }

    if (alphaAnim.value > 0) {
        Box(
            modifier = modifier
                .background(
                    brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                        colors = if (isRight) listOf(Color.Transparent, Color.White.copy(alpha = 0.2f * alphaAnim.value))
                        else listOf(Color.White.copy(alpha = 0.2f * alphaAnim.value), Color.Transparent)
                    ),
                    shape = if (isRight) androidx.compose.foundation.shape.RoundedCornerShape(topStartPercent = 100, bottomStartPercent = 100)
                    else androidx.compose.foundation.shape.RoundedCornerShape(topEndPercent = 100, bottomEndPercent = 100)
                ),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = if (isRight) androidx.compose.material.icons.Icons.Rounded.FastForward else androidx.compose.material.icons.Icons.Rounded.FastRewind,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = alphaAnim.value),
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "10 seconds",
                    color = Color.White.copy(alpha = alphaAnim.value),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun LockButtonOverlay(
    modifier: Modifier = Modifier,
    onLock: () -> Unit
) {
    IconButton(
        onClick = onLock,
        modifier = modifier
            .size(48.dp)
            .background(
                Color.Black.copy(alpha = 0.5f),
                CircleShape
            )
    ) {
        Icon(
            imageVector = Icons.Rounded.LockOpen,
            contentDescription = "Lock screen",
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun LockedScreenOverlay(
    modifier: Modifier = Modifier,
    isVisible: Boolean,
    onUnlock: () -> Unit
) {
    val alphaAnim = remember { androidx.compose.animation.core.Animatable(0f) }

    androidx.compose.runtime.LaunchedEffect(isVisible) {
        if (isVisible) {
            alphaAnim.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(300))
        } else {
            alphaAnim.animateTo(0f, animationSpec = androidx.compose.animation.core.tween(400))
        }
    }

    if (alphaAnim.value > 0f) {
        Box(
            modifier = modifier
                .alpha(alphaAnim.value)
                .background(
                    Color.Black.copy(alpha = 0.4f * alphaAnim.value),
                    androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onUnlock
                )
                .padding(horizontal = 28.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = "Unlock screen",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = "Tap to unlock",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                )
            }
        }
    }
}

