package me.rerere.rikkahub.web

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 星汐: 網頁伺服器的安全措施。
 * - 傳給網頁／電腦版的設定拿掉所有金鑰、密碼、權杖（網頁端用不到，改金鑰請在手機上）
 * - 被拒絕的連線記一行（時間、來源、路徑），只存在手機裡，最多 300 筆
 */
object WebSecurity {
    private val secretKeys = setOf(
        "apikey", "api_key", "key", "keys", "secret", "secretkey", "secret_key", "secretaccesskey", "accesskey", "accesskeyid",
        "accesskeysecret", "password", "passwd", "token", "accesstoken", "refreshtoken", "clientsecret", "appkey", "appsecret",
        "privatekey", "private_key", "proxypassword", "proxyusername", "webserveraccesspassword", "authorization", "cookie",
        "bearer", "credential", "credentials", "session", "sessiontoken",
    )
    /** 這些欄位整個換成空的（裡面常放金鑰） */
    private val wipeKeys = setOf("headers", "customheaders", "custombodies", "oauth", "env")

    fun redactedSettingsJson(settingsJson: String): String = runCatching {
        scrub(JsonInstant.parseToJsonElement(settingsJson)).toString()
    }.getOrDefault("{}")

    private fun scrub(e: JsonElement): JsonElement = when (e) {
        is JsonArray -> JsonArray(e.map(::scrub))
        is JsonObject -> JsonObject(e.mapValues { (k, v) ->
            val key = k.lowercase()
            when {
                key in wipeKeys -> if (v is JsonArray) JsonArray(emptyList()) else JsonNull
                key in secretKeys || key.endsWith("apikey") || key.endsWith("secret") || key.endsWith("token") || key.endsWith("password") ->
                    if (v is JsonPrimitive && v.isString) JsonPrimitive(if (v.content.isEmpty()) "" else "••••") else if (v is JsonArray) JsonArray(emptyList()) else scrub(v)
                else -> scrub(v)
            }
        })
        else -> e
    }

    // ---------------- 防爆破：同一個來源 10 分鐘內錯 5 次，鎖 30 分鐘 ----------------

    private val failures = mutableMapOf<String, MutableList<Long>>()
    private val lockedUntil = mutableMapOf<String, Long>()

    @Synchronized
    fun isLocked(remote: String): Boolean = (lockedUntil[remote] ?: 0L) > System.currentTimeMillis()

    @Synchronized
    fun recordFailure(remote: String) {
        val now = System.currentTimeMillis()
        val list = failures.getOrPut(remote) { mutableListOf() }
        list.removeAll { now - it > 10 * 60_000 }
        list += now
        if (list.size >= 5) {
            lockedUntil[remote] = now + 30 * 60_000
            list.clear()
        }
    }

    @Synchronized
    fun recordSuccess(remote: String) {
        failures.remove(remote)
    }

    // ---------------- 門鈴 ----------------

    private const val PREFS = "hoshio_web_denied"
    private val fmt = DateTimeFormatter.ofPattern("MM/dd HH:mm:ss")

    @Synchronized
    fun logDenied(context: Context, remote: String, method: String, path: String, status: Int) {
        runCatching {
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val line = Instant.now().atZone(ZoneId.systemDefault()).format(fmt) + "  $status  ${remote.take(60)}  $method ${path.take(120)}"
            val old = p.getString("log", "").orEmpty().lines().filter { it.isNotBlank() }
            p.edit { putString("log", (listOf(line) + old).take(300).joinToString("\n")) }
        }
    }

    fun deniedLog(context: Context): List<String> =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("log", "").orEmpty()
            .lines().filter { it.isNotBlank() }

    fun clearDenied(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove("log") }
}
