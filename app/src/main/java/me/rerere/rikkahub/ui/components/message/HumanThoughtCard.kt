package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock

/**
 * 星汐: 她的心裡話（💭 開頭那幾行），像 AI 的思考一樣收合，點開才看得到。
 * 只影響顯示；送給模型時另外由 HumanThoughtTransformer 處理（他要用工具才看得到）。
 */
@Composable
internal fun HumanThoughtCard(content: String, key: String) {
    var expanded by remember(key) { mutableStateOf(false) }
    val ink = LocalContentColor.current.copy(alpha = .72f)
    Column(Modifier.widthIn(min = 120.dp)) {
        Row(
            modifier = Modifier
                .heightIn(min = 32.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable { expanded = !expanded }
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(HugeIcons.Idea01, null, Modifier.size(15.dp), tint = ink)
            Text("思考鏈", style = MaterialTheme.typography.labelMedium, color = ink)
            Icon(if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, null, Modifier.size(15.dp), tint = ink)
        }
        if (expanded) SelectionContainer {
            MarkdownBlock(
                content = content.ifBlank { "（為空）" },
                style = MaterialTheme.typography.bodySmall.copy(color = ink),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}
