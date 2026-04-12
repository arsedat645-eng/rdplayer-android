package com.rdplayer.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rdplayer.app.R
import com.rdplayer.app.ui.theme.*
import com.rdplayer.app.util.ServerUrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

import com.rdplayer.app.model.ConnectionTestState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onSetupComplete: (String) -> Unit
) {
    var serverUrl by remember { mutableStateOf("") }
    var testState by remember { mutableStateOf(ConnectionTestState.IDLE) }
    var errorMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val invalidUrlMessage = stringResource(R.string.setup_invalid_url)

    fun testConnection() {
        if (!ServerUrlUtils.isValidServerUrl(serverUrl)) {
            testState = ConnectionTestState.FAILED
            errorMessage = invalidUrlMessage
            return
        }
        testState = ConnectionTestState.TESTING
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val url = URL(ServerUrlUtils.normalizeServerUrl(serverUrl))
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "HEAD"
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    connection.instanceFollowRedirects = true
                    try {
                        connection.connect()
                        connection.responseCode in 200..399
                    } finally {
                        connection.disconnect()
                    }
                }
                testState = if (result) ConnectionTestState.SUCCESS else ConnectionTestState.FAILED
                errorMessage = if (!result) "Server returned an error" else ""
            } catch (e: Exception) {
                testState = ConnectionTestState.FAILED
                errorMessage = e.localizedMessage ?: "Connection failed"
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App icon
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(AccentRed, AccentRedDark)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = stringResource(R.string.setup_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.setup_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Server URL card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = DarkCard
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Public,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Server Address",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = {
                            serverUrl = it
                            if (testState != ConnectionTestState.IDLE) {
                                testState = ConnectionTestState.IDLE
                            }
                        },
                        placeholder = {
                            Text(
                                stringResource(R.string.setup_url_hint),
                                color = TextTertiary,
                                fontSize = 14.sp
                            )
                        },
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
                            onDone = { focusManager.clearFocus() }
                        )
                    )

                    // Test result
                    AnimatedVisibility(
                        visible = testState != ConnectionTestState.IDLE,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            when (testState) {
                                ConnectionTestState.TESTING -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = AccentBlue
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        stringResource(R.string.setup_testing),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AccentBlue
                                    )
                                }
                                ConnectionTestState.SUCCESS -> {
                                    Icon(
                                        Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = StatusSuccess,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        stringResource(R.string.setup_success),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StatusSuccess
                                    )
                                }
                                ConnectionTestState.FAILED -> {
                                    Icon(
                                        Icons.Rounded.Error,
                                        contentDescription = null,
                                        tint = StatusError,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        errorMessage.ifBlank { stringResource(R.string.setup_failed) },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StatusError
                                    )
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Test button
                OutlinedButton(
                    onClick = {
                        focusManager.clearFocus()
                        testConnection()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = Brush.linearGradient(listOf(DarkDivider, DarkDivider))
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = AccentBlue
                    ),
                    enabled = serverUrl.isNotBlank() && testState != ConnectionTestState.TESTING
                ) {
                    Text(
                        stringResource(R.string.setup_test),
                        modifier = Modifier.padding(vertical = 4.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Save button
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        if (ServerUrlUtils.isValidServerUrl(serverUrl)) {
                            onSetupComplete(ServerUrlUtils.normalizeServerUrl(serverUrl))
                        } else {
                            testState = ConnectionTestState.FAILED
                            errorMessage = invalidUrlMessage
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentRed,
                        contentColor = TextPrimary
                    ),
                    enabled = serverUrl.isNotBlank() && testState != ConnectionTestState.TESTING
                ) {
                    Text(
                        stringResource(R.string.setup_save),
                        modifier = Modifier.padding(vertical = 4.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
