package me.rerere.rikkahub.ui.pages.hoshio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.hoshio.together.BY_HER
import me.rerere.rikkahub.hoshio.together.BY_HIM
import me.rerere.rikkahub.hoshio.together.BoardGameStore
import me.rerere.rikkahub.hoshio.together.DailyQuestion
import me.rerere.rikkahub.hoshio.together.DailyQuestionStore
import java.time.LocalDate

@Composable
fun BoardGamePage() {
    val context = LocalContext.current
    val game by BoardGameStore.game.collectAsState()
    remember { BoardGameStore.load(context) }
    var type by remember { mutableStateOf("ttt") }
    var herFirst by remember { mutableStateOf(true) }
    val record = remember(game?.winner) { BoardGameStore.record(context) }

    val thinking by BoardGameStore.thinking.collectAsState()

    // 轮到他就请他下（离开页面也会继续）
    LaunchedEffect(game?.startedAt, game?.moves, game?.turn) { BoardGameStore.requestHisMove(context) }

    PlayScaffold("下棋") { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("戰績：你 ${record.first} 勝・他 ${record.second} 勝・平手 ${record.third}", style = MaterialTheme.typography.labelLarge)
            val g = game
            if (g == null) {
                Text("你是 ✕，他是 ○。他會自己決定怎麼下，邊下邊說話。", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = type == "ttt", onClick = { type = "ttt" }, label = { Text("圈圈叉叉 3×3") })
                    FilterChip(selected = type == "gomoku", onClick = { type = "gomoku" }, label = { Text("五子棋 11×11") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = herFirst, onClick = { herFirst = true }, label = { Text("我先下") })
                    FilterChip(selected = !herFirst, onClick = { herFirst = false }, label = { Text("他先下") })
                }
                Button(onClick = { BoardGameStore.start(context, type, herFirst) }, modifier = Modifier.fillMaxWidth()) { Text("開始") }
            } else {
                Text(
                    when (g.winner) {
                        BY_HER -> "🎉 你贏了！"
                        BY_HIM -> "他贏了 😏"
                        "draw" -> "平手～"
                        else -> if (g.turn == BY_HER) "輪到你（✕）" else if (thinking) "他在想…" else "輪到他（○）"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (g.hisComment.isNotBlank()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = MaterialTheme.shapes.large) {
                        Text("他：${g.hisComment}", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                val lineColor = MaterialTheme.colorScheme.outlineVariant
                Column(
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp),
                ) {
                    for (r in 0 until g.size) {
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            for (c in 0 until g.size) {
                                val index = r * g.size + c
                                val mark = g.cells[index]
                                Box(
                                    Modifier.weight(1f).fillMaxSize().border(0.5.dp, lineColor)
                                        .background(if (g.lastMove == index) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent)
                                        .clickable(enabled = mark == '.' && g.turn == BY_HER && g.winner == null) {
                                            BoardGameStore.move(context, BY_HER, index)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (mark != '.') {
                                        Text(
                                            if (mark == BoardGameStore.HER) "✕" else "○",
                                            fontSize = if (g.size <= 3) 44.sp else 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (mark == BoardGameStore.HER) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (g.winner != null) {
                        Button(onClick = { BoardGameStore.start(context, g.type, herFirst = g.winner != BY_HER) }) { Text("再來一局") }
                    }
                    TextButton(onClick = { BoardGameStore.clear(context) }) { Text(if (g.winner != null) "換遊戲" else "不玩了") }
                }
            }
        }
    }
}

// ───────────────────────── 每日一问 ─────────────────────────

@Composable
fun DailyQuestionPage() {
    val context = LocalContext.current
    val questions by DailyQuestionStore.items.collectAsState()
    remember { DailyQuestionStore.load(context) }
    val callHim = rememberCallHim()
    val todayStr = LocalDate.now().toString()
    val today = questions.firstOrNull { it.date == todayStr }
    var asking by remember { mutableStateOf(false) }
    var answer by remember(today?.id) { mutableStateOf("") }

    PlayScaffold("每日一問") { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.extraLarge) {
                    Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("今天的問題", style = MaterialTheme.typography.labelLarge)
                        if (today == null) {
                            Text("今天還沒有問題", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Button(onClick = {
                                callHim("每日一問", "She wants today's question. Think of something you genuinely want to know about her, set it with daily_question ask, " +
                                    "answer it yourself with daily_question answer, then ask her in chat (do not reveal your answer until she answers). " +
                                    "When she answers in chat, save it with daily_question record_her_answer.")
                            }, modifier = Modifier.fillMaxWidth()) { Text("請他問我一題") }
                            FilledTonalButton(onClick = { asking = true }, modifier = Modifier.fillMaxWidth()) { Text("我來問他") }
                        } else {
                            Text(today.question, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text(if (today.asker == BY_HIM) "他問的" else "你問的", style = MaterialTheme.typography.labelMedium)
                            if (today.herAnswer.isBlank()) {
                                OutlinedTextField(value = answer, onValueChange = { answer = it }, label = { Text("你的回答") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                                Button(enabled = answer.isNotBlank(), onClick = {
                                    DailyQuestionStore.update(context) { list -> list.map { if (it.id == today.id) it.copy(herAnswer = answer.trim()) else it } }
                                    callHim("每日一問", "She answered today's question \"${today.question}\": \"${answer.trim()}\". " +
                                        (if (today.hisAnswer.isBlank()) "Answer it yourself too with daily_question answer, then " else "") +
                                        "react to her answer and share yours in character.")
                                }) { Text("回答") }
                            } else {
                                Text("你：${today.herAnswer}", style = MaterialTheme.typography.bodyLarge)
                                Text("他：${today.hisAnswer.ifBlank { "（還沒回答）" }}", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
            val past = questions.filter { it.date != todayStr }
            if (past.isNotEmpty()) item { Text("以前的問題", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            items(past, key = { it.id }) { q ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${q.date}・${if (q.asker == BY_HIM) "他問" else "你問"}", style = MaterialTheme.typography.labelSmall)
                        Text(q.question, style = MaterialTheme.typography.titleSmall)
                        Text("你：${q.herAnswer.ifBlank { "—" }}", style = MaterialTheme.typography.bodyMedium)
                        Text("他：${q.hisAnswer.ifBlank { "—" }}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    if (asking) {
        var question by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("問他一個問題") },
            text = { OutlinedTextField(value = question, onValueChange = { question = it }, minLines = 2) },
            confirmButton = {
                TextButton(enabled = question.isNotBlank(), onClick = {
                    DailyQuestionStore.update(context) { listOf(DailyQuestion(asker = BY_HER, question = question.trim().take(200))) + it }
                    asking = false
                    callHim("每日一問", "She asked you today's question: \"${question.trim()}\". Answer it honestly in character, " +
                        "and also save your answer with daily_question answer. Then ask what her own answer would be, and when she tells you, " +
                        "save it with daily_question record_her_answer.")
                }) { Text("問他") }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("取消") } },
        )
    }
}
