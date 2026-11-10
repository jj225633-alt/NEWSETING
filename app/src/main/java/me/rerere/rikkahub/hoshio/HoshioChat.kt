package me.rerere.rikkahub.hoshio

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * 星汐: 在页面或服务里叫他一声——找到目前助手最近的对话，放一则提示进去，等他回完。
 */
object HoshioChat : KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val conversationRepository: ConversationRepository by inject()
    private val chatService: ChatService by inject()

    data class Reply(val conversationId: String, val assistantName: String, val text: String)

    suspend fun latestConversation(): Pair<Conversation, String>? {
        val settings = settingsStore.awaitLoaded()
        val assistantId = settings.assistantId
        val name = settings.assistants.firstOrNull { it.id == assistantId }?.name?.ifBlank { null } ?: "時澈"
        val conversation = conversationRepository.getRecentConversations(assistantId, limit = 1).firstOrNull() ?: return null
        return conversation to name
    }

    /** 最新版本的对话（正在生成时也包含记忆体里最新的讯息） */
    suspend fun fresh(conversation: Conversation): Conversation = chatService.getConversation(conversation.id) ?: conversation

    /** 他正在回别的话时回传 true */
    suspend fun isBusy(conversation: Conversation): Boolean =
        chatService.getGenerationJobStateFlow(conversation.id).first() != null

    /** 只放一则提示、不等他回；回传对话 id（没有对话时回传 null） */
    suspend fun post(title: String, text: String): kotlin.uuid.Uuid? {
        val (conversation, _) = latestConversation() ?: return null
        chatService.postNotice(
            conversationId = conversation.id,
            key = "hoshio_post:${System.currentTimeMillis()}",
            notice = UIMessagePart.Notice(title = title, text = text),
        )
        return conversation.id
    }

    /**
     * 放一则提示并等他回完（最多 [timeoutMinutes] 分钟）。
     * 没有对话时回传 null；他没说话（只用工具或逾时）时 text 为空字串。
     */
    suspend fun ask(title: String, text: String, timeoutMinutes: Long = 5): Reply? {
        val (conversation, name) = latestConversation() ?: return null
        return askIn(conversation.id, conversation.currentMessages.size, name, title, text, timeoutMinutes)
    }

    /** 开一个新对话，放一则提示进去（新对话会成为最近的那个），等他回完 */
    suspend fun askInNewConversation(title: String, text: String, timeoutMinutes: Long = 5): Reply {
        val settings = settingsStore.awaitLoaded()
        val name = settings.assistants.firstOrNull { it.id == settings.assistantId }?.name?.ifBlank { null } ?: "時澈"
        return askIn(kotlin.uuid.Uuid.random(), 0, name, title, text, timeoutMinutes)
    }

    private suspend fun askIn(
        conversationId: kotlin.uuid.Uuid,
        before: Int,
        name: String,
        title: String,
        text: String,
        timeoutMinutes: Long,
    ): Reply {
        chatService.postNotice(
            conversationId = conversationId,
            key = "hoshio_ask:${System.currentTimeMillis()}",
            notice = UIMessagePart.Notice(title = title, text = text),
        )
        val jobs = chatService.getGenerationJobStateFlow(conversationId)
        withTimeoutOrNull(TimeUnit.MINUTES.toMillis(timeoutMinutes)) {
            withTimeoutOrNull(30_000) { jobs.filterNotNull().first() }
            jobs.first { it == null }
        }
        val reply = chatService.getConversation(conversationId)
            ?.currentMessages
            ?.drop(before)
            ?.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.toText()
            ?.trim()
            .orEmpty()
        return Reply(conversationId.toString(), name, reply)
    }
}
