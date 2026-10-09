package com.mike.campusautofill.ui

import android.content.res.Configuration
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mike.campusautofill.R
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun FillConfirmation(
    username: String, hasExtra: Boolean, busy: Boolean,
    onCancel: () -> Unit, onFill: () -> Unit, onDrawn: (Int, Int) -> Unit = { _, _ -> }
) {
    val logged = remember { AtomicBoolean(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        modifier = Modifier.testTag("fillConfirmation").drawWithContent {
            drawContent()
            if (logged.compareAndSet(false, true)) onDrawn(size.width.toInt(), size.height.toInt())
        },
        title = { Text(stringResource(R.string.gate_title)) },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                12.dp)) {
                Text(if (username.isEmpty()) stringResource(R.string.gate_message_no_user)
                    else stringResource(R.string.gate_message, username))
                if (hasExtra) Text(stringResource(R.string.gate_has_extra), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { Button(onClick = onFill, enabled = !busy, modifier = Modifier.testTag("confirmFill")) {
            Text(if (busy) "正在验证…" else stringResource(R.string.gate_fill))
        } },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.gate_cancel)) } }
    )
}

@Preview(name = "确认·浅色", showBackground = true)
@Preview(name = "确认·深色", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "确认·大字", fontScale = 2f, widthDp = 320, showBackground = true)
@Composable
private fun ConfirmationPreview() {
    CampusTheme { FillConfirmation("TESTUSER", true, false, {}, {}) }
}
