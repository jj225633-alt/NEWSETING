package me.rerere.rikkahub.ui.pages.hoshio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.pages.hoshio.splash.HoshioGlass
import me.rerere.rikkahub.ui.pages.hoshio.splash.LocalHoshioChatGlass

/** 場景模式：像看小說。動作、神態（括號或 *…* 包起來的段落）用淡一點的斜體 */
@Composable
fun SceneProse(text: String, streaming: Boolean) {
    val paragraphs = remember(text) { text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotBlank() } }
    val body = @Composable {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            paragraphs.forEachIndexed { i, p ->
                val action = p.startsWith("（") || p.startsWith("(") || (p.startsWith("*") && p.endsWith("*") && !p.startsWith("**"))
                if (action) {
                    Text(
                        p.trim('*'),
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif, lineHeight = 24.sp),
                        color = LocalContentColor.current.copy(alpha = .7f),
                    )
                } else {
                    MarkdownBlock(
                        content = p,
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif, lineHeight = 26.sp),
                        streaming = streaming && i == paragraphs.lastIndex,
                    )
                }
            }
        }
    }
    if (LocalHoshioChatGlass.current) {
        HoshioGlass(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) { body() }
    } else body()
}
