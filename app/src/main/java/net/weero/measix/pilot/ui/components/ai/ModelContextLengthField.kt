package net.weero.measix.pilot.ui.components.ai

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import net.weero.measix.pilot.R

internal fun validModelContextLengthInput(value: String): Boolean = value.isEmpty() ||
    (value.all { it in '0'..'9' } && value.toIntOrNull()?.let { it > 0 } == true)

@Composable
internal fun ModelContextLengthField(value: String, onValueChange: (String) -> Unit, description: String, enabled: Boolean = true) {
    val valid = validModelContextLengthInput(value)
    OutlinedTextField(
        value = value, onValueChange = onValueChange, enabled = enabled,
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(stringResource(R.string.model_context_capacity)) },
        placeholder = { Text(stringResource(R.string.model_context_capacity_unknown)) },
        supportingText = { Text(if (valid) description else stringResource(R.string.model_context_capacity_invalid)) },
        isError = !valid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
