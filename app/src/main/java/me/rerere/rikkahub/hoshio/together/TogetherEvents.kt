package me.rerere.rikkahub.hoshio.together

import android.content.Context
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 一则要叫醒他的事 */
data class TogetherEvent(val key: String, val title: String, val text: String)

/**
 * 星汐「我们的生活」的定时事件：生活照顾到点、约定到期、时光胶囊解锁。
 * 由 CareWorker 每次检查时呼叫。
 */
object TogetherEvents {
    fun hasReminders(context: Context): Boolean =
        RoutineStore.load(context).any { it.active } ||
            PromiseStore.load(context).any { !it.done && it.due != null && !it.reminded } ||
            CapsuleStore.load(context).any { !it.announced }

    fun detect(context: Context): List<TogetherEvent> {
        val events = mutableListOf<TogetherEvent>()
        val now = LocalDateTime.now()
        val today = now.toLocalDate()

        // 生活照顾：到点后 90 分钟内、还没做过就提醒一次
        RoutineStore.load(context).filter { it.active }.forEach { routine ->
            routine.times.mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }.forEach { time ->
                val due = LocalDateTime.of(today, time)
                val key = "${routine.id}_${today}_$time"
                if (!now.isBefore(due) && now.isBefore(due.plusMinutes(90)) &&
                    !RoutineLogStore.remindedKey(context, key) && !RoutineLogStore.doneFor(context, routine.id, time)
                ) {
                    RoutineLogStore.markReminded(context, key)
                    events += TogetherEvent(
                        key = "routine_${routine.kind}",
                        title = "${routine.emoji} ${routine.label}時間",
                        text = "Automatic event (not typed by her): it is $time, the time she set for \"${routine.label}\" " +
                            "(${routine.kind}). She has not marked it done yet. Remind her in character, short and caring " +
                            "(she can tap 「做了」 in the app, or tell you and you call care_routine log).",
                    )
                }
            }
        }

        // 约定到期：当天早上 9 点后提一次
        if (now.hour >= 9) {
            val dueToday = PromiseStore.load(context).filter { !it.done && !it.reminded && it.due != null && it.due <= today.toString() }
            if (dueToday.isNotEmpty()) {
                PromiseStore.update(context) { list -> list.map { p -> if (dueToday.any { it.id == p.id }) p.copy(reminded = true) else p } }
                events += TogetherEvent(
                    key = "promise_due",
                    title = "約定的日子到了",
                    text = "Automatic event (not typed by her): these promises between you two are due: " +
                        dueToday.joinToString("; ") { "[${it.id}] ${it.text}" } +
                        ". Bring it up with her in character. Mark done with our_promises complete when it happens.",
                )
            }
        }

        // 时光胶囊解锁：早上 8 点后告诉她
        if (now.hour >= 8) {
            val unlocked = CapsuleStore.load(context).filter { !it.announced && it.unlocked(today) }
            if (unlocked.isNotEmpty()) {
                CapsuleStore.update(context) { list -> list.map { c -> if (unlocked.any { it.id == c.id }) c.copy(announced = true) else c } }
                events += TogetherEvent(
                    key = "capsule_unlocked",
                    title = "時光膠囊可以打開了",
                    text = "Automatic event (not typed by her): these time capsules unlock today: " +
                        unlocked.joinToString("; ") { "\"${it.title}\" from ${if (it.by == BY_HIM) "you" else "her"}, sealed on " + LocalDate.ofEpochDay(it.createdAt / 86_400_000).toString() } +
                        ". Tell her she can open them now (首頁 → 時光膠囊). Do not reveal what is inside hers.",
                )
            }
        }
        return events
    }
}
