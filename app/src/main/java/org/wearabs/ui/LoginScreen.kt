package org.wearabs.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

/**
 * Server address, username and password. Each field opens Wear's system text
 * input, so the phone keyboard can be used when one is paired.
 */
@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onEditField: (label: String, initial: String, onResult: (String) -> Unit) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize()
        ) {
            item { ListHeader { Text("Sign in") } }

            item {
                Field(
                    label = "Server",
                    value = state.serverUrl,
                    placeholder = "abs.example.com",
                    onClick = {
                        onEditField("Server address", state.serverUrl, viewModel::setServerUrl)
                    }
                )
            }
            item {
                Field(
                    label = "User",
                    value = state.username,
                    placeholder = "username",
                    onClick = { onEditField("Username", state.username, viewModel::setUsername) }
                )
            }
            item {
                Field(
                    label = "Password",
                    // Never render the password back onto the watch face.
                    value = if (state.password.isEmpty()) "" else "•".repeat(state.password.length.coerceAtMost(12)),
                    placeholder = "password",
                    onClick = { onEditField("Password", "", viewModel::setPassword) }
                )
            }

            state.error?.let { error ->
                item { CenteredMessage(error) }
            }

            item {
                if (state.busy) {
                    CenteredSpinner()
                } else {
                    Button(
                        onClick = viewModel::submit,
                        enabled = state.canSubmit,
                        label = { Text("Sign in") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, placeholder: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        secondaryLabel = {
            Text(
                text = value.ifBlank { placeholder },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        modifier = Modifier.fillMaxWidth()
    )
}

/** Shown while the stored session is being read at launch. */
@Composable
fun SplashScreen() {
    ScreenScaffold {
        CenteredColumn {
            Text(
                text = "ABS Player",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            CenteredSpinner()
        }
    }
}
