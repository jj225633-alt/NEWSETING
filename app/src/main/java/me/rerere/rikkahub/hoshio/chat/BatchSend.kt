package me.rerere.rikkahub.hoshio.chat

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 星汐「等我說完再回」：送出後先等幾秒，這段時間又送了新的就繼續等，停下來才讓他一次回覆。
 */
object BatchSend {
    private const val PREFS = "hoshio_batch_send"
    private val _pending = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** 對話 id → 預計開始回覆的時間 */
    val pending: StateFlow<Map<String, Long>> = _pending.asStateFlow()
    private val _enabled = MutableStateFlow<Boolean?>(null)
    val enabledFlow: StateFlow<Boolean?> = _enabled.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false).also { _enabled.value = it }
    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean("enabled", value) }
        _enabled.value = value
    }

    fun seconds(context: Context): Int = prefs(context).getInt("seconds", 5)
    fun setSeconds(context: Context, value: Int) = prefs(context).edit { putInt("seconds", value.coerceIn(2, 30)) }

    fun mark(id: String, fireAt: Long) { _pending.value = _pending.value + (id to fireAt) }
    fun clear(id: String) { _pending.value = _pending.value - id }
}
