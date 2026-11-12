package me.rerere.rikkahub.ui.pages.hoshio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.hoshio.health.HealthData
import me.rerere.rikkahub.hoshio.health.HealthDay
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SLEEP_COLOR = Color(0xFF8D88B5)
private val STEP_COLOR = Color(0xFF4F9BB5)
private val HEART_COLOR = Color(0xFFC8867C)
private val HM = DateTimeFormatter.ofPattern("HH:mm")

/**
 * 星汐「手錶與健康」給她看的儀表板（參考 Orbis 的健康頁）：
 * 某一天的睡眠、步數、心率，加上最近 7 天的睡眠和步數趨勢。沒有資料就顯示「—」，不當成 0。
 */
@Composable
fun HealthDashboard(refreshKey: Int) {
    val context = LocalContext.current
    var date by remember { mutableStateOf(LocalDate.now()) }
    var day by remember { mutableStateOf<HealthDay?>(null) }
    var week by remember { mutableStateOf<List<HealthDay>>(emptyList()) }
    LaunchedEffect(date, refreshKey) {
        day = null
        day = runCatching { HealthData.day(context, date) }.getOrElse { HealthDay(date) }
    }
    LaunchedEffect(refreshKey) {
        val today = LocalDate.now()
        week = (6 downTo 0).map { back ->
            runCatching { HealthData.day(context, today.minusDays(back.toLong()), withDetails = false) }.getOrElse { HealthDay(today.minusDays(back.toLong())) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 日期切換
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { date = date.minusDays(1) }) { Text("‹", fontSize = 26.sp) }
            Text(
                (if (date == LocalDate.now()) "今天 · " else if (date == LocalDate.now().minusDays(1)) "昨天 · " else "") +
                    "${date.monthValue}月${date.dayOfMonth}日（${"一二三四五六日"[date.dayOfWeek.value - 1]}）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(enabled = date < LocalDate.now(), onClick = { date = date.plusDays(1) }) { Text("›", fontSize = 26.sp) }
        }

        val d = day
        if (d == null) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            SleepCard(d)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("↗ 步數", d.steps?.let { "%,d".format(it) } ?: "—", "步", STEP_COLOR, Modifier.weight(1f),
                    d.calories?.let { "消耗約 $it 大卡" })
                StatCard("♡ 心率", (d.avgHr ?: d.restingHr)?.toString() ?: "—", "次 / 分", HEART_COLOR, Modifier.weight(1f),
                    listOfNotNull(
                        d.restingHr?.let { "靜止 $it" },
                        if (d.minHr != null && d.maxHr != null) "範圍 ${d.minHr}–${d.maxHr}" else null,
                    ).joinToString("・").ifBlank { null })
            }
            if (d.exercises.isNotEmpty()) {
                DashCard {
                    Text("✦ 運動", style = MaterialTheme.typography.labelLarge, color = STEP_COLOR)
                    d.exercises.forEach { Text(exerciseZh(it), style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }

        if (week.isNotEmpty()) {
            DashCard {
                Text("最近 7 天", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val sleeps = week.mapNotNull { it.sleep?.asleepMinutes }
                Text(
                    "睡眠" + if (sleeps.isEmpty()) "：暫無記錄" else "：平均 ${sleeps.average().toLong() / 60} 小時 ${sleeps.average().toLong() % 60} 分",
                    style = MaterialTheme.typography.labelLarge,
                )
                WeekBars(week, { it.sleep?.asleepMinutes?.toDouble() }, SLEEP_COLOR, date) { date = it }
                val steps = week.mapNotNull { it.steps }
                Text(
                    "步數" + if (steps.isEmpty()) "：暫無記錄" else "：平均 ${"%,d".format(steps.average().toLong())} 步",
                    style = MaterialTheme.typography.labelLarge,
                )
                WeekBars(week, { it.steps?.toDouble() }, STEP_COLOR, date) { date = it }
                Text("點長條可以看那一天・空白代表沒有資料，不算 0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DashCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun SleepCard(d: HealthDay) {
    DashCard {
        Text("☾ 睡眠", style = MaterialTheme.typography.labelLarge, color = SLEEP_COLOR)
        val s = d.sleep
        if (s == null) {
            Text("尚未記錄", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val zone = ZoneId.systemDefault()
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${s.asleepMinutes / 60}", fontSize = 40.sp, fontWeight = FontWeight.Medium)
                Text(" 小時 ", Modifier.padding(bottom = 8.dp))
                Text("${s.asleepMinutes % 60}", fontSize = 40.sp, fontWeight = FontWeight.Medium)
                Text(" 分鐘", Modifier.padding(bottom = 8.dp))
            }
            Row {
                Column(Modifier.weight(1f)) {
                    Text("入睡", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(s.start.atZone(zone).format(HM), style = MaterialTheme.typography.titleMedium)
                }
                Column(Modifier.weight(1f)) {
                    Text("起床", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(s.end.atZone(zone).format(HM), style = MaterialTheme.typography.titleMedium)
                }
            }
            val stages = listOf(
                Triple("深眠", s.deepMinutes, Color(0xFF4B4785)),
                Triple("淺眠", s.lightMinutes, Color(0xFF8D88B5)),
                Triple("REM", s.remMinutes, Color(0xFF7FB3C9)),
                Triple("清醒", s.awakeMinutes, Color(0xFFE2B77A)),
            ).filter { it.second > 0 }
            if (stages.isNotEmpty()) {
                val total = stages.sumOf { it.second }.toFloat()
                Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
                    stages.forEach { (_, m, c) -> Box(Modifier.weight(m / total).height(10.dp).background(c)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    stages.forEach { (label, m, c) ->
                        Text("● ", color = c, style = MaterialTheme.typography.labelSmall)
                        Text("$label ${m / 60}:${"%02d".format(m % 60)}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 0.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, unit: String, color: Color, modifier: Modifier, sub: String?) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = color)
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
            Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun WeekBars(week: List<HealthDay>, value: (HealthDay) -> Double?, color: Color, selected: LocalDate, onPick: (LocalDate) -> Unit) {
    val values = week.map(value)
    val peak = values.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val track = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        week.forEachIndexed { i, d ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .background(if (d.date == selected) color.copy(alpha = .12f) else Color.Transparent)
                    .clickable { onPick(d.date) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Canvas(Modifier.fillMaxWidth().height(64.dp)) {
                    val w = size.width * .5f
                    val x = (size.width - w) / 2f
                    drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(w / 2f))
                    values[i]?.let { v ->
                        val h = (v / peak * size.height).toFloat().coerceAtLeast(w)
                        drawRoundRect(color, Offset(x, size.height - h), Size(w, h), CornerRadius(w / 2f))
                    }
                }
                Text("一二三四五六日"[d.date.dayOfWeek.value - 1].toString(), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun exerciseZh(s: String): String = s
    .replace("walking", "走路").replace("running", "跑步").replace("biking", "騎車").replace("hiking", "健行")
    .replace("yoga", "瑜伽").replace("swimming", "游泳").replace("strength training", "肌力訓練").replace("weightlifting", "重訓")
    .replace("exercise #", "運動 #")
