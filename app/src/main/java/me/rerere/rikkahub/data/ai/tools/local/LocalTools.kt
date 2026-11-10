package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.tts.provider.TTSManager

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
) {
    val javascriptTool by lazy { buildJavascriptTool() }

    val timeTool by lazy { buildTimeInfoTool() }

    val clipboardTool by lazy { buildClipboardTool(context) }

    val ttsTool by lazy { buildTextToSpeechTool(eventBus, ttsManager, settingsStore) }

    val askUserTool by lazy { buildAskUserTool() }

    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }

    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }

    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }

    val chartDisplayTool by lazy { buildChartDisplayTool() }

    val deviceStatusTool by lazy { buildDeviceStatusTool(context) }

    val locationTool by lazy { buildLocationTool(context) }

    val screenViewTool by lazy { buildScreenViewTool(context) }

    val diaryTools by lazy { buildDiaryTools(context) }

    val selfReminderTools by lazy { buildSelfReminderTools(context) }

    val playroomTools by lazy { buildPlayroomTools(context) }

    val anniversaryTools by lazy { buildAnniversaryTools(context) }

    val weatherTool by lazy { buildWeatherTool(context) }

    val privateRoomTools by lazy { buildPrivateRoomTools(context) }

    val libraryTools by lazy { buildLibraryTools(context) }

    val musicTools by lazy { buildMusicTools(context) }

    val lifeTools by lazy { buildLifeTools(context) }

    val appLockTools by lazy { buildAppLockTools(context) }

    val playTools by lazy { buildPlayTools(context) }

    fun getTools(options: List<LocalToolOption>): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (options.contains(LocalToolOption.Tts)) {
            tools.add(ttsTool)
        }
        if (options.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (options.contains(LocalToolOption.ScreenTime)) {
            tools.add(screenTimeTool)
        }
        if (options.contains(LocalToolOption.Calendar)) {
            tools.add(calendarQueryTool)
            tools.add(calendarCreateTool)
        }
        if (options.contains(LocalToolOption.ChartDisplay)) {
            tools.add(chartDisplayTool)
        }
        if (options.contains(LocalToolOption.DeviceStatus)) {
            tools.add(deviceStatusTool)
        }
        if (options.contains(LocalToolOption.Location)) {
            tools.add(locationTool)
        }
        if (options.contains(LocalToolOption.ScreenView)) {
            tools.add(screenViewTool)
        }
        if (options.contains(LocalToolOption.Diary)) {
            tools.addAll(diaryTools)
        }
        if (options.contains(LocalToolOption.SelfReminder)) {
            tools.addAll(selfReminderTools)
        }
        if (options.contains(LocalToolOption.Playroom)) {
            tools.addAll(playroomTools)
        }
        if (options.contains(LocalToolOption.Anniversary)) {
            tools.addAll(anniversaryTools)
        }
        if (options.contains(LocalToolOption.Weather)) {
            tools.add(weatherTool)
        }
        if (options.contains(LocalToolOption.PrivateRoom)) {
            tools.addAll(privateRoomTools)
        }
        if (options.contains(LocalToolOption.Library)) {
            tools.addAll(libraryTools)
        }
        if (options.contains(LocalToolOption.Music)) {
            tools.addAll(musicTools)
        }
        if (options.contains(LocalToolOption.Life)) {
            tools.addAll(lifeTools)
        }
        if (options.contains(LocalToolOption.AppLock)) {
            tools.addAll(appLockTools)
        }
        if (options.contains(LocalToolOption.Play)) {
            tools.addAll(playTools)
        }
        return tools
    }
}
