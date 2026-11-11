package me.rerere.rikkahub.hoshio.note

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 他留在側邊欄最上面的一句話（取代固定的「早上好👋」） */
data class SideNote(val text: String, val at: Long)

object SideNoteStore {
    private const val PREFS = "hoshio_side_note"
    private val _note = MutableStateFlow<SideNote?>(null)
    val note: StateFlow<SideNote?> = _note.asStateFlow()
    private var loaded = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(context: Context): SideNote? {
        if (!loaded) {
            val p = prefs(context)
            _note.value = p.getString("text", null)?.takeIf { it.isNotBlank() }?.let { SideNote(it, p.getLong("at", 0)) }
            loaded = true
        }
        return _note.value
    }

    @Synchronized
    fun set(context: Context, text: String): SideNote {
        val note = SideNote(text.trim().replace("\n", " ").take(40), System.currentTimeMillis())
        prefs(context).edit { putString("text", note.text); putLong("at", note.at) }
        _note.value = note
        loaded = true
        return note
    }
}
