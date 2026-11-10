package me.rerere.rikkahub.ui.pages.hoshio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * 星汐: 常用数值做成晶片，最后多一个「自訂」可以手动输入（限制在 [range] 里）。
 * [unit] 显示在输入框旁，例如「分鐘」「點（0–23）」。
 */
@Composable
fun NumberChoiceChips(
    options: List<Int>,
    selected: Int,
    label: (Int) -> String,
    range: IntRange,
    unit: String,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val isCustom = selected !in options
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { option ->
            FilterChip(selected = selected == option, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
        FilterChip(
            selected = isCustom,
            onClick = { editing = true },
            label = { Text(if (isCustom) "自訂：${label(selected)}" else "自訂…") },
        )
    }
    if (editing) {
        var text by remember { mutableStateOf(selected.toString()) }
        val value = text.trim().toIntOrNull()
        val valid = value != null && value in range
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("自訂") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter(Char::isDigit).take(5) },
                        singleLine = true,
                        suffix = { Text(unit) },
                        isError = !valid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Text("可以填 ${range.first}–${range.last}", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    onSelect(value!!)
                    editing = false
                }) { Text("好") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } },
        )
    }
}
