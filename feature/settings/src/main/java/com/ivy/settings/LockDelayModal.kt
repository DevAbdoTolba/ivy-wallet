package com.ivy.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.design.utils.thenIf
import com.ivy.ui.R
import com.ivy.wallet.ui.theme.Ivy
import com.ivy.wallet.ui.theme.White
import com.ivy.wallet.ui.theme.modal.IvyModal
import com.ivy.wallet.ui.theme.modal.ModalTitle
import java.util.UUID

/**
 * Picker for how long the app waits in the background before it locks. The values are the
 * seconds persisted to [com.ivy.base.legacy.SharedPrefs.LOCK_DELAY_SECONDS] and read by the
 * lock timer in RootViewModel; 0 means "Instantly".
 */
@Composable
fun BoxWithConstraintsScope.LockDelayModal(
    visible: Boolean,
    selectedSeconds: Int,
    dismiss: () -> Unit,
    onDelaySelected: (Int) -> Unit,
    id: UUID = UUID.randomUUID(),
) {
    IvyModal(
        id = id,
        visible = visible,
        dismiss = dismiss,
        PrimaryAction = { }
    ) {
        Spacer(Modifier.height(32.dp))

        ModalTitle(text = stringResource(R.string.lock_after))

        Spacer(Modifier.height(24.dp))

        LockDelayOptions.forEach { (seconds, labelRes) ->
            LockDelayOptionRow(
                label = stringResource(labelRes),
                selected = seconds == selectedSeconds
            ) {
                onDelaySelected(seconds)
                dismiss()
            }

            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(8.dp))
    }
}

private val LockDelayOptions: List<Pair<Int, Int>> = listOf(
    0 to R.string.lock_delay_instantly,
    60 to R.string.lock_delay_1_min,
    300 to R.string.lock_delay_5_min,
    900 to R.string.lock_delay_15_min,
)

@Composable
private fun LockDelayOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Text(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .fillMaxWidth()
            .border(2.dp, if (selected) Ivy else UI.colors.medium, shape)
            .thenIf(selected) {
                background(Ivy, shape)
            }
            .clickable { onClick() }
            .padding(vertical = 16.dp, horizontal = 20.dp),
        text = label,
        style = UI.typo.b2.style(
            fontWeight = FontWeight.ExtraBold,
            color = if (selected) White else UI.colors.pureInverse
        )
    )
}
