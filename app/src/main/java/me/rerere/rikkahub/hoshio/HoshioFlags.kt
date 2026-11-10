package me.rerere.rikkahub.hoshio

import android.content.Context
import androidx.core.content.edit

/**
 * 星汐自己的全域开关，存在独立的 SharedPreferences，不动 RikkaHub 的设定档。
 */
object HoshioFlags {
    private const val PREFS = "hoshio_flags"
    private const val KEY_FOLLOW_ASSISTANT = "conversation_follow_assistant"

    private var appContext: Context? = null

    /**
     * 对话跟随助手：开启时对话不再固定自己的模型、推理、搜寻、MCP、工作区与技能，
     * 一律使用助手当前的设定；在聊天页切换这些设定也会直接改到助手上，套用到所有对话。
     */
    @Volatile
    var followAssistant: Boolean = true
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        followAssistant = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_FOLLOW_ASSISTANT, true)
    }

    fun setFollowAssistant(value: Boolean) {
        followAssistant = value
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit { putBoolean(KEY_FOLLOW_ASSISTANT, value) }
    }
}
