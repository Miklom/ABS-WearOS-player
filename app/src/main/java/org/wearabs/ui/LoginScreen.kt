package org.wearabs.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight

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
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            if (!state.busy) {
                EdgeButton(
                    onClick = viewModel::submit,
                    enabled = state.canSubmit,
                    buttonSize = EdgeButtonSize.Medium
                ) { Text("Sign in") }
            }
        }
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                ListHeader(
                    modifier = Modifier.transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec)
                ) { Text("Sign in") }
            }

            item {
                Field(
                    label = "Server",
                    value = state.serverUrl,
                    placeholder = "abs.example.com",
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier.transformedHeight(this, spec)
                ) { onEditField("Server address", state.serverUrl, viewModel::setServerUrl) }
            }
            item {
                Field(
                    label = "User",
                    value = state.username,
                    placeholder = "username",
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier.transformedHeight(this, spec)
                ) { onEditField("Username", state.username, viewModel::setUsername) }
            }
            item {
                Field(
                    label = "Password",
                    // Never render the password back onto the watch face.
                    value = "•".repeat(state.password.length.coerceAtMost(12)),
                    placeholder = "password",
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier.transformedHeight(this, spec)
                ) { onEditField("Password", "", viewModel::setPassword) }
            }

            state.error?.let { error ->
                item { CenteredMessage(error) }
            }
            if (state.busy) {
                item { CenteredSpinner() }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    transformation: SurfaceTransformation,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
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
        transformation = transformation,
        modifier = modifier.fillMaxWidth()
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
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
            CenteredSpinner()
        }
    }
}
