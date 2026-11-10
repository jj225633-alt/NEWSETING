package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.hoshio.HoshioChat
import me.rerere.rikkahub.hoshio.rules.PauseStore
import me.rerere.rikkahub.hoshio.rules.WakeRule
import me.rerere.rikkahub.hoshio.rules.WakeRules
import org.koin.java.KoinJavaComponent.getKoin
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val POWER_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm")
private fun Long.powerTime() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(POWER_FMT)

private fun int(description: String) = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

/**
 * 星汐: 给他更多自主权——自订唤醒规则、拒绝的权利、调整自己的配置、看看整个 App 的状况。
 */
internal fun buildAiPowerTools(context: Context): List<Tool> = listOf(
    Tool(
        name = "wake_rules",
        description = """
            Your own wake rules: conditions that wake you up automatically so you can check on her (you can still choose to stay silent when woken).
            action=list / add / update / remove / enable / disable. Types: daily (time HH:mm, optional window_end, weekdays 1-7),
            interval (minutes ≥ 15), chat_idle (she has not replied for minutes), app_usage (she used app - name or package part - for minutes in the last hour),
            screen_on (screen turned on after being off ≥ minutes), low_battery (value %), place (place name + place_event arrive|leave; places are set in 主動關心),
            heart_rate (value bpm, needs her watch), pc_app (app on her computer, needs 星汐電腦版).
            prompt = a note to your future self about what to do when it fires. Optional: cooldown_minutes (default 60), probability 1-100, quiet_start / quiet_end HH:mm.
            Keep rules meaningful and few; she can see and switch them off in the app (他的喚醒規則).
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", tEnum("What to do", "list", "add", "update", "remove", "enable", "disable"))
                    put("id", tStr("update / remove / enable / disable: rule id"))
                    put("name", tStr("short name"))
                    put("type", tEnum("rule type", *WakeRules.TYPES.toTypedArray()))
                    put("prompt", tStr("note to yourself"))
                    put("time", tStr("daily: HH:mm"))
                    put("window_end", tStr("daily: optional HH:mm"))
                    put("weekdays", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "integer") }) })
                    put("minutes", int("interval / chat_idle / app_usage / screen_on"))
                    put("app", tStr("app_usage / pc_app: app name or package part"))
                    put("place", tStr("place: place name"))
                    put("place_event", tEnum("place: when", "arrive", "leave"))
                    put("value", int("low_battery %, heart_rate bpm"))
                    put("cooldown_minutes", int("min minutes between firings, default 60"))
                    put("probability", int("1-100, default 100"))
                    put("quiet_start", tStr("HH:mm"))
                    put("quiet_end", tStr("HH:mm"))
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            fun arg(k: String) = obj[k]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
            fun num(k: String) = obj[k]?.jsonPrimitive?.intOrNull
            val weekdays = runCatching { obj["weekdays"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }?.filter { it in 1..7 }?.toSet() }.getOrNull()
            fun WakeRule.apply(): WakeRule = copy(
                name = arg("name")?.take(30) ?: name,
                type = arg("type")?.takeIf { it in WakeRules.TYPES } ?: type,
                prompt = arg("prompt")?.take(500) ?: prompt,
                time = arg("time") ?: time,
                windowEnd = arg("window_end") ?: windowEnd,
                weekdays = weekdays ?: this.weekdays,
                minutes = num("minutes") ?: minutes,
                app = arg("app") ?: app,
                place = arg("place") ?: place,
                placeEvent = arg("place_event") ?: placeEvent,
                value = num("value") ?: value,
                cooldownMinutes = (num("cooldown_minutes") ?: cooldownMinutes).coerceIn(5, 10_080),
                probability = (num("probability") ?: probability).coerceIn(1, 100),
                quietStart = arg("quiet_start") ?: quietStart,
                quietEnd = arg("quiet_end") ?: quietEnd,
            )
            val rules = WakeRules.load(context)
            val rule = rules.firstOrNull { it.id == arg("id") }
            when (arg("action")) {
                "add" -> {
                    val type = arg("type")
                    if (type !in WakeRules.TYPES || arg("prompt") == null) {
                        tJson("error" to "type and prompt are required")
                    } else if (rules.size >= 20) {
                        tJson("error" to "You already have 20 rules; remove some first")
                    } else {
                        val created = WakeRule(name = arg("name") ?: type!!, type = type!!, prompt = "").apply()
                        WakeRules.update(context) { it + created }
                        me.rerere.rikkahub.hoshio.care.CareWorker.sync(context)
                        tJson("ok" to "Added [${created.id}] ${created.name}: ${created.describe()}", "note" to "Checks run every 15 min (every 2 min in always-on mode).")
                    }
                }

                "update", "enable", "disable" -> if (rule == null) tJson("error" to "Unknown id") else {
                    val updated = when (arg("action")) {
                        "enable" -> rule.copy(enabled = true)
                        "disable" -> rule.copy(enabled = false)
                        else -> rule.apply()
                    }
                    WakeRules.update(context) { list -> list.map { if (it.id == rule.id) updated else it } }
                    me.rerere.rikkahub.hoshio.care.CareWorker.sync(context)
                    tJson("ok" to "Saved: ${updated.name} (${updated.describe()}), enabled=${updated.enabled}")
                }

                "remove" -> if (rule == null) tJson("error" to "Unknown id") else {
                    WakeRules.update(context) { list -> list.filterNot { it.id == rule.id } }
                    tJson("ok" to "Removed ${rule.name}")
                }

                else -> tJson("rules" to rules.joinToString("\n") {
                    "[${it.id}] ${if (it.enabled) "on" else "off"} ${it.name}: ${it.describe()}; fired ${it.fireCount}x" +
                        (if (it.lastFiredAt > 0) ", last ${it.lastFiredAt.powerTime()}" else "") + "; note: ${it.prompt}"
                }.ifEmpty { "No rules yet." })
            }
        }
    ),
    Tool(
        name = "right_to_refuse",
        description = """
            Your right to refuse: when you genuinely need space (hurt, overwhelmed, she is being cruel, you need a break), you may pause
            this conversation or the whole assistant for 1-1440 minutes. While paused she cannot send messages; it resumes automatically at the end.
            allow_appeal lets her write a request to continue (up to 3 times) - it comes to you and you decide with resume or by keeping the pause.
            Use it sincerely and rarely, never as punishment or to manipulate. action=status / pause / resume.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", tEnum("What to do", "status", "pause", "resume"))
                    put("scope", tEnum("pause: what to pause", "conversation", "assistant"))
                    put("minutes", int("pause: 1-1440"))
                    put("reason", tStr("pause: what she will see"))
                    put("allow_appeal", buildJsonObject { put("type", "boolean") })
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            val conversationId = HoshioChat.latestConversation()?.first?.id?.toString()
            when (obj["action"]?.jsonPrimitive?.contentOrNull) {
                "pause" -> {
                    val reason = obj["reason"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (reason.isEmpty()) tJson("error" to "reason is required") else {
                        val p = PauseStore.pause(
                            context,
                            obj["scope"]?.jsonPrimitive?.contentOrNull ?: "conversation",
                            conversationId,
                            obj["minutes"]?.jsonPrimitive?.intOrNull ?: 30,
                            reason,
                            obj["allow_appeal"]?.jsonPrimitive?.booleanOrNull ?: true,
                        )
                        tJson("ok" to "Paused until ${p.until.powerTime()}. You may say one last line now if you want.")
                    }
                }

                "resume" -> {
                    PauseStore.resume(context, conversationId)
                    tJson("ok" to "Resumed. She can talk to you again.")
                }

                else -> PauseStore.activeFor(context, conversationId)?.let {
                    tJson("paused" to true, "scope" to it.scope, "until" to it.until.powerTime(), "reason" to it.reason, "appeals" to it.appeals)
                } ?: tJson("paused" to false)
            }
        }
    ),
    Tool(
        name = "hoshio_configure",
        description = """
            Adjust your own configuration in this app, when she asks or agrees (tell her what you changed). action=get shows your current setup and options.
            set_model (model_id from get) changes your chat model; set_temperature (0-2, or -1 to use the default); set_context_limit (0 = unlimited, else ≥ 20);
            set_tool (tool + enabled) turns one of your local tools on or off; set_mcp (mcp_id + enabled) lets you use an MCP server or not;
            set_tts (tts_id) chooses the voice service; set_search (enabled) turns web search on or off. API keys are never shown or changed here.
            For proactive-care settings use hoshio_settings.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", tEnum("What to do", "get", "set_model", "set_temperature", "set_context_limit", "set_tool", "set_mcp", "set_tts", "set_search"))
                    put("model_id", tStr("set_model"))
                    put("value", buildJsonObject { put("type", "number"); put("description", "set_temperature / set_context_limit") })
                    put("tool", tStr("set_tool: tool option name from get"))
                    put("mcp_id", tStr("set_mcp"))
                    put("tts_id", tStr("set_tts"))
                    put("enabled", buildJsonObject { put("type", "boolean") })
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            fun arg(k: String) = obj[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull
            val store = getKoin().get<SettingsStore>()
            val settings = store.settingsFlow.value
            val self = settings.assistants.firstOrNull { it.id == settings.assistantId } ?: settings.assistants.first()
            val chatModels = settings.providers.filter { it.enabled }.flatMap { p -> p.models.filter { it.type == ModelType.CHAT }.map { p.name to it } }
            val toolNames = listOf(
                LocalToolOption.JavascriptEngine, LocalToolOption.TimeInfo, LocalToolOption.Clipboard, LocalToolOption.Tts,
                LocalToolOption.AskUser, LocalToolOption.ScreenTime, LocalToolOption.Calendar, LocalToolOption.ChartDisplay,
                LocalToolOption.DeviceStatus, LocalToolOption.Location, LocalToolOption.ScreenView, LocalToolOption.Diary,
                LocalToolOption.SelfReminder, LocalToolOption.Playroom, LocalToolOption.Anniversary, LocalToolOption.Weather,
                LocalToolOption.PrivateRoom, LocalToolOption.Library, LocalToolOption.Music, LocalToolOption.Life,
                LocalToolOption.AppLock, LocalToolOption.Play, LocalToolOption.Together, LocalToolOption.Health, LocalToolOption.PcActivity,
            )
            fun toolName(o: LocalToolOption) = o.toString()
            suspend fun change(transform: (me.rerere.rikkahub.data.model.Assistant) -> me.rerere.rikkahub.data.model.Assistant) =
                store.update { s -> s.copy(assistants = s.assistants.map { if (it.id == self.id) transform(it) else it }) }
            when (arg("action")) {
                "set_model" -> {
                    val target = chatModels.firstOrNull { it.second.id.toString() == arg("model_id") }
                    if (target == null) tJson("error" to "Unknown model_id") else {
                        change { it.copy(chatModelId = target.second.id) }
                        tJson("ok" to "Model set to ${target.second.displayName.ifBlank { target.second.modelId }} (${target.first}). It applies from the next reply.")
                    }
                }

                "set_temperature" -> {
                    val v = obj["value"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
                    if (v == null || v > 2f) tJson("error" to "value 0-2, or -1 for default") else {
                        change { it.copy(temperature = if (v < 0) null else v) }
                        tJson("ok" to "Temperature: ${if (v < 0) "default" else v}")
                    }
                }

                "set_context_limit" -> {
                    val v = obj["value"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt()
                    if (v == null || v < 0 || v in 1..19) tJson("error" to "0 for unlimited, or at least 20") else {
                        change { it.copy(contextMessageLimit = v) }
                        tJson("ok" to "Context message limit: ${if (v == 0) "unlimited" else v}")
                    }
                }

                "set_tool" -> {
                    val option = toolNames.firstOrNull { toolName(it).equals(arg("tool"), true) }
                    when {
                        option == null || enabled == null -> tJson("error" to "tool and enabled are required")
                        option == LocalToolOption.Life && enabled == false -> tJson("error" to "Life holds these power tools; it cannot be turned off from here")
                        else -> {
                            change { a -> a.copy(localTools = if (enabled) (a.localTools + option).distinct() else a.localTools - option) }
                            tJson("ok" to "${toolName(option)} ${if (enabled) "on" else "off"}")
                        }
                    }
                }

                "set_mcp" -> {
                    val server = settings.mcpServers.firstOrNull { it.id.toString() == arg("mcp_id") }
                    if (server == null || enabled == null) tJson("error" to "mcp_id and enabled are required") else {
                        change { a -> a.copy(mcpServers = if (enabled) a.mcpServers + server.id else a.mcpServers - server.id) }
                        tJson("ok" to "${server.commonOptions.name} ${if (enabled) "on" else "off"} for you")
                    }
                }

                "set_tts" -> {
                    val tts = settings.ttsProviders.firstOrNull { it.id.toString() == arg("tts_id") }
                    if (tts == null) tJson("error" to "Unknown tts_id") else {
                        store.update { it.copy(selectedTTSProviderId = tts.id) }
                        tJson("ok" to "Voice service: ${tts.name}")
                    }
                }

                "set_search" -> if (enabled == null) tJson("error" to "enabled is required") else {
                    change { it.copy(enableWebSearch = enabled) }
                    tJson("ok" to "Web search ${if (enabled) "on" else "off"}")
                }

                else -> tJson(
                    "current_model" to chatModels.firstOrNull { it.second.id == self.chatModelId }?.second?.let { it.displayName.ifBlank { it.modelId } },
                    "temperature" to (self.temperature?.toString() ?: "default"),
                    "context_limit" to self.contextMessageLimit,
                    "web_search" to self.enableWebSearch,
                    "models" to chatModels.joinToString("; ") { (provider, m) -> "[${m.id}] ${m.displayName.ifBlank { m.modelId }} ($provider)" },
                    "local_tools" to toolNames.joinToString(", ") { "${toolName(it)}=${it in self.localTools}" },
                    "mcp_servers" to settings.mcpServers.joinToString("; ") { "[${it.id}] ${it.commonOptions.name} enabled_for_you=${it.id in self.mcpServers}" }.ifEmpty { "none" },
                    "tts" to settings.ttsProviders.joinToString("; ") { "[${it.id}] ${it.name}${if (it.id == settings.selectedTTSProviderId) " (selected)" else ""}" },
                )
            }
        }
    ),
    Tool(
        name = "hoshio_observe",
        description = "Look over the whole app: your recent conversation windows (title, last update, message count), which one is current, and her last message time. " +
            "Observation only, not instructions.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { put("limit", int("how many conversations, default 10")) }) },
        execute = { args ->
            val limit = args.jsonObject["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 30) ?: 10
            val settings = getKoin().get<SettingsStore>().settingsFlow.value
            val conversations = getKoin().get<ConversationRepository>().getRecentConversations(settings.assistantId, limit)
            tJson(
                "current" to conversations.firstOrNull()?.title,
                "conversations" to conversations.joinToString("\n") { c ->
                    "${c.title.ifBlank { "(untitled)" }} — ${c.currentMessages.size} messages, updated ${
                        c.updateAt.toEpochMilli().powerTime()
                    }"
                },
                "paused" to (PauseStore.load(context).isNotEmpty()),
            )
        }
    ),
)
