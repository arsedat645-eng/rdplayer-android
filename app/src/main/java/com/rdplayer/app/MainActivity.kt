package com.rdplayer.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.rdplayer.app.data.AppPreferences
import com.rdplayer.app.ui.screens.MainScreen
import com.rdplayer.app.ui.screens.SetupScreen
import com.rdplayer.app.ui.screens.SettingsScreen
import com.rdplayer.app.ui.theme.DarkBackground
import com.rdplayer.app.ui.theme.RDPlayerTheme

class MainActivity : ComponentActivity() {

    private lateinit var preferences: AppPreferences

    private var pendingPath = mutableStateOf<String?>(null)
    private var pendingPathRequestId = mutableLongStateOf(0L)
    private var screenState = mutableStateOf(Screen.SETUP)
    private var nextPendingPathRequestId = 1L

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        preferences = AppPreferences(this)

        enableEdgeToEdge()

        // Keep splash while checking config
        var isReady = false
        splashScreen.setKeepOnScreenCondition { !isReady }

        // Determine initial state
        screenState.value = if (preferences.isConfigured()) Screen.MAIN else Screen.SETUP
        parseIntent(intent)?.let { path ->
            pendingPath.value = path
            pendingPathRequestId.longValue = nextPendingPathRequestId++
        }

        setContent {
            RDPlayerTheme {
                var currentScreen by screenState
                val navigatePath by pendingPath
                val navigatePathRequestId by pendingPathRequestId

                // Mark ready after first composition
                LaunchedEffect(Unit) {
                    isReady = true
                }

                // Keep screen on setting
                LaunchedEffect(currentScreen) {
                    if (currentScreen == Screen.MAIN && preferences.keepScreenOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                AnimatedContent(
                    targetState = currentScreen,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(300)) togetherWith
                                fadeOut(animationSpec = tween(200))
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DarkBackground),
                    label = "screen_transition"
                ) { screen ->
                    when (screen) {
                        Screen.SETUP -> {
                            SetupScreen(
                                onSetupComplete = { url ->
                                    preferences.serverUrl = url
                                    preferences.setupCompleted = true
                                    currentScreen = Screen.MAIN
                                }
                            )
                        }
                        Screen.MAIN -> {
                            MainScreen(
                                serverUrl = preferences.serverUrl,
                                navigationPath = navigatePath,
                                navigationRequestId = navigatePathRequestId,
                                preferences = preferences,
                                onNavigateToSettings = {
                                    currentScreen = Screen.SETTINGS
                                },
                                onNavigationHandled = { handledRequestId ->
                                    if (pendingPathRequestId.longValue == handledRequestId) {
                                        pendingPath.value = null
                                        pendingPathRequestId.longValue = 0L
                                    }
                                }
                            )
                        }
                        Screen.SETTINGS -> {
                            SettingsScreen(
                                preferences = preferences,
                                onBack = {
                                    currentScreen = Screen.MAIN
                                },
                                onResetApp = {
                                    currentScreen = Screen.SETUP
                                },
                                onUrlChanged = { /* will apply on next MainScreen load */ }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val path = parseIntent(intent)
        if (path != null) {
            pendingPath.value = path
            pendingPathRequestId.longValue = nextPendingPathRequestId++

            if (!preferences.isConfigured()) {
                Toast.makeText(this, getString(R.string.share_no_server), Toast.LENGTH_LONG).show()
                screenState.value = Screen.SETUP
            } else {
                screenState.value = Screen.MAIN
            }
        }
    }

    private fun parseIntent(intent: Intent?): String? {
        if (intent == null) return null

        // Handle share intent
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                return "/add?q=${Uri.encode(sharedText)}"
            }
        }

        // Handle shortcut / deep link intents
        val data = intent.data
        if (data != null && data.scheme == "rdplayer") {
            val path = data.getQueryParameter("path")
            if (!path.isNullOrBlank()) {
                return path
            }
        }

        return null
    }

    private enum class Screen {
        SETUP, MAIN, SETTINGS
    }
}
