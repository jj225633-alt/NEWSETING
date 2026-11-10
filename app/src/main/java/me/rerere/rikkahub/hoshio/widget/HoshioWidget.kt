package me.rerere.rikkahub.hoshio.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import androidx.core.content.edit
import me.rerere.rikkahub.R
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 星汐「桌面小工具」：在桌面显示他最新说的话，点一下打开 App。
 */
class HoshioWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        HoshioWidget.render(context, appWidgetManager, appWidgetIds)
    }
}

object HoshioWidget {
    private const val PREFS = "hoshio_widget"

    /** 记下他最新说的话并更新所有桌面小工具 */
    fun update(context: Context, name: String, message: String) {
        val text = message.replace(Regex("\\s+"), " ").trim()
        if (text.isBlank()) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("name", name)
            putString("message", text.take(300))
            putString("time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM/dd HH:mm")))
        }
        runCatching {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HoshioWidgetProvider::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }
    }

    /** 只重画（心情、宠物、待办会变） */
    fun refresh(context: Context) {
        runCatching {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HoshioWidgetProvider::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }
    }

    /** 小工具最下面那一行：宠物状况、接下来的生活照顾 */
    private fun statusLine(context: Context): String {
        val parts = mutableListOf<String>()
        runCatching {
            me.rerere.rikkahub.hoshio.play.PetStore.load(context)?.let { parts += "${it.species} ${it.name}：${it.status}" }
        }
        runCatching {
            val now = java.time.LocalTime.now()
            val next = me.rerere.rikkahub.hoshio.together.RoutineStore.load(context).filter { it.active }.flatMap { r ->
                r.times.mapNotNull { t -> runCatching { java.time.LocalTime.parse(t) }.getOrNull() }
                    .filter { it.isAfter(now) }
                    .map { it to r }
            }.minByOrNull { it.first }
            next?.let { (time, r) -> parts += "${r.emoji} ${r.label} ${time}" }
        }
        return parts.joinToString("・")
    }

    internal fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mood = runCatching { me.rerere.rikkahub.hoshio.life.MoodStore.current(context).mood }.getOrNull()
        val status = statusLine(context)
        val views = RemoteViews(context.packageName, R.layout.hoshio_widget).apply {
            setTextViewText(
                R.id.hoshio_widget_name,
                "✦ " + (prefs.getString("name", null) ?: "時澈") + (mood?.let { "・$it" } ?: ""),
            )
            setTextViewText(R.id.hoshio_widget_status, status)
            setViewVisibility(R.id.hoshio_widget_status, if (status.isBlank()) android.view.View.GONE else android.view.View.VISIBLE)
            setTextViewText(R.id.hoshio_widget_message, prefs.getString("message", null) ?: "點這裡和他聊天")
            setTextViewText(R.id.hoshio_widget_time, prefs.getString("time", null).orEmpty())
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                setOnClickPendingIntent(
                    R.id.hoshio_widget_root,
                    PendingIntent.getActivity(context, 7303, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            }
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}
