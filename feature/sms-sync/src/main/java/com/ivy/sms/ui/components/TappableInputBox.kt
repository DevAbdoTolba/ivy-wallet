package com.ivy.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style

/**
 * Single-line text input wrapped in an Ivy-themed card. Tapping anywhere on the
 * card — not just on the BasicTextField glyph area — focuses the field and
 * shows the keyboard. Replaces the wrapped `IvyBasicTextField` calls that
 * couldn't be tapped because their inner BasicTextField was sized to its empty
 * placeholder text width.
 */
@Composable
fun TappableInputBox(
    value: TextFieldValue,
    hint: String,
    onValueChanged: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions(
        keyboardType = KeyboardType.Text,
        imeAction = ImeAction.Done,
        capitalization = KeyboardCapitalization.Sentences,
        autoCorrect = true,
    ),
    keyboardActions: KeyboardActions? = null,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(UI.shapes.r4)
            .background(UI.colors.medium)
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) {
                focusRequester.requestFocus()
                keyboard?.show()
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.text.isBlank()) {
            Text(
                text = hint,
                style = UI.typo.b2.style(
                    color = UI.colors.gray,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
        BasicTextField(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            value = value,
            onValueChange = onValueChanged,
            singleLine = true,
            cursorBrush = SolidColor(UI.colors.pureInverse),
            textStyle = UI.typo.b2.style(
                color = UI.colors.pureInverse,
                fontWeight = FontWeight.SemiBold,
            ),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions ?: KeyboardActions(
                onDone = { keyboard?.hide() },
            ),
        )
    }
}
