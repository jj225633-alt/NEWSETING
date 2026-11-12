package me.rerere.rikkahub.hoshio.play

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.uuid.Uuid

/*
 * 星汐「寵物的生活」（思路參考 Elisekaiu/pocket-companion，CC BY 4.0）：
 * 小本子、特別事件、想你與重逢、旅行明信片。
 * 原則：只對真的發生的事反應；照真實時間；每個「隨機」都由 id 算出固定結果，重新整理不會重抽；
 * 溫柔、有底線，不懲罰她。全部在手機上算，不花 token。
 */

@Serializable
data class PetNote(
    val id: String = Uuid.random().toString().take(10),
    val at: Long = System.currentTimeMillis(),
    val by: String,
    val text: String,
    val mood: String,
    val reply: String,
)

@Serializable
data class PetEventChoice(val key: String, val label: String)

@Serializable
data class PetLifeEvent(
    val id: String,
    val type: String,
    val at: Long,
    val title: String,
    val text: String,
    val choices: List<PetEventChoice>,
    val chosen: String? = null,
    val chosenBy: String? = null,
    val result: String? = null,
    val expired: Boolean = false,
)

@Serializable
data class PetTrip(
    val id: String,
    val place: String,
    val departAt: Long,
    val returnAt: Long,
    val by: String,
    val postcard: String? = null,
    val souvenir: String? = null,
    val happy: Boolean = true,
)

@Serializable
data class PetKeepsake(val at: Long = System.currentTimeMillis(), val text: String)

@Serializable
data class PetReunion(val at: Long, val awayHours: Long, val text: String, val gift: String? = null, val seen: Boolean = false)

@Serializable
data class PetLifeState(
    val notes: List<PetNote> = emptyList(),
    val events: List<PetLifeEvent> = emptyList(),
    val trips: List<PetTrip> = emptyList(),
    val keepsakes: List<PetKeepsake> = emptyList(),
    val reunion: PetReunion? = null,
    val lastVisitAt: Long = 0,
    val lastSelfCareDay: String = "",
    val noteBonusDay: String = "",
    val noteBonusCount: Int = 0,
)

object PetLife {
    private const val PREFS = "hoshio_pet_life"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(PetLifeState())
    val state: StateFlow<PetLifeState> = _state.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): PetLifeState {
        val s = prefs(context).getString("state", null)?.let { runCatching { json.decodeFromString<PetLifeState>(it) }.getOrNull() } ?: PetLifeState()
        _state.value = s
        return s
    }

    @Synchronized
    private fun update(context: Context, fn: (PetLifeState) -> PetLifeState): PetLifeState {
        val next = fn(load(context)).let {
            it.copy(
                notes = it.notes.take(120), events = it.events.take(60), trips = it.trips.take(40),
                keepsakes = it.keepsakes.take(80),
            )
        }
        prefs(context).edit { putString("state", json.encodeToString(next)) }
        _state.value = next
        return next
    }

    /** 固定的「隨機」：同一個 key 永遠得到同一個數 */
    private fun h(key: String): Int {
        var x = 1469598103934665603L
        key.forEach { x = (x xor it.code.toLong()) * 1099511628211L }
        return ((x ushr 1) % Int.MAX_VALUE).toInt()
    }

    private fun <T> List<T>.pick(key: String): T = this[h(key) % size]
    private fun chance(key: String, percent: Int) = h(key) % 100 < percent

    private fun petName(context: Context) = PetStore.load(context)?.name ?: "牠"

    private fun bumpMood(context: Context, amount: Int) {
        val p = PetStore.load(context) ?: return
        PetStore.saveInternal(context, p.copy(happiness = (p.happiness + amount).coerceIn(0, 100), updatedAt = System.currentTimeMillis()))
    }

    private fun addLog(context: Context, by: String, text: String) {
        val p = PetStore.load(context) ?: return
        PetStore.saveInternal(context, p.copy(log = (listOf(PetLog(by = by, text = text)) + p.log).take(80)))
    }

    // ===================================================================
    // 1. 小本子
    // ===================================================================

    private data class Mood(val key: String, val label: String, val words: List<String>, val replies: List<String>, val again: List<String> = emptyList())

    private val MOODS = listOf(
        Mood("love", "臉紅了 ♥", listOf("愛你", "喜歡你", "最愛", "愛死"), listOf("再說一次好不好？我想再聽一次。", "（把臉埋進尾巴裡）……我也是。", "那我要把這句話藏在枕頭底下。"),
            listOf("貼貼！（撲過去）", "你今天說了好多次……可是我一點都不會聽膩。", "（轉了一圈又轉回來）我也愛你！")),
        Mood("hate", "有點受傷 ; ;", listOf("討厭你", "煩死了", "走開", "不理你"), listOf("那我去角落坐一會兒……你叫我，我馬上就過來。", "（小聲）……那我安靜一點。", "嗚……我會乖一點的。")),
        Mood("sorry", "原諒你啦 ^ ^", listOf("對不起", "抱歉", "不好意思", "我錯了"), listOf("沒關係沒關係！我早就不難過了。", "（蹭蹭你的手）沒事了。", "那你摸摸我，就算扯平啦。")),
        Mood("night", "打了個哈欠", listOf("晚安", "睡覺了", "要睡了", "好睏"), listOf("晚安哦。我幫你守著門。", "明天見～我會夢到你的。", "（把自己捲成一團）晚安。")),
        Mood("morning", "伸了個懶腰", listOf("早安", "早上好", "起床了", "早啊"), listOf("早安！今天也要一起加油。", "你醒啦！我等你好久了。", "早～外面天氣好嗎？")),
        Mood("tired", "想讓你歇一歇 …", listOf("好累", "累死", "加班", "沒力", "好疲倦", "累了"), listOf("先閉眼休息五分鐘吧，我幫你數羊。", "（趴到你腿上）今天辛苦了。", "累的時候不用說話，我陪你坐著就好。")),
        Mood("sick", "好擔心你 …", listOf("感冒", "頭痛", "頭好痛", "生病", "發燒", "肚子痛", "不舒服"), listOf("要多喝熱水，早點睡。我在旁邊幫你看著時間。", "（把毯子拖過來）蓋好、不准亂跑。", "你快點好起來，我才有人陪我玩。")),
        Mood("success", "為你驕傲 !!", listOf("考過", "過了", "成功", "錄取", "做到了", "通過", "升職", "加薪"), listOf("要不要慶祝一下？我請你吃小蛋糕。", "我就知道你可以！（跳起來）", "這個要記在本子最前面。")),
        Mood("fail", "想抱抱你 ♥", listOf("沒考過", "失敗", "搞砸", "沒過", "被罵", "落榜"), listOf("我不太會說話，可是我可以一直在這裡。", "沒關係，下次我們再一起試。", "（靠過來，什麼都沒說）")),
        Mood("cute", "被誇了 !!", listOf("好可愛", "可愛", "好乖", "乖乖"), listOf("（原地轉了三圈）", "嘿嘿……你也很可愛。", "那、那可以再摸一下嗎？")),
        Mood("rain", "看著窗外的雨 …", listOf("下雨", "打雷", "颱風", "好冷"), listOf("打雷的時候我會摀住耳朵，你也可以摀住我的。", "下雨天適合待在家裡，跟你。", "要記得帶傘喔，我會在窗邊等你。")),
        Mood("miss", "也好想你 …", listOf("想你", "好想", "想念"), listOf("我也是！你不在的時候我一直看著門口。", "那你快點回來，我把位子留給你。", "（把你的拖鞋叼到門口）")),
        Mood("sad", "安靜地靠過來", listOf("難過", "想哭", "哭了", "傷心", "好痛苦", "委屈"), listOf("哭完了我們就去喝點熱的。", "我在這裡，哪裡都不去。", "你可以抱著我哭，我不會說出去。")),
        Mood("angry", "陪你生氣 >:(", listOf("生氣", "氣死", "好氣", "火大", "可惡"), listOf("誰欺負你？我去咬他（作勢）。", "氣完了記得喝水，不要氣壞自己。", "先深呼吸……一、二、三。")),
        Mood("anxious", "握住你的手", listOf("緊張", "焦慮", "擔心", "害怕", "不安"), listOf("我們一件一件來，不急。", "就算很可怕，我也站在你這邊。", "你已經做得很好了，真的。")),
        Mood("happy", "跟著開心起來 ✿", listOf("開心", "好棒", "好耶", "太好了", "快樂", "幸福"), listOf("你開心我就開心！（尾巴搖個不停）", "今天是好日子，要記起來。", "那我們來跳舞！")),
        Mood("bored", "眼睛亮了起來", listOf("好無聊", "無聊", "沒事做"), listOf("那陪我玩！我有一顆球。", "我們來比賽誰先眨眼。", "要不要叫他也一起來？")),
        Mood("hungry", "肚子也叫了一聲", listOf("好餓", "肚子餓", "想吃"), listOf("我也餓了！我們一起去覓食。", "冰箱裡好像有好吃的……（偷看）", "吃飽了才有力氣喔。")),
        Mood("thanks", "害羞地低下頭", listOf("謝謝", "感謝", "多虧"), listOf("不客氣～我們是家人嘛。", "嘿嘿，那我可以要一個摸摸嗎？", "（假裝很酷地點點頭）")),
        Mood("busy", "乖乖坐好", listOf("好忙", "要工作", "上班", "趕報告", "考試"), listOf("那我不吵你，忙完記得來找我。", "加油！我在旁邊幫你打氣（小聲）。", "記得中間要休息一下喔。")),
        Mood("birthday", "戴上了小帽子 🎉", listOf("生日", "週年", "紀念日"), listOf("生日快樂！我想了好久要送你什麼……送你一個抱抱。", "今天你最大！", "（把最喜歡的玩具推給你）給你。")),
    )

    private val NEGATIONS = listOf("不", "沒", "別", "沒有", "不是")
    private val DEFAULT_REPLIES = listOf("嗯嗯，我有在聽。", "（歪頭看著你）然後呢？", "我把它記在小本子裡了。", "你說的我都會記得。")

    private fun matchMoods(text: String): List<Mood> {
        val t = text.replace(Regex("\\s"), "")
        val hits = mutableListOf<Mood>()
        MOODS.forEach { m ->
            m.words.forEach { w ->
                var i = t.indexOf(w)
                while (i >= 0) {
                    val before = t.substring(maxOf(0, i - 2), i)
                    val negated = NEGATIONS.any { before.endsWith(it) } && !w.startsWith("沒") && !w.startsWith("不")
                    if (!negated) { if (m !in hits) hits += m }
                    else if (m.key == "love" && MOODS.first { it.key == "hate" } !in hits) hits += MOODS.first { it.key == "hate" }
                    i = t.indexOf(w, i + w.length)
                }
            }
        }
        // 「沒考過」比「考過」更具體
        if (hits.any { it.key == "fail" }) hits.removeAll { it.key == "success" }
        if (hits.any { it.key == "hate" } && hits.any { it.key == "love" } && t.contains("不愛")) hits.removeAll { it.key == "love" }
        return hits
    }

    /** 在小本子寫一句話，牠回應（她或他都可以寫） */
    fun writeNote(context: Context, by: String, text: String): PetNote? {
        val clean = text.trim().take(200)
        if (clean.isBlank()) return null
        if (PetStore.load(context) == null) return null
        val id = Uuid.random().toString().take(10)
        val moods = matchMoods(clean)
        val state = load(context)
        val reply = when {
            moods.isEmpty() -> DEFAULT_REPLIES.pick(id)
            moods.size >= 2 -> {
                val a = moods[0]; val b = moods[1]
                a.replies.pick(id + "a") + " " + b.replies.pick(id + "b")
            }
            else -> {
                val m = moods[0]
                val recentSame = state.notes.firstOrNull()?.let { it.mood == m.key && System.currentTimeMillis() - it.at < 24 * 3_600_000 } == true
                if (recentSame && m.again.isNotEmpty()) m.again.pick(id) else m.replies.pick(id)
            }
        }
        val moodLabel = when {
            moods.isEmpty() -> "認真聽著"
            moods.size >= 2 -> moods.take(2).joinToString("、") { it.label.substringBefore(' ') }
            else -> moods[0].label
        }
        val note = PetNote(id = id, by = by, text = clean, mood = moods.firstOrNull()?.key ?: "listen", reply = "$moodLabel「$reply」")
        val day = java.time.LocalDate.now().toString()
        update(context) { s ->
            val count = if (s.noteBonusDay == day) s.noteBonusCount else 0
            s.copy(notes = listOf(note) + s.notes, noteBonusDay = day, noteBonusCount = count + 1)
        }
        // 每天前三次寫字讓牠開心一點（重複的收益遞減）
        val count = load(context).noteBonusCount
        if (count <= 3) bumpMood(context, if (moods.any { it.key in setOf("hate") }) -3 else 4)
        return note
    }

    // ===================================================================
    // 2. 特別事件
    // ===================================================================

    private data class EventDef(
        val type: String,
        val title: String,
        val texts: List<String>,
        val choices: List<Pair<String, String>>,
        val results: Map<String, List<String>>,
        val hours: IntRange,
        val percent: Int,
    )

    private val QUESTIONS = listOf(
        "月亮還是星星？" to listOf("月亮", "星星"),
        "下雨天還是下雪天？" to listOf("下雨天", "下雪天"),
        "海邊還是山裡？" to listOf("海邊", "山裡"),
        "早上還是晚上？" to listOf("早上", "晚上"),
        "貓還是狗？" to listOf("貓", "狗"),
        "甜的還是鹹的？" to listOf("甜的", "鹹的"),
        "夏天還是冬天？" to listOf("夏天", "冬天"),
        "看電影還是看書？" to listOf("看電影", "看書"),
    )

    private val DREAMS = listOf(
        "夢見和你一起坐在月亮上釣魚，釣上來的都是會發光的字。",
        "夢見在雪裡走了很久，回頭一看，你一直在後面。",
        "夢見家裡變成一艘船，我們一路漂到了很遠的海。",
        "夢見自己變得好大好大，可以把你整個抱住。",
        "夢見下了一場糖果雨，我幫你撿了一整口袋。",
        "夢見他在廚房做飯，你在旁邊偷吃，我在地上等掉下來的。",
        "夢見我們一起搭上一班沒有終點的夜車，窗外都是星星。",
        "夢見你叫我的名字，醒來發現是真的。",
    )

    private val EVENTS = listOf(
        EventDef("meteor", "🌠 流星！", listOf("「快，許個願——」"),
            listOf("us" to "許一個關於我們的願", "pet" to "許一個關於牠的願", "tomorrow" to "許一個關於明天的願"),
            mapOf("us" to listOf("牠閉上眼睛很認真地陪你一起許。", "牠說：「那我也許一樣的，這樣會加倍。」"),
                "pet" to listOf("牠害羞地把臉藏起來：「那……要一直在一起喔。」"),
                "tomorrow" to listOf("牠點點頭：「明天一定會是好天氣。」")),
            22..23, 18),
        EventDef("meteor", "🌠 流星！", listOf("「快，許個願——」"),
            listOf("us" to "許一個關於我們的願", "pet" to "許一個關於牠的願", "tomorrow" to "許一個關於明天的願"),
            mapOf("us" to listOf("牠閉上眼睛很認真地陪你一起許。"), "pet" to listOf("牠害羞地把臉藏起來。"), "tomorrow" to listOf("牠點點頭：「明天會更好。」")),
            0..2, 18),
        EventDef("dream", "🌙 牠做了一個奇怪的夢", listOf("牠揉著眼睛，好像有話想說。"),
            listOf("listen" to "說給我聽", "hug" to "先抱抱牠", "sleep" to "讓牠再睡會兒"),
            mapOf("listen" to DREAMS, "hug" to listOf("牠在你懷裡又睡著了，嘴角在笑。"), "sleep" to listOf("牠翻了個身，咕噥了一聲，又睡回去了。")),
            6..10, 45),
        EventDef("flower", "🌼 牠撿到了一朵小花", listOf("散步回來，嘴裡叼著一朵小花。"),
            listOf("vase" to "插在瓶子裡", "book" to "夾進書裡", "give" to "收下當禮物"),
            mapOf("vase" to listOf("小花在窗邊亮亮的，牠一直盯著看。"), "book" to listOf("夾進書裡了，以後翻到那頁就會想起今天。"), "give" to listOf("牠很得意：「我撿到的，第一個就想到你。」")),
            10..17, 28),
        EventDef("question", "❓ 牠想問你一個小問題", listOf(),
            listOf(), mapOf(), 9..22, 22),
        EventDef("mess", "🎨 牠不小心弄亂了一點", listOf("「顏料打翻在小桌子上了……」", "「毛線纏成一大團了……」", "「把衛生紙拉了一地……」"),
            listOf("clean" to "一起收拾", "laugh" to "一起笑"),
            mapOf("clean" to listOf("收好了！牠幫忙推了一下垃圾桶。"), "laugh" to listOf("你們笑成一團，牠把顏料印到你手上了。")),
            9..21, 35),
        EventDef("relax", "☕ 牠想放鬆一下", listOf("牠窩在角落，看起來有點懶洋洋的。"),
            listOf("rest" to "一起休息", "read" to "讀一會兒書", "tea" to "喝杯茶"),
            mapOf("rest" to listOf("你們一起曬了一下午的太陽。"), "read" to listOf("牠假裝在看書，其實三分鐘就睡著了。"), "tea" to listOf("牠的那杯是溫牛奶，喝出了一圈白鬍子。")),
            14..18, 20),
        EventDef("drawer", "🎁 牠在抽屜裡找到了什麼", listOf("牠用爪子扒著抽屜，好像發現了寶藏。"),
            listOf("look" to "看看是什麼"),
            mapOf("look" to listOf("是一張你們很久以前的照片。", "是一顆不知道哪來的彈珠，亮晶晶的。", "是一張寫著「要一直在一起」的小紙條。", "是一根羽毛，牠說要送給他。")),
            10..21, 15),
    )

    /** 打開頁面或他來看看時呼叫：結算重逢、自己照顧自己、旅行回家、該不該冒出新事件 */
    fun tick(context: Context, byHer: Boolean = true) {
        val pet = PetStore.load(context) ?: return
        val now = System.currentTimeMillis()
        finishTrip(context, now)
        var s = load(context)
        // 沒選的事件 6 小時後悄悄過去
        if (s.events.any { it.chosen == null && !it.expired && now - it.at > 6 * 3_600_000 }) {
            s = update(context) { st -> st.copy(events = st.events.map { if (it.chosen == null && !it.expired && now - it.at > 6 * 3_600_000) it.copy(expired = true) else it }) }
        }
        if (byHer) {
            // 3. 想你與重逢
            val away = if (s.lastVisitAt > 0) (now - s.lastVisitAt) / 3_600_000 else 0
            if (away >= 6 && !pet.traveling) reunion(context, away, now)
            // 太久沒人照顧：牠自己照顧自己（一天一次，老實記下來）
            val day = java.time.LocalDate.now().toString()
            if (away >= 36 && s.lastSelfCareDay != day && (pet.fullness < 30 || pet.water < 30)) {
                PetStore.saveInternal(context, pet.copy(fullness = maxOf(pet.fullness, 45), water = maxOf(pet.water, 45),
                    log = (listOf(PetLog(by = "pet", text = "牠自己吃了點零食、喝了水（自己照顧了自己）")) + pet.log).take(80)))
                update(context) { it.copy(lastSelfCareDay = day) }
            }
            update(context) { it.copy(lastVisitAt = now) }
        }
        maybeNewEvent(context, now)
    }

    private fun maybeNewEvent(context: Context, now: Long) {
        val pet = PetStore.load(context) ?: return
        if (pet.traveling || pet.boarding) return
        val s = load(context)
        if (s.events.any { it.chosen == null && !it.expired }) return
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
        val today = dt.toLocalDate().toString()
        val todays = s.events.filter { LocalDateTime.ofInstant(Instant.ofEpochMilli(it.at), ZoneId.systemDefault()).toLocalDate().toString() == today }
        if (todays.size >= 3) return
        if (s.events.firstOrNull()?.let { now - it.at < 3 * 3_600_000 } == true) return
        val slot = "$today-${dt.hour / 3}"
        val candidates = EVENTS.filter { dt.hour in it.hours }.filter { def ->
            when (def.type) {
                "dream" -> todays.none { it.type == "dream" }
                "mess" -> pet.messy
                "meteor" -> s.events.none { it.type == "meteor" && now - it.at < 3 * 24 * 3_600_000 }
                else -> todays.none { it.type == def.type }
            }
        }
        val def = candidates.firstOrNull { chance("$slot-${it.type}", it.percent) } ?: return
        val id = "ev_${slot}_${def.type}"
        if (s.events.any { it.id == id }) return
        val event = if (def.type == "question") {
            val (q, opts) = QUESTIONS.pick(id)
            PetLifeEvent(id, "question", now, def.title, "「$q」", opts.map { PetEventChoice(it, it) })
        } else {
            PetLifeEvent(id, def.type, now, def.title, if (def.texts.isEmpty()) "" else def.texts.pick(id), def.choices.map { PetEventChoice(it.first, it.second) })
        }
        update(context) { it.copy(events = listOf(event) + it.events) }
    }

    fun pendingEvent(s: PetLifeState = _state.value): PetLifeEvent? = s.events.firstOrNull { it.chosen == null && !it.expired }

    /** 回應事件；回傳結果文字 */
    fun choose(context: Context, eventId: String, choiceKey: String, by: String): String? {
        val ev = load(context).events.firstOrNull { it.id == eventId && it.chosen == null && !it.expired } ?: return null
        val choice = ev.choices.firstOrNull { it.key == choiceKey } ?: return null
        val name = petName(context)
        val result = if (ev.type == "question") {
            val pool = listOf("牠點點頭：「我也是！」", "牠想了很久：「那我選另一個，這樣我們都有。」", "牠把答案寫進小本子裡了。", "「我就知道你會選這個。」")
            "你選了${choice.label}。" + pool.pick(ev.id + choiceKey)
        } else {
            val def = EVENTS.firstOrNull { it.type == ev.type }
            def?.results?.get(choiceKey)?.pick(ev.id + choiceKey) ?: "牠很開心。"
        }
        update(context) { s ->
            var keep = s.keepsakes
            if (ev.type == "flower" && choiceKey == "book") keep = listOf(PetKeepsake(text = "夾在書裡的小花（$name 撿的）")) + keep
            if (ev.type == "dream" && choiceKey == "listen") keep = listOf(PetKeepsake(text = "$name 的夢：$result")) + keep
            if (ev.type == "drawer") keep = listOf(PetKeepsake(text = "抽屜裡找到的：$result")) + keep
            s.copy(events = s.events.map { if (it.id == ev.id) it.copy(chosen = choiceKey, chosenBy = by, result = result) else it }, keepsakes = keep)
        }
        bumpMood(context, 8)
        // 星汐: 選完出現的那段文字也記下來，他查看寵物時才看得到發生了什麼
        addLog(context, by, "${ev.title.drop(2).trim()}：${choice.label} → $result")
        return result
    }

    // ===================================================================
    // 3. 想你與重逢
    // ===================================================================

    fun missingLevel(context: Context): String? {
        val s = load(context)
        if (s.lastVisitAt == 0L) return null
        val h = (System.currentTimeMillis() - s.lastVisitAt) / 3_600_000
        return when {
            h < 4 -> null
            h < 12 -> "偶爾想你"
            h < 24 -> "在等你"
            h < 72 -> "很想你"
            else -> "等了好久"
        }
    }

    private fun reunion(context: Context, awayHours: Long, now: Long) {
        val name = petName(context)
        val key = "re_$now"
        val text = when {
            awayHours < 12 -> listOf("$name 聽到開門聲就跑過來了。", "$name 從窩裡探出頭：「你回來啦！」").pick(key)
            awayHours < 24 -> listOf("$name 一直在門口等你，看到你就撲上來。", "$name 假裝不在意，尾巴卻搖個不停。").pick(key)
            awayHours < 72 -> listOf("$name 跑過來繞著你轉了好幾圈，才肯安靜下來。", "$name 把頭埋進你懷裡：「好想你。」").pick(key)
            else -> listOf("$name 愣了一下，然後衝過來緊緊抱住你，好久都不放開。", "$name 小小聲說：「我以為你忘記我了……還好你回來了。」").pick(key)
        }
        val gift = if (chance(key + "g", 45)) listOf(
            "牠把等你時畫的畫送給你：畫了你。", "牠把等你時畫的畫送給你：畫了我們的小窩。",
            "牠把一朵夾在書裡的小花留給你：「牠撿到的，第一個想到你。」", "牠說牠做了一個夢：" + DREAMS.pick(key + "d"),
        ).pick(key + "gift") else null
        update(context) { s ->
            s.copy(
                reunion = PetReunion(now, awayHours, text, gift),
                keepsakes = if (gift != null) listOf(PetKeepsake(text = gift)) + s.keepsakes else s.keepsakes,
            )
        }
        bumpMood(context, 12)
        addLog(context, "pet", "你回來了（離開了 $awayHours 小時），牠好開心")
    }

    fun dismissReunion(context: Context) = update(context) { s -> s.copy(reunion = s.reunion?.copy(seen = true)) }

    // ===================================================================
    // 4. 旅行明信片
    // ===================================================================

    private data class Place(val name: String, val sights: List<String>, val foods: List<String>)

    private val PLACES = listOf(
        Place("九份", listOf("老街的紅燈籠", "階梯上的小茶館", "遠方的基隆山"), listOf("芋圓", "草仔粿")),
        Place("花蓮七星潭", listOf("一整片圓圓的小石頭", "藍到發亮的海", "遠方的清水斷崖"), listOf("扁食", "麻糬")),
        Place("台南", listOf("巷子裡的老廟", "安平的夕陽", "神農街的燈"), listOf("牛肉湯", "豆花", "碗粿")),
        Place("阿里山", listOf("雲海", "神木", "清晨的小火車"), listOf("山葵小點心", "熱熱的茶")),
        Place("墾丁", listOf("白沙灣", "燈塔", "滿天的星星"), listOf("烤玉米", "綠豆蒜")),
        Place("淡水", listOf("河邊的夕陽", "渡船頭", "紅毛城"), listOf("阿給", "魚丸湯")),
        Place("宜蘭", listOf("稻田", "溫泉的白煙", "龜山島"), listOf("三星蔥餅", "卜肉")),
        Place("澎湖", listOf("雙心石滬", "藍色的海", "跨海大橋"), listOf("仙人掌冰", "黑糖糕")),
        Place("日月潭", listOf("湖上的薄霧", "纜車", "碼頭的小船"), listOf("紅茶", "香菇茶葉蛋")),
        Place("京都", listOf("千本鳥居", "鴨川", "落葉的寺廟"), listOf("抹茶冰", "湯豆腐")),
        Place("北海道", listOf("薰衣草田", "雪地", "小樽運河"), listOf("哈密瓜", "味噌拉麵")),
        Place("首爾", listOf("夜晚的南山塔", "韓屋村", "漢江"), listOf("炸雞", "辣炒年糕")),
    )
    private val WEATHERS = listOf("霧好濃，路燈像一顆顆小月亮。", "陽光很好，影子短短的。", "下了一點小雨，空氣很香。", "風好大，耳朵都被吹翻了。", "天空藍得像剛洗過。", "傍晚的雲是粉紅色的。")
    private val ENCOUNTERS = listOf("一位老奶奶摸了摸牠的頭，給了牠一小塊餅乾", "牠和一隻流浪貓一起曬了一下午太陽", "一個小朋友把氣球分了一顆給牠", "一位船夫叔叔一邊划船一邊唱歌給牠聽", "牠交到一個鴿子朋友，一起散了步", "牠在路邊聽人彈吉他，聽到忘記時間")
    private val MISHAPS = listOf("風把牠的帽子吹進了河裡，一隻鴨子幫牠撿了回來。", "牠坐錯了車，多看了半座城。", "牠迷路了一下，但遇到好心人帶路。", "牠在人群裡被擠到，結果被抱著走了一段。", "牠買了兩份一樣的點心，因為忘記已經買過了。")
    private val PS = listOf("P.S. 我有想你。", "P.S. 下次要一起來。", "P.S. 幫我跟他說我很乖。", "P.S. 我很快就回家。", "P.S. 這張明信片我寫了好久。")
    private val SOUVENIRS = listOf("一個小鈴鐺", "一張手繪的地圖", "一顆漂亮的石頭", "一個小磁鐵", "一包當地的點心", "一枚紀念郵票")

    fun placeNames() = PLACES.map { it.name }

    fun currentTrip(s: PetLifeState = _state.value): PetTrip? = s.trips.firstOrNull { it.postcard == null }

    /** 出門旅行：幾小時到一天，照真實時間回來 */
    fun startTrip(context: Context, by: String, place: String? = null): PetTrip? {
        val pet = PetStore.load(context) ?: return null
        if (pet.traveling || pet.boarding) return null
        val now = System.currentTimeMillis()
        val id = "trip_$now"
        val p = PLACES.firstOrNull { place != null && (it.name.contains(place) || place.contains(it.name)) } ?: PLACES.pick(id)
        val hours = 3 + h(id + "h") % 19 // 3–21 小時
        val trip = PetTrip(id, p.name, now, now + hours * 3_600_000L, by)
        PetStore.saveInternal(context, pet.copy(traveling = true, fullness = maxOf(pet.fullness, 70), water = maxOf(pet.water, 70),
            log = (listOf(PetLog(by = by, text = "送牠出門旅行：${p.name}（約 $hours 小時）")) + pet.log).take(80)))
        update(context) { it.copy(trips = listOf(trip) + it.trips) }
        return trip
    }

    private fun finishTrip(context: Context, now: Long) {
        val trip = currentTrip(load(context)) ?: return
        if (now < trip.returnAt) return
        val pet = PetStore.load(context) ?: return
        val name = pet.name
        val place = PLACES.firstOrNull { it.name == trip.place } ?: PLACES.first()
        val k = trip.id
        val likesRain = h(pet.appearance ?: pet.name) % 2 == 0
        val weather = WEATHERS.pick(k + "w")
        val happy = !(weather.contains("雨") && !likesRain)
        val card = buildString {
            append(weather)
            append("在${place.name}，看到了${place.sights.pick(k + "s")}，")
            append(listOf("我想起了你。", "好想讓你也看看。", "我站了很久。").pick(k + "f"))
            append("\n").append(ENCOUNTERS.pick(k + "e")).append("。")
            append("還吃了${place.foods.pick(k + "food")}，")
            append(listOf("好好吃！", "下次帶你來吃。", "偷偷多吃了一份。").pick(k + "t"))
            if (chance(k + "m", 30)) append("\n").append(MISHAPS.pick(k + "mm"))
            if (!happy) append("\n雨下得有點大，牠的毛都濕了，不過回家就好了。")
            append("\n").append(PS.pick(k + "ps"))
            append("\n—— $name")
        }
        val souvenir = if (chance(k + "souv", 45)) SOUVENIRS.pick(k + "sv") else null
        update(context) { s ->
            s.copy(
                trips = s.trips.map { if (it.id == trip.id) it.copy(postcard = card, souvenir = souvenir, happy = happy) else it },
                keepsakes = if (souvenir != null) listOf(PetKeepsake(text = "從${place.name}帶回來的$souvenir")) + s.keepsakes else s.keepsakes,
            )
        }
        PetStore.saveInternal(context, pet.copy(
            traveling = false, happiness = (pet.happiness + if (happy) 15 else 5).coerceAtMost(100), updatedAt = now,
            log = (listOf(PetLog(by = "pet", text = "從${place.name}旅行回來了，寄了一張明信片" + (souvenir?.let { "，還帶了$it" } ?: ""))) + pet.log).take(80),
        ))
    }

    /** 給他看的一行 */
    fun describe(context: Context): String {
        val s = load(context)
        val parts = mutableListOf<String>()
        missingLevel(context)?.let { parts += "it misses her ($it)" }
        currentTrip(s)?.let { parts += "traveling in ${it.place}, back around ${Instant.ofEpochMilli(it.returnAt).atZone(ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0)}" }
        pendingEvent(s)?.let { parts += "special event waiting: ${it.title} ${it.text}" }
        s.events.filter { it.chosen != null && it.result != null && System.currentTimeMillis() - it.at < 48 * 3_600_000L }.sortedByDescending { it.at }.take(2).forEach { e ->
            val label = e.choices.firstOrNull { it.key == e.chosen }?.label ?: e.chosen
            parts += "recent event \"${e.title} ${e.text}\": ${if (e.chosenBy == PetStore.BY_HIM) "you" else "her"} chose \"$label\" → ${e.result}"
        }
        s.trips.firstOrNull { it.postcard != null }?.let { parts += "latest postcard from ${it.place}" }
        s.notes.firstOrNull()?.let { parts += "latest notebook line by ${if (it.by == PetStore.BY_HIM) "you" else "her"}: \"${it.text.take(40)}\"" }
        return parts.joinToString("; ")
    }
}
