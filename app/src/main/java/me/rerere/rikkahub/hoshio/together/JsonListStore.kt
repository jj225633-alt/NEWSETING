package me.rerere.rikkahub.hoshio.together

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal val togetherJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** 存在 SharedPreferences 里的一串 JSON 资料，附一个给画面用的 StateFlow */
open class JsonListStore<T>(private val prefsName: String, item: KSerializer<T>, private val limit: Int = 500) {
    private val serializer = ListSerializer(item)
    private val _items = MutableStateFlow<List<T>>(emptyList())
    val items: StateFlow<List<T>> = _items.asStateFlow()

    protected fun prefs(context: Context) = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun load(context: Context): List<T> {
        val list = prefs(context).getString("items", null)
            ?.let { runCatching { togetherJson.decodeFromString(serializer, it) }.getOrNull() }
            .orEmpty()
        _items.value = list
        return list
    }

    fun save(context: Context, list: List<T>) {
        val trimmed = list.take(limit)
        prefs(context).edit { putString("items", togetherJson.encodeToString(serializer, trimmed)) }
        _items.value = trimmed
    }

    fun update(context: Context, fn: (List<T>) -> List<T>): List<T> = synchronized(this) {
        fn(load(context)).also { save(context, it) }
    }
}
