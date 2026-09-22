package org.wearabs.ui

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Text

/**
 * Second tap for anything destructive. Shared by the two places that need it,
 * so the wording and behaviour cannot drift apart.
 */
@Composable
fun ConfirmDialog(
    visible: Boolean,
    title: String,
    detail: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(detail) },
        confirmButton = { AlertDialogDefaults.ConfirmButton(onClick = onConfirm) },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = onDismiss) }
    )
}
