package me.rerere.rikkahub.web.routes

import android.content.Context
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.hoshio.HoshioChat
import me.rerere.rikkahub.hoshio.diary.DiaryAuthor
import me.rerere.rikkahub.hoshio.diary.DiaryEntry
import me.rerere.rikkahub.hoshio.diary.DiaryStore
import me.rerere.rikkahub.hoshio.life.Letter
import me.rerere.rikkahub.hoshio.life.LetterStore
import me.rerere.rikkahub.hoshio.life.MoodStore
import me.rerere.rikkahub.hoshio.life.WakeLogStore
import me.rerere.rikkahub.hoshio.life.WakeRecord
import me.rerere.rikkahub.hoshio.play.PetStore
import me.rerere.rikkahub.hoshio.together.AlbumStore
import me.rerere.rikkahub.hoshio.together.BY_HER
import me.rerere.rikkahub.hoshio.together.Capsule
import me.rerere.rikkahub.hoshio.together.CapsuleStore
import me.rerere.rikkahub.hoshio.together.DailyQuestion
import me.rerere.rikkahub.hoshio.together.DailyQuestionStore
import me.rerere.rikkahub.hoshio.together.MoodCheckin
import me.rerere.rikkahub.hoshio.together.MoodCheckinStore
import me.rerere.rikkahub.hoshio.together.OurPromise
import me.rerere.rikkahub.hoshio.together.PromiseStore
import me.rerere.rikkahub.hoshio.together.RoutineLogStore
import me.rerere.rikkahub.hoshio.together.RoutineStore
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import java.time.LocalDate
import java.time.ZoneId

@Serializable
data class HoshioStateDto(val mood: String, val energy: Int, val reason: String, val updatedAt: Long)

@Serializable
data class HoshioPetDto(val name: String, val species: String, val status: String, val fullness: Int, val happiness: Int, val water: Int, val health: Int, val ageDays: Long)

@Serializable
data class HoshioRoutineDto(val id: String, val emoji: String, val label: String, val times: List<String>, val doneToday: List<Long>)

@Serializable
data class HoshioOverviewDto(
    val assistantName: String,
    val state: HoshioStateDto,
    val wakeLog: List<WakeRecord>,
    val unreadLetters: Int,
    val todayMood: MoodCheckin?,
    val moodOptions: List<List<String>>,
    val question: DailyQuestion?,
    val pet: HoshioPetDto?,
    val routines: List<HoshioRoutineDto>,
    val openPromises: Int,
)

@Serializable
data class HoshioAlbumPhotoDto(val id: String, val path: String, val herCaption: String, val hisCaption: String, val at: Long)

@Serializable
data class HoshioCapsuleDto(val id: String, val by: String, val title: String, val openOn: String, val unlocked: Boolean, val opened: Boolean, val content: String?)

@Serializable
data class HoshioDiaryDto(val entries: List<DiaryEntry>, val moods: List<MoodCheckin>)

@Serializable
data class HoshioTextRequest(val text: String)

@Serializable
data class HoshioMoodRequest(val emoji: String, val label: String, val note: String = "", val date: String? = null, val tell: Boolean = false)

@Serializable
data class HoshioDiaryRequest(val date: String, val content: String, val visibleToAi: Boolean = true)

@Serializable
data class HoshioPromiseRequest(val text: String, val by: String = "both", val due: String? = null)

@Serializable
data class HoshioCapsuleRequest(val title: String, val content: String, val openOn: String)

private val ok = mapOf("status" to "ok")

/**
 * 星汐: 网页版用的 API（他的状态、信箱、日记与心情、约定、每日一问、时光胶囊、相簿、宠物、生活照顾）。
 */
fun Route.hoshioRoutes(context: Context, settingsStore: SettingsStore) {
    route("/hoshio") {
        get("/overview") {
            val settings = settingsStore.settingsFlow.value
            val name = settings.assistants.firstOrNull { it.id == settings.assistantId }?.name?.ifBlank { null } ?: "時澈"
            val mood = MoodStore.current(context)
            val today = LocalDate.now().toString()
            val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val logs = RoutineLogStore.load(context).filter { it.at >= startOfDay }
            call.respond(
                HoshioOverviewDto(
                    assistantName = name,
                    state = HoshioStateDto(mood.mood, mood.energy, mood.reason, mood.updatedAt),
                    wakeLog = WakeLogStore.load(context).take(10),
                    unreadLetters = LetterStore.load(context).count { !it.read },
                    todayMood = MoodCheckinStore.load(context).firstOrNull { it.date == today },
                    moodOptions = MoodCheckinStore.MOODS.map { listOf(it.first, it.second) },
                    question = DailyQuestionStore.load(context).firstOrNull { it.date == today }?.let {
                        // 她还没回答时，先不给他的答案
                        if (it.herAnswer.isBlank()) it.copy(hisAnswer = "") else it
                    },
                    pet = PetStore.load(context)?.let { HoshioPetDto(it.name, it.species, it.status, it.fullness, it.happiness, it.water, it.health, it.ageDays) },
                    routines = RoutineStore.load(context).filter { it.active }.map { r ->
                        HoshioRoutineDto(r.id, r.emoji, r.label, r.times, logs.filter { it.routineId == r.id }.map { it.at })
                    },
                    openPromises = PromiseStore.load(context).count { !it.done },
                )
            )
        }

        // 信箱
        get("/letters") { call.respond(LetterStore.load(context)) }
        post("/letters/{id}/read") {
            LetterStore.markRead(context, call.parameters["id"] ?: throw BadRequestException("id"))
            call.respond(HttpStatusCode.OK, ok)
        }

        // 日记与心情
        get("/diary") { call.respond(HoshioDiaryDto(DiaryStore.load(context), MoodCheckinStore.load(context))) }
        post("/diary") {
            val req = call.receive<HoshioDiaryRequest>()
            if (req.content.isBlank()) throw BadRequestException("content is empty")
            val date = runCatching { LocalDate.parse(req.date).toString() }.getOrNull() ?: throw BadRequestException("bad date")
            DiaryStore.add(context, DiaryEntry(date = date, author = DiaryAuthor.HUMAN, content = req.content.trim(), visibleToAi = req.visibleToAi))
            call.respond(HttpStatusCode.OK, ok)
        }
        post("/mood") {
            val req = call.receive<HoshioMoodRequest>()
            val date = req.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
            MoodCheckinStore.checkIn(context, req.emoji, req.label, req.note, date)
            if (req.tell && date == LocalDate.now()) {
                HoshioChat.post(
                    "心情打卡",
                    "She just checked in her mood for today (from the computer): ${req.emoji} ${req.label}" +
                        (if (req.note.isNotBlank()) " — \"${req.note}\"" else "") + ". Respond to her in character.",
                )
            }
            call.respond(HttpStatusCode.OK, ok)
        }

        // 我们的约定
        get("/promises") { call.respond(PromiseStore.load(context)) }
        post("/promises") {
            val req = call.receive<HoshioPromiseRequest>()
            if (req.text.isBlank()) throw BadRequestException("text is empty")
            val due = req.due?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it).toString() }.getOrNull() ?: throw BadRequestException("bad date") }
            PromiseStore.update(context) { listOf(OurPromise(text = req.text.trim().take(100), by = req.by, due = due)) + it }
            me.rerere.rikkahub.hoshio.care.CareWorker.sync(context)
            call.respond(HttpStatusCode.OK, ok)
        }
        post("/promises/{id}/toggle") {
            val id = call.parameters["id"] ?: throw BadRequestException("id")
            PromiseStore.update(context) { list ->
                list.map { if (it.id == id) it.copy(done = !it.done, doneAt = if (!it.done) System.currentTimeMillis() else null) else it }
            }
            call.respond(HttpStatusCode.OK, ok)
        }

        // 每日一问
        get("/questions") {
            call.respond(DailyQuestionStore.load(context).map { if (it.herAnswer.isBlank()) it.copy(hisAnswer = "") else it })
        }
        post("/question/answer") {
            val req = call.receive<HoshioTextRequest>()
            val today = LocalDate.now().toString()
            val q = DailyQuestionStore.load(context).firstOrNull { it.date == today } ?: throw NotFoundException("No question today")
            if (req.text.isBlank()) throw BadRequestException("text is empty")
            DailyQuestionStore.update(context) { list -> list.map { if (it.id == q.id) it.copy(herAnswer = req.text.trim().take(1000)) else it } }
            HoshioChat.post(
                "每日一問",
                "She answered today's question \"${q.question}\" (from the computer): \"${req.text.trim()}\". " +
                    (if (q.hisAnswer.isBlank()) "Answer it yourself too with daily_question answer, then " else "") +
                    "react to her answer and share yours in character.",
            )
            call.respond(HttpStatusCode.OK, ok)
        }
        post("/question/ask") {
            val req = call.receive<HoshioTextRequest>()
            val today = LocalDate.now().toString()
            if (DailyQuestionStore.load(context).any { it.date == today }) throw BadRequestException("Today already has a question")
            if (req.text.isBlank()) throw BadRequestException("text is empty")
            DailyQuestionStore.update(context) { listOf(DailyQuestion(asker = BY_HER, question = req.text.trim().take(200))) + it }
            HoshioChat.post(
                "每日一問",
                "She asked you today's question (from the computer): \"${req.text.trim()}\". Answer it honestly in character, save your answer with " +
                    "daily_question answer, then ask what her own answer would be; when she tells you, save it with daily_question record_her_answer.",
            )
            call.respond(HttpStatusCode.OK, ok)
        }

        // 时光胶囊
        get("/capsules") {
            call.respond(CapsuleStore.load(context).map { c ->
                val unlocked = c.unlocked()
                HoshioCapsuleDto(c.id, c.by, c.title, c.openOn, unlocked, c.opened, if (unlocked) c.content else null)
            })
        }
        post("/capsules") {
            val req = call.receive<HoshioCapsuleRequest>()
            val date = runCatching { LocalDate.parse(req.openOn) }.getOrNull()?.takeIf { it.isAfter(LocalDate.now()) }
                ?: throw BadRequestException("openOn must be a future date")
            if (req.content.isBlank()) throw BadRequestException("content is empty")
            CapsuleStore.update(context) { listOf(Capsule(by = BY_HER, title = req.title.ifBlank { "給未來的我們" }.take(40), content = req.content, openOn = date.toString())) + it }
            me.rerere.rikkahub.hoshio.care.CareWorker.sync(context)
            call.respond(HttpStatusCode.OK, ok)
        }
        post("/capsules/{id}/open") {
            val id = call.parameters["id"] ?: throw BadRequestException("id")
            val c = CapsuleStore.load(context).firstOrNull { it.id == id } ?: throw NotFoundException("Capsule not found")
            if (!c.unlocked()) throw BadRequestException("Still sealed")
            CapsuleStore.update(context) { list -> list.map { if (it.id == id) it.copy(opened = true) else it } }
            call.respond(HttpStatusCode.OK, ok)
        }

        // 相簿（图片走 /api/files/path/hoshio_album/...）
        get("/album") {
            call.respond(AlbumStore.load(context).map { HoshioAlbumPhotoDto(it.id, "hoshio_album/${it.fileName}", it.herCaption, it.hisCaption, it.at) })
        }

        // 宠物与生活照顾
        post("/pet/{action}") {
            val action = call.parameters["action"] ?: throw BadRequestException("action")
            if (action !in listOf("feed", "water", "play", "tidy", "clean", "pet", "sleep")) throw BadRequestException("bad action")
            PetStore.care(context, action, PetStore.BY_HER) ?: throw NotFoundException("No pet")
            call.respond(HttpStatusCode.OK, ok)
        }
        post("/routines/{id}/done") {
            val id = call.parameters["id"] ?: throw BadRequestException("id")
            if (RoutineStore.load(context).none { it.id == id }) throw NotFoundException("Routine not found")
            RoutineLogStore.log(context, id, BY_HER)
            call.respond(HttpStatusCode.OK, ok)
        }
    }
}
