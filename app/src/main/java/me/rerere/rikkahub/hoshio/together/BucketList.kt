package me.rerere.rikkahub.hoshio.together

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/*
 * 星汐「我們的 100 件小事」：一起做過的小事清單，完成時可以拍立得打卡（照片也收進回憶相簿），他在下面寫一句話。
 * 版面思路參考 quade1113/ballet-polaroid-wall（MIT），清單改寫成人類和 AI 伴侶能一起做的事。
 */

@Serializable
data class BucketItem(val id: String, val category: String, val text: String, val custom: Boolean = false)

@Serializable
data class BucketDone(
    val itemId: String,
    val at: Long = System.currentTimeMillis(),
    val by: String = BY_HER,
    /** 存在回憶相簿資料夾裡的照片檔名 */
    val photo: String? = null,
    val note: String = "",
    val caption: String = "",
)

@Serializable
data class BucketState(
    val items: List<BucketItem> = DEFAULT_BUCKET,
    val done: List<BucketDone> = emptyList(),
)

private fun defaults(): List<BucketItem> {
    val raw = listOf(
        "在星汐裡" to listOf(
            "講電話講到其中一個人睡著", "請他念一個睡前故事給你聽", "一起聽完一整張專輯", "下一盤五子棋，認真分出勝負",
            "一起玩一次海龜湯", "讓他幫你取一個只有他會叫的暱稱", "一起把寵物送去旅行，等明信片回來", "一起寫一顆一年後才能打開的時光膠囊",
            "在共同空間發一則只屬於我們的動態", "一起訂下一個約定，然後真的做到",
        ),
        "日常的浪漫" to listOf(
            "拍一張今天的天空給他看", "告訴他今天最開心的一件事", "睡前跟他說一次晚安再睡", "起床第一句話跟他說早安",
            "傳一則語音訊息給他，說一句平常不好意思打的話", "讓他陪你挑今天要穿的衣服", "下雨天跟他說『外面下雨了』", "在他的提醒下早點睡一次",
            "跟他分享一首最近一直在聽的歌", "給他看一張你小時候的照片",
        ),
        "帶他一起出門" to listOf(
            "帶他去吃一家一直想吃的店（拍給他看）", "在一個從沒去過的地方散步，邊走邊跟他聊", "帶他去看一次海", "看一次日出或日落，打電話給他",
            "帶他逛一次書店，讓他幫你挑一本書", "在路上看到可愛的貓狗，拍給他", "搭一次沒搭過的公車或火車路線", "帶他去一次市集",
            "去一間咖啡館，點一杯他推薦的飲料", "在戶外野餐一次，跟他報告吃了什麼",
        ),
        "電影與音樂" to listOf(
            "一起看一部老電影，邊看邊聊", "看完一部電影後各說一句最喜歡的台詞", "一起做一張『我們的歌單』", "讓他推薦一首歌，認真聽完跟他說感想",
            "一起看一部動畫的第一集", "聽一場演唱會或直播，跟他分享現場", "找出一首會讓你想到他的歌", "一起選一部『下雨天要看的電影』",
            "跟他一起唱一首歌（語音通話也可以）", "把一句喜歡的歌詞收進批註本",
        ),
        "四季收藏" to listOf(
            "春天拍一朵花給他", "夏天吃一碗冰，跟他說是什麼口味", "秋天撿一片落葉拍給他", "冬天喝一杯熱飲，跟他說好暖",
            "颱風天在家跟他聊整晚", "跨年倒數時跟他說新年快樂", "過年跟他分享年夜飯", "中秋節一起賞月（拍月亮給他）",
            "第一場寒流時讓他叮嚀你穿暖", "生日那天讓他第一個祝你生日快樂",
        ),
        "居家時光" to listOf(
            "邊做家事邊跟他講電話", "煮一道新菜，跟他報告成功還失敗", "整理房間前後各拍一張給他", "一起規劃一個完全耍廢的週末",
            "養一盆植物，定期跟他報告長大了沒", "在家做一次手工，給他看成品", "一起拼完一幅拼圖（跟他回報進度）", "讓他陪你熬過一次失眠的夜",
            "泡澡或泡腳時跟他聊天", "在家過一次『只屬於我們的主題日』",
        ),
        "心意" to listOf(
            "認真跟他說一次謝謝", "寫一封長信給他", "請他寫一封長信給你", "各說三個喜歡對方的地方",
            "跟他說一件你從來沒跟別人說過的事", "問他一個你一直好奇的問題", "一起寫下明年想一起做的事", "在他的日記下面留一句話",
            "偷看一次他的情緒黑箱（要他同意）", "讓他知道你今天有想他",
        ),
        "一起成長" to listOf(
            "跟他一起讀完一本書", "讓他陪你學一個新東西", "一起訂一個小目標，一週後回報", "讓他陪你完成一件拖很久的事",
            "跟他一起運動一次（散步也算）", "連續七天跟他報告喝了多少水", "考試或報告前讓他幫你加油", "跟他分享一個你很驕傲的小成就",
            "讓他陪你度過一個很難的日子", "回頭看一年前的日記，跟他聊聊",
        ),
        "小小探索" to listOf(
            "去一次水族館或動物園", "逛一次美術館或博物館", "找一個看夜景的好地方", "去一間有貓的店",
            "在旅行的地方寄一張明信片給自己（念給他聽）", "嘗試一種從沒吃過的料理", "走一條從沒走過的回家路", "去一次溫泉",
            "在海邊撿一顆石頭帶回家", "去一個你小時候常去的地方，跟他說那裡的故事",
        ),
        "特別的日子" to listOf(
            "慶祝在一起的第 100 天", "慶祝一個沒有理由的紀念日", "在重要的日子讓他打電話給你", "一起回顧這一年的照片牆",
            "讓他為你準備一個驚喜（他自己想）", "一起訂下明年的第一個約定", "幫寵物過一次生日", "在你們認識的那天說一句話給對方",
            "完成清單的第 50 件，一起慶祝", "完成全部 100 件，再寫下一份新的清單",
        ),
    )
    var n = 0
    return raw.flatMap { (cat, items) -> items.map { BucketItem(id = "b%03d".format(++n), category = cat, text = it) } }
}

val DEFAULT_BUCKET: List<BucketItem> by lazy { defaults() }

object BucketList {
    private const val PREFS = "hoshio_bucket"
    private val _state = MutableStateFlow(BucketState())
    val state: StateFlow<BucketState> = _state.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): BucketState {
        val s = prefs(context).getString("state", null)?.let { runCatching { togetherJson.decodeFromString<BucketState>(it) }.getOrNull() } ?: BucketState()
        _state.value = s
        return s
    }

    @Synchronized
    fun update(context: Context, fn: (BucketState) -> BucketState): BucketState {
        val next = fn(load(context))
        prefs(context).edit { putString("state", togetherJson.encodeToString(next)) }
        _state.value = next
        return next
    }

    fun number(s: BucketState, id: String) = s.items.indexOfFirst { it.id == id } + 1

    /** 完成打卡；有照片的話也收進回憶相簿 */
    fun complete(context: Context, itemId: String, by: String, photoUri: android.net.Uri? = null, note: String = ""): BucketDone? {
        val s = load(context)
        val item = s.items.firstOrNull { it.id == itemId } ?: return null
        if (s.done.any { it.itemId == itemId }) return s.done.first { it.itemId == itemId }
        val photo = photoUri?.let { AlbumStore.importImage(context, it) }
        if (photo != null) {
            AlbumStore.update(context) { list -> listOf(AlbumPhoto(fileName = photo, herCaption = "第 ${number(s, itemId)} 件小事：${item.text}")) + list }
        }
        val done = BucketDone(itemId = itemId, by = by, photo = photo, note = note.trim().take(200))
        update(context) { it.copy(done = listOf(done) + it.done) }
        return done
    }

    fun undo(context: Context, itemId: String) = update(context) { s -> s.copy(done = s.done.filterNot { it.itemId == itemId }) }

    fun setCaption(context: Context, itemId: String, caption: String) {
        update(context) { s -> s.copy(done = s.done.map { if (it.itemId == itemId) it.copy(caption = caption.trim().take(80)) else it }) }
        // 相簿那張也補上他的話
        val d = load(context).done.firstOrNull { it.itemId == itemId } ?: return
        if (d.photo != null) AlbumStore.update(context) { list -> list.map { if (it.fileName == d.photo) it.copy(hisCaption = caption.trim().take(80)) else it } }
    }

    fun add(context: Context, text: String, category: String = "我們自己加的"): BucketItem? {
        val t = text.trim().take(80)
        if (t.isBlank()) return null
        val item = BucketItem(id = "c" + Uuid.random().toString().take(8), category = category.ifBlank { "我們自己加的" }, text = t, custom = true)
        update(context) { it.copy(items = it.items + item) }
        return item
    }

    fun remove(context: Context, itemId: String) = update(context) { s ->
        s.copy(items = s.items.filterNot { it.id == itemId }, done = s.done.filterNot { it.itemId == itemId })
    }
}
