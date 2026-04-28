package com.ivy.sms.ui.templates

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardMapping

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WildcardMappingBottomSheet(
    wildcardId: WildcardId,
    currentMappings: Map<WildcardId, WildcardMapping>,
    onChoose: (WildcardMapping) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val takenMappings = currentMappings
        .filterKeys { it != wildcardId }
        .values
        .filter { it != WildcardMapping.Ignored && it != WildcardMapping.Unmapped }
        .toSet()

    val options: List<Pair<String, WildcardMapping>> = listOf(
        "Amount" to WildcardMapping.Amount,
        "Merchant" to WildcardMapping.Merchant,
        "Date / time" to WildcardMapping.DateTime,
        "Reference" to WildcardMapping.Reference,
        "Ignore" to WildcardMapping.Ignored,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(text = "What does this part of the message mean?")
            options.forEach { (label, mapping) ->
                val disabled = mapping in takenMappings
                ListItem(
                    headlineContent = { Text(label) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .let {
                            if (disabled) it
                            else it.padding()
                        },
                    leadingContent = if (disabled) {
                        @Composable {
                            Text("(used)")
                        }
                    } else {
                        null
                    },
                )
                if (!disabled) {
                    androidx.compose.material3.TextButton(
                        onClick = { onChoose(mapping) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Use as $label")
                    }
                }
            }
        }
    }
}
