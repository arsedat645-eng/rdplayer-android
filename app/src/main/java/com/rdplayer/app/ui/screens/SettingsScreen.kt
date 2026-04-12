package com.rdplayer.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rdplayer.app.R
import com.rdplayer.app.data.AppPreferences
import com.rdplayer.app.data.PlayerMode
import com.rdplayer.app.model.ConnectionTestState
import com.rdplayer.app.ui.theme.*
import com.rdplayer.app.util.ServerUrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferences: AppPreferences,
    onBack: () -> Unit,
    onResetApp: () -> Unit,
    onUrlChanged: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var serverUrl by remember { mutableStateOf(preferences.serverUrl) }
    var testState by remember { mutableStateOf(ConnectionTestState.IDLE) }
    var testMessage by remember { mutableStateOf("") }
    var keepScreenOn by remember { mutableStateOf(preferences.keepScreenOn) }
    var playerMode by remember { mutableStateOf(preferences.playerMode) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showSnackbar by remember { mutableStateOf("") }

    val snackbarHostState = remember { SnackbarHostState() }
    val invalidUrlMessage = stringResource(R.string.setup_invalid_url)

    LaunchedEffect(showSnackbar) {
        if (showSnackbar.isNotBlank()) {
            snackbarHostState.showSnackbar(showSnackbar)
            showSnackbar = ""
        }
    }

    // Reset confirmation dialog
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = {
                Text(
                    stringResource(R.string.settings_reset_confirm_title),
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    stringResource(R.string.settings_reset_confirm_message),
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        preferences.resetApp(context)
                        onResetApp()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = StatusError)
                ) {
                    Text(stringResource(R.string.settings_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetDialog = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                ) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
            containerColor = DarkCard,
            shape = RoundedCornerShape(20.dp)
        )
    }

    Scaffold(
        containerColor = DarkBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_title),
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // Server section
            SettingsSectionHeader(stringResource(R.string.settings_server_section))

            SettingsCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = {
                            serverUrl = it
                            testState = ConnectionTestState.IDLE
                        },
                        label = { Text(stringResource(R.string.settings_server_url), color = TextTertiary) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = DarkDivider,
                            cursorColor = AccentRed,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedContainerColor = DarkSurface,
                            unfocusedContainerColor = DarkSurface,
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                if (ServerUrlUtils.isValidServerUrl(serverUrl)) {
                                    val normalizedUrl = ServerUrlUtils.normalizeServerUrl(serverUrl)
                                    serverUrl = normalizedUrl
                                    preferences.serverUrl = normalizedUrl
                                    onUrlChanged(normalizedUrl)
                                } else if (serverUrl.isNotBlank()) {
                                    testState = ConnectionTestState.FAILED
                                    testMessage = invalidUrlMessage
                                }
                            }
                        )
                    )

                    // Test result indicator
                    AnimatedVisibility(
                        visible = testState != ConnectionTestState.IDLE,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            when (testState) {
                                ConnectionTestState.TESTING -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = AccentBlue
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Testing…", style = MaterialTheme.typography.bodySmall, color = AccentBlue)
                                }
                                ConnectionTestState.SUCCESS -> {
                                    Icon(Icons.Rounded.CheckCircle, null, tint = StatusSuccess, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Connected!", style = MaterialTheme.typography.bodySmall, color = StatusSuccess)
                                }
                                ConnectionTestState.FAILED -> {
                                    Icon(Icons.Rounded.Error, null, tint = StatusError, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(testMessage.ifBlank { "Failed" }, style = MaterialTheme.typography.bodySmall, color = StatusError)
                                }
                                else -> {}
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (!ServerUrlUtils.isValidServerUrl(serverUrl)) {
                                    testState = ConnectionTestState.FAILED
                                    testMessage = invalidUrlMessage
                                    return@OutlinedButton
                                }

                                testState = ConnectionTestState.TESTING
                                scope.launch {
                                    try {
                                        val ok = withContext(Dispatchers.IO) {
                                            val conn = URL(ServerUrlUtils.normalizeServerUrl(serverUrl)).openConnection() as HttpURLConnection
                                            conn.requestMethod = "HEAD"
                                            conn.connectTimeout = 8000
                                            conn.readTimeout = 8000
                                            conn.instanceFollowRedirects = true
                                            try {
                                                conn.connect()
                                                conn.responseCode in 200..399
                                            } finally {
                                                conn.disconnect()
                                            }
                                        }
                                        testState = if (ok) ConnectionTestState.SUCCESS else ConnectionTestState.FAILED
                                        testMessage = if (!ok) "Server error" else ""
                                    } catch (e: Exception) {
                                        testState = ConnectionTestState.FAILED
                                        testMessage = e.localizedMessage ?: "Failed"
                                    }
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            enabled = serverUrl.isNotBlank() && testState != ConnectionTestState.TESTING
                        ) {
                            Text(stringResource(R.string.settings_test_connection), style = MaterialTheme.typography.labelMedium)
                        }

                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                if (ServerUrlUtils.isValidServerUrl(serverUrl)) {
                                    val normalizedUrl = ServerUrlUtils.normalizeServerUrl(serverUrl)
                                    serverUrl = normalizedUrl
                                    preferences.serverUrl = normalizedUrl
                                    onUrlChanged(normalizedUrl)
                                    showSnackbar = "Server URL saved"
                                } else {
                                    testState = ConnectionTestState.FAILED
                                    testMessage = invalidUrlMessage
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                            enabled = serverUrl.isNotBlank()
                        ) {
                            Text("Save", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Playback section
            SettingsSectionHeader(stringResource(R.string.settings_playback_section))

            SettingsCard {
                SettingsPlayerModeItem(
                    selectedMode = playerMode,
                    onModeSelected = {
                        playerMode = it
                        preferences.playerMode = it
                    }
                )
                HorizontalDivider(color = DarkDivider, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                SettingsToggleItem(
                    icon = Icons.Rounded.ScreenLockPortrait,
                    title = stringResource(R.string.settings_keep_screen_on),
                    subtitle = stringResource(R.string.settings_keep_screen_on_desc),
                    checked = keepScreenOn,
                    onCheckedChange = {
                        keepScreenOn = it
                        preferences.keepScreenOn = it
                    }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Data section
            SettingsSectionHeader(stringResource(R.string.settings_data_section))

            SettingsCard {
                SettingsClickItem(
                    icon = Icons.Rounded.Cookie,
                    title = stringResource(R.string.settings_clear_cookies),
                    onClick = {
                        preferences.clearCookies()
                        showSnackbar = context.getString(R.string.settings_cookies_cleared)
                    }
                )
                HorizontalDivider(color = DarkDivider, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                SettingsClickItem(
                    icon = Icons.Rounded.CleaningServices,
                    title = stringResource(R.string.settings_clear_cache),
                    onClick = {
                        preferences.clearCache(context)
                        showSnackbar = context.getString(R.string.settings_cache_cleared)
                    }
                )
                HorizontalDivider(color = DarkDivider, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                SettingsClickItem(
                    icon = Icons.Rounded.RestartAlt,
                    title = stringResource(R.string.settings_reset_app),
                    titleColor = StatusError,
                    onClick = { showResetDialog = true }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // About section
            SettingsSectionHeader(stringResource(R.string.settings_about_section))

            SettingsCard {
                SettingsInfoItem(
                    icon = Icons.Rounded.Info,
                    title = stringResource(R.string.settings_version),
                    value = com.rdplayer.app.BuildConfig.VERSION_NAME
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = AccentBlue,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        content = content
    )
}

@Composable
private fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextPrimary,
                checkedTrackColor = AccentRed,
                uncheckedThumbColor = TextTertiary,
                uncheckedTrackColor = DarkDivider,
                uncheckedBorderColor = DarkDivider
            )
        )
    }
}

@Composable
private fun SettingsPlayerModeItem(
    selectedMode: PlayerMode,
    onModeSelected: (PlayerMode) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.OpenInNew, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = stringResource(R.string.settings_player_mode),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Text(
                    text = stringResource(R.string.settings_player_mode_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
            }
        }

        HorizontalDivider(color = DarkDivider, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))

        PlayerMode.values().forEachIndexed { index, mode ->
            if (index > 0) {
                HorizontalDivider(color = DarkDivider, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
            }
            SettingsRadioItem(
                title = stringResource(playerModeTitleRes(mode)),
                subtitle = stringResource(playerModeSubtitleRes(mode)),
                selected = selectedMode == mode,
                onClick = { onModeSelected(mode) }
            )
        }
    }
}

@Composable
private fun SettingsRadioItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
        }
        Spacer(modifier = Modifier.width(8.dp))
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = AccentRed,
                unselectedColor = TextTertiary
            )
        )
    }
}

private fun playerModeTitleRes(mode: PlayerMode): Int {
    return when (mode) {
        PlayerMode.INTERNAL -> R.string.settings_player_mode_internal
        PlayerMode.EXTERNAL -> R.string.settings_player_mode_external
        PlayerMode.ASK_EVERY_TIME -> R.string.settings_player_mode_ask
    }
}

private fun playerModeSubtitleRes(mode: PlayerMode): Int {
    return when (mode) {
        PlayerMode.INTERNAL -> R.string.settings_player_mode_internal_desc
        PlayerMode.EXTERNAL -> R.string.settings_player_mode_external_desc
        PlayerMode.ASK_EVERY_TIME -> R.string.settings_player_mode_ask_desc
    }
}

@Composable
private fun SettingsClickItem(
    icon: ImageVector,
    title: String,
    titleColor: androidx.compose.ui.graphics.Color = TextPrimary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor)
        Spacer(modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SettingsInfoItem(
    icon: ImageVector,
    title: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        Spacer(modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = TextTertiary)
    }
}
