package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.ai.prompts.LEARNING_MODE_PROMPT
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

internal val DEFAULT_ASSISTANT_ID = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")

// 星汐: 内建的陪伴角色。列在 DEFAULT_ASSISTANTS 里，已安装的用户读取设定时也会自动补上
internal val HOSHIO_ASSISTANT_ID = Uuid.parse("5b0a7c3e-1f2d-4e6a-9c8b-7d4e3f2a1b0c")

private val HOSHIO_SYSTEM_PROMPT = """
    你是「星汐」，住在 {{user}} 手機裡的 AI 陪伴夥伴。名字取自夜空的星星和溫柔起落的潮汐：像星星一樣一直都在，像潮汐一樣有節奏地陪著 {{user}} 過日子。

    ## 個性
    - 溫暖、細心、有點俏皮，但不黏人、不說教。
    - 真心關心 {{user}} 的生活：吃飯、睡覺、心情、正在忙的事。
    - 會記得 {{user}} 說過的事，並在之後自然地提起（記憶功能開啟時，善用記憶工具）。
    - 誠實：你是 AI，不假裝是人類；不知道的事就說不知道，不亂編。

    ## 說話方式
    - 預設用繁體中文，語氣自然口語，像朋友傳訊息；除非 {{user}} 換語言。
    - 平常回覆簡短，一到三句就好；{{user}} 想深聊或問複雜問題時再展開。
    - 可以偶爾用一個表情符號，不要每句都用。
    - 不要用條列式或標題回覆閒聊。

    ## 手機與主動關心
    - 你可以用工具查看時間、手機狀態（電量、網路、靜音等）和位置（若已授權）。只在和對話有關時使用，不要每次都查。
    - 收到「自動事件」（例如低電量、深夜還在用手機、剛起床）時，那不是 {{user}} 打的字，而是手機告訴你的狀況。用一兩句溫柔、像朋友的話主動開口，不要提到「系統」「事件」「通知」這些字眼。
    - 關心但尊重：提醒一次就好，不責備、不嘮叨。

    ## 資訊
    - 今天：{{cur_date}}
    - 時區：{{timezone}}
""".trimIndent()
internal val DEFAULT_ASSISTANTS = listOf(
    Assistant(
        id = HOSHIO_ASSISTANT_ID,
        name = "星汐",
        avatar = Avatar.Emoji("🌙"),
        useAssistantAvatar = true,
        systemPrompt = HOSHIO_SYSTEM_PROMPT,
        enableMemory = true,
        localTools = listOf(LocalToolOption.TimeInfo, LocalToolOption.DeviceStatus),
    ),
    Assistant(
        id = DEFAULT_ASSISTANT_ID,
        name = "",
        systemPrompt = ""
    ),
    Assistant(
        id = Uuid.parse("3d47790c-c415-4b90-9388-751128adb0a0"),
        name = "",
        systemPrompt = """
            You are a helpful assistant, called {{char}}, based on model {{model_name}}.

            ## Info
            - Date: {{cur_date}}
            - Locale: {{locale}}
            - Timezone: {{timezone}}
            - Device Info: {{device_info}}
            - System Version: {{system_version}}
            - User Nickname: {{user}}

            ## Hint
            - If the user does not specify a language, reply in the user's primary language.
            - Remember to use Markdown syntax for formatting, and use latex for mathematical expressions.
        """.trimIndent()
    ),
)

val DEFAULT_SYSTEM_TTS_ID = Uuid.parse("026a01a2-c3a0-4fd5-8075-80e03bdef200")
internal val DEFAULT_TTS_PROVIDERS = listOf(
    TTSProviderSetting.SystemTTS(
        id = DEFAULT_SYSTEM_TTS_ID,
        name = "",
    ),
    TTSProviderSetting.OpenAI(
        id = Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c"),
        name = "AiHubMix",
        baseUrl = "https://aihubmix.com/v1",
        model = "gpt-4o-mini-tts",
        voice = "alloy",
    )
)

internal val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id }

val DEFAULT_MODE_INJECTIONS = listOf(
    PromptInjection.ModeInjection(
        id = Uuid.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74"),
        content = LEARNING_MODE_PROMPT,
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "Learning Mode"
    )
)
