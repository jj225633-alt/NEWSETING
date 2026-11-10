package me.rerere.rikkahub.web.routes

import android.content.Context
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.hoshio.pc.PcActivityReport
import me.rerere.rikkahub.hoshio.pc.PcActivityStore
import me.rerere.rikkahub.hoshio.pc.PcRequestInfo
import me.rerere.rikkahub.hoshio.pc.PcScreenshotBroker
import me.rerere.rikkahub.hoshio.pc.PcScreenshotResult
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.ConflictException
import me.rerere.rikkahub.web.ForbiddenException

@Serializable private data class PcAck(val ok: Boolean, val stored: Int)
@Serializable private data class BridgeRegistration(val baseUrl: String, val token: String, val servers: List<String>)
@Serializable private data class PcRequests(val requests: List<PcRequestInfo>)
@Serializable private data class PcScreenshotUpload(val ok: Boolean, val code: String? = null, val imageBase64: String? = null, val width: Int = 0, val height: Int = 0)

/**
 * 星汐电脑版（移植自 Orbis 电脑版）：回报电脑活动、取走截图请求、上传截图、登记桥接的 MCP。
 * 只在「密码认证」开启时接受。
 */
fun Route.pcRoutes(context: Context, settingsStore: SettingsStore) {
    fun requirePassword() {
        if (!settingsStore.settingsFlow.value.webServerJwtEnabled) throw ForbiddenException("password_required")
    }
    post("/hoshio/pc/activity") {
        requirePassword()
        val report = call.receive<PcActivityReport>()
        val state = try {
            withContext(Dispatchers.IO) { PcActivityStore.accept(context, report) }
        } catch (e: IllegalArgumentException) {
            throw BadRequestException(e.message ?: "pc_report_invalid")
        }
        call.respond(PcAck(true, state.timeline.size))
    }
    // 电脑版长轮询：取得他的截图请求
    get("/hoshio/pc/requests") {
        requirePassword()
        call.respond(PcRequests(PcScreenshotBroker.poll()))
    }
    post("/hoshio/pc/screenshot/{id}") {
        requirePassword()
        val id = call.parameters["id"] ?: throw BadRequestException("missing_id")
        val upload = call.receive<PcScreenshotUpload>()
        val result = if (!upload.ok) {
            PcScreenshotResult(false, upload.code?.take(60) ?: "refused")
        } else {
            val bytes = runCatching { java.util.Base64.getDecoder().decode(upload.imageBase64.orEmpty()) }
                .getOrElse { throw BadRequestException("image_invalid") }
            if (bytes.size < 100 || bytes.size > 12 * 1024 * 1024 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) {
                throw BadRequestException("image_invalid")
            }
            PcScreenshotResult(true, null, bytes, upload.width, upload.height)
        }
        if (!PcScreenshotBroker.complete(id, result)) throw BadRequestException("request_expired")
        call.respond(PcAck(true, 0))
    }
    delete("/hoshio/pc/activity") {
        requirePassword()
        withContext(Dispatchers.IO) { PcActivityStore.clear(context) }
        call.respond(PcAck(true, 0))
    }
    // 电脑版把它桥接的指令型 MCP 伺服器登记到手机
    post("/hoshio/mcp/bridge") {
        requirePassword()
        val request = call.receive<BridgeRegistration>()
        val base = runCatching { java.net.URI(request.baseUrl) }.getOrNull()
        if (base == null || base.scheme != "http" || base.host.isNullOrBlank() || base.rawPath.let { !it.isNullOrEmpty() && it != "/" }) {
            throw BadRequestException("base_url_invalid")
        }
        if (request.token.length !in 16..200 || request.servers.size > 50 || request.servers.any { it.isBlank() || it.length > 100 }) {
            throw BadRequestException("registration_invalid")
        }
        var count = 0
        try {
            settingsStore.update { settings ->
                val merged = me.rerere.rikkahub.data.ai.mcp.mergeDesktopBridgeServers(settings.mcpServers, request.baseUrl, request.token, request.servers)
                count = merged.size
                settings.copy(mcpServers = merged)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw ConflictException("bridge_save_failed: " + e::class.simpleName)
        }
        call.respond(PcAck(true, count))
    }
}
