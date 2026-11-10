package me.rerere.rikkahub.hoshio.diary

import android.content.Context
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.backgroundTextGenerationParams
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.hoshio.care.CarePrefs
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

private const val MAX_MESSAGE_CHARS = 800
private const val MAX_TRANSCRIPT_CHARS = 24_000

/**
 * 让助手依照当天的对话写一篇日记。只用当天真的发生过的对话，没聊天就不写。
 */
object DiaryWriter : KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val conversationRepository: ConversationRepository by inject()
    private val providerManager: ProviderManager by inject()

    sealed interface Result {
        data class Written(val entry: DiaryEntry) : Result
        data object NothingToWrite : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun write(context: Context, date: LocalDate): Result {
        val settings = settingsStore.awaitLoaded()
        val assistantId = CarePrefs(context).assistantId ?: settings.assistantId
        val assistant = settings.assistants.firstOrNull { it.id == assistantId }
            ?: return Result.Failed("找不到助手")
        val model = settings.findModelById(assistant.chatModelId, settings.chatModelId)
            ?: return Result.Failed("助手沒有設定聊天模型")
        val provider = model.findProvider(settings.providers) ?: return Result.Failed("找不到模型的服務商")

        val zone = ZoneId.systemDefault()
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        val nickname = settings.displaySetting.userNickname.ifBlank { "她" }
        val assistantName = assistant.name.ifBlank { "星汐" }

        // 当天的对话，依时间排好
        val lines = conversationRepository.getRecentConversations(assistantId, limit = 40)
            .flatMap { it.currentMessages }
            .filter { (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT) && !it.isNoticeOnly }
            .map { it to Instant.ofEpochMilli(it.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()).atZone(zone) }
            .filter { (_, at) -> at.toLocalDate() == date }
            .sortedBy { (_, at) -> at }
            .mapNotNull { (message, at) ->
                val text = message.toText().trim().ifBlank { return@mapNotNull null }.take(MAX_MESSAGE_CHARS)
                val who = if (message.role == MessageRole.USER) nickname else assistantName
                "[${at.format(timeFmt)}] $who：$text"
            }
        val transcript = buildString {
            for (line in lines) {
                if (length + line.length > MAX_TRANSCRIPT_CHARS) break
                appendLine(line)
            }
        }
        val dateText = date.toString()
        val humanEntries = DiaryStore.visibleToAi(context)
            .filter { it.date == dateText && it.author == DiaryAuthor.HUMAN }
        if (transcript.isBlank() && humanEntries.isEmpty()) return Result.NothingToWrite

        val persona = assistant.systemPrompt
            .replace("{{user}}", nickname)
            .replace("{{cur_date}}", dateText)
            .replace("{{timezone}}", zone.id)
        val instruction = buildString {
            appendLine("現在請你以「$assistantName」的身分，為 $dateText 寫一篇自己的日記。")
            appendLine()
            appendLine("規則：")
            appendLine("- 第一人稱，用你平常的個性和口吻，繁體中文，大約 150～400 字。")
            appendLine("- 只能寫下面真的發生過的事（對話或她的日記）；不要編造沒發生的事、沒說過的話或細節。")
            appendLine("- 可以寫你自己的感受和想法，但要讓人看得出那是你的感受，不是事實。")
            appendLine("- 重點放在今天和$nickname 之間發生的事、聊到的重要內容、她的狀態，以及之後值得記得的事。")
            appendLine("- 不要寫密碼、帳號、其他人的隱私細節。")
            appendLine("- 只輸出日記正文，不要加標題、日期或其他說明。")
            if (transcript.isNotBlank()) {
                appendLine()
                appendLine("<today_conversation>")
                append(transcript)
                appendLine("</today_conversation>")
            }
            if (humanEntries.isNotEmpty()) {
                appendLine()
                appendLine("<her_diary>")
                humanEntries.forEach { appendLine(it.content.take(2000)) }
                appendLine("</her_diary>")
            }
        }

        val result = runCatching {
            providerManager.getProviderByType(provider).generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.system(persona), UIMessage.user(instruction)),
                params = backgroundTextGenerationParams(model, Uuid.random()),
            )
        }.getOrElse { return Result.Failed(it.message ?: it.javaClass.simpleName) }

        val content = result.message.parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            .replace(Regex("<think>[\\s\\S]*?</think>"), "")
            .trim()
        if (content.isBlank()) return Result.Failed("模型沒有回傳內容")
        val entry = DiaryEntry(date = dateText, author = DiaryAuthor.AI, content = content)
        DiaryStore.add(context, entry)
        return Result.Written(entry)
    }
}
