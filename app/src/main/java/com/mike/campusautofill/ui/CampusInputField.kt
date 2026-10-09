package com.mike.campusautofill.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mike.campusautofill.R

/** Shared filled field. Material owns editing/IME/selection/semantics, not a hand-drawn editor. */
@Composable
internal fun CampusInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String,
    tag: String,
    enabled: Boolean,
    error: Boolean,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    password: Boolean = false
) {
    var visible by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // A fixed label stays readable after typing; no floating text cuts through a border.
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        TextField(value = value, onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }.testTag(tag),
            enabled = enabled, singleLine = true, isError = error,
            textStyle = MaterialTheme.typography.bodyLarge,
            placeholder = if (error) null else { { Text(hint) } }, shape = MaterialTheme.shapes.large,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = colors.surfaceColorAtElevation(2.dp),
                unfocusedContainerColor = colors.surfaceColorAtElevation(2.dp),
                errorContainerColor = colors.errorContainer,
                // Use the standard field's editing behavior with a quiet filled surface.
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent
            ),
            supportingText = if (error) { { Text(hint) } } else null,
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = if (password) { {
                IconButton(onClick = { visible = !visible }, enabled = enabled, modifier = Modifier.testTag("togglePassword")) {
                    Icon(ImageVector.vectorResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                        contentDescription = if (visible) "隐藏密码" else "显示密码", tint = colors.onSurfaceVariant)
                }
            } } else null
        )
    }
}
