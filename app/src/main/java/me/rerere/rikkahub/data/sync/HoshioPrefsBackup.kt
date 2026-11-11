package me.rerere.rikkahub.data.sync

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 星汐: 很多星汐資料存在 SharedPreferences（hoshio_*.xml），原本的備份只帶 filesDir 裡的資料夾，會漏掉。
 * 備份時把它們複製成 hoshio/_prefs 底下的 xml；還原後下次啟動、在任何人讀取之前，搬回 shared_prefs。
 */
object HoshioPrefsBackup {
    private const val TAG = "HoshioPrefsBackup"
    /** 只是暫存或紀錄、不需要搬到新手機的 */
    private val SKIP = setOf("hoshio_web_denied.xml", "hoshio_diagnostics.xml", "hoshio_call_state.xml")

    private fun prefsDir(context: Context) = File(context.applicationInfo.dataDir, "shared_prefs")

    fun snapshot(context: Context): List<Pair<String, File>> =
        prefsDir(context).listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("hoshio_") && it.name.endsWith(".xml") && it.name !in SKIP }
            .map { it.name to it }

    /** 啟動時呼叫：有剛還原進來的 hoshio/_prefs 就搬回 shared_prefs */
    fun installRestored(context: Context) {
        val staged = File(context.filesDir, "hoshio/_prefs")
        if (!staged.isDirectory) return
        runCatching {
            val dir = prefsDir(context).apply { mkdirs() }
            staged.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("hoshio_") && it.name.endsWith(".xml") }.forEach { f ->
                f.copyTo(File(dir, f.name), overwrite = true)
            }
        }.onFailure { Log.e(TAG, "install restored prefs failed", it) }
        staged.deleteRecursively()
    }
}
