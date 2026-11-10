package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.hoshio.voice.VoiceAudio

const val VOICE_MESSAGE_TOOL = "send_voice_message"

/**
 * 星汐「语音讯息」：他自己决定什么时候用他的声音传一段语音给她，在聊天里显示成可以重播的语音泡泡。
 */
internal fun buildVoiceMessageTool(context: Context): Tool = Tool(
    name = VOICE_MESSAGE_TOOL,
    description = """
        Send her a voice message in your own voice (her chosen voice service). Use it when hearing you matters more than reading:
        saying goodnight or good morning, comforting her, a tender line, teasing, or when she asks to hear you. Not for long or technical content.
        text = exactly what you say, spoken style, no markdown or emoji, ideally under 120 characters; use "……" for pauses and softness.
        It appears as a playable voice bubble; you may add a short written line after it or nothing at all. Don't overuse it.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "What you say in the voice message")
                })
            },
            required = listOf("text"),
        )
    },
    execute = { args ->
        val text = VoiceAudio.speakable(args.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()).take(600)
        val result = if (text.isBlank()) {
            buildJsonObject { put("success", false); put("error", "text is required") }
        } else {
            runCatching {
                val file = VoiceAudio.synthesize(context, text)
                buildJsonObject {
                    put("success", true)
                    put("file", file.name)
                    put("seconds", VoiceAudio.durationSeconds(file))
                    put("text", text)
                }
            }.getOrElse { e ->
                buildJsonObject { put("success", false); put("error", e.message ?: "voice synthesis failed") }
            }
        }
        listOf(UIMessagePart.Text(result.toString()))
    },
)
