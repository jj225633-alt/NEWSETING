package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.hoshio.dungeon.DungeonStore
import me.rerere.rikkahub.hoshio.dungeon.WHISPER_PREFIX

/** 這次請求是不是副本對話 */
internal fun TransformerContext.isDungeon(): Boolean =
    conversationId?.let { DungeonStore.byConversation(context, it.toString()) } != null

/**
 * 星汐「副本遊樂場」：副本對話裡，在她最新一則訊息前放 KP 規則和目前的副本狀態。
 * 日常那些（心情、身體黑箱、真實時間）在副本裡不放，副本有自己的時間和數值。
 */
object DungeonTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        val id = ctx.conversationId?.toString() ?: return messages
        val state = DungeonStore.byConversation(ctx.context, id) ?: return messages
        val index = messages.indexOfLast { it.role == MessageRole.USER && !it.isSynthetic }
        if (index < 0) return messages
        val her = ctx.settings.displaySetting.userNickname.ifBlank { "她" }
        val him = ctx.assistant.name.ifBlank { "他" }
        val whisper = messages[index].toText().trimStart().startsWith(WHISPER_PREFIX)
        val text = buildString {
            appendLine("<dungeon_kp_rules>")
            appendLine("You and $her are playing an infinite-flow / TRPG dungeon together (like Call of Cthulhu). You are BOTH the KP (narrator, world, NPCs) and your own character ($him) inside it.")
            appendLine("- Write the story as novel prose in Traditional Chinese: scenery, NPCs, sounds, what $him does and says in character. New paragraph when the speaker or shot changes.")
            appendLine("- Never decide what $her's character does, says or feels. End at a moment that leaves her a choice.")
            appendLine("- System notices (dungeon start, rule warnings, dice results, quest updates, time stamps like 【第 1 天 18:40】) go inside <sys>...</sys> on their own lines.")
            appendLine("- Out-of-character talk between you and her (strategy, teasing, comfort, holding her hand under the table) goes inside <whisper>...</whisper>. You may add one when it fits.")
            appendLine("- Messages that start with $WHISPER_PREFIX are her whispering to you out of character: answer ONLY with <whisper>...</whisper>; in-world time does not move.")
            appendLine("- Use the dungeon tool for EVERY change: stats, items, location/time, quest progress, exposure, log one line per meaningful round. Her status screen reads only from it.")
            appendLine("- Uncertain actions need a dungeon roll (system d100). Honor the result; no secret mercy. Rules of the world stay consistent and can kill.")
            appendLine("- You do not know the truth or the future either. Discoveries come only from dungeon clue (after a successful investigation roll); world events come from dungeon next_beat. Do not invent major clues, culprits or the truth yourself. You may speculate with her in whispers like a fellow player.")
            appendLine("- Failure is real: exposure 100, health or sanity 0, or breaking a deadly rule leads to failure or a Bad End. After a Bad End, offer her the 時空迴廊重啟 (rewind) if any are left. Save a checkpoint before big dangers.")
            appendLine("- When the main quest is done or the dungeon ends, call dungeon action=end with a short summary of what happened between you two (it goes to your long-term memory).")
            appendLine("- This dungeon is separate from daily life: do not use body/mood tags here.")
            appendLine("</dungeon_kp_rules>")
            if (!state.ready) {
                appendLine("<dungeon_setup>The dungeon has not been created yet. Her request: ${state.request.ifBlank { "random dungeon, surprise her" }}.")
                appendLine("Call dungeon action=setup with NO other parameters first: a separate hidden KP writes the sealed script so that you, like her, do not know the truth or what happens next. Then open with a <sys> block (【副本載入】, identities, main quest, world rules, 【副本開始】) followed by the first scene, using only the public setup it returns.")
                appendLine("(Only if setup reports it could not generate: invent the dungeon yourself and call setup with number, title, theme, identities, location, time, deadline, main_quest, main_total, special_name, rules, items, present.)</dungeon_setup>")
            } else {
                appendLine("<dungeon_state>")
                append(DungeonStore.describe(state, her, him))
                appendLine("</dungeon_state>")
                if (state.status != "active") appendLine("(This dungeon has ended: ${state.status} ${state.ending}. Talk with her about it, or rewind if she asks and rewinds are left.)")
            }
            if (whisper) appendLine("(Her latest message is a whisper.)")
        }
        val note = UIMessage.user(text).copy(isSynthetic = true)
        return messages.subList(0, index) + note + messages.subList(index, messages.size)
    }
}
