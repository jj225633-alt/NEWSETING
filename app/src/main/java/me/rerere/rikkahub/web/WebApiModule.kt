package me.rerere.rikkahub.web

import android.content.Context
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.dto.ErrorResponse
import me.rerere.rikkahub.web.dto.WebAuthTokenRequest
import me.rerere.rikkahub.web.dto.WebAuthTokenResponse
import me.rerere.rikkahub.web.routes.aiIconRoutes
import me.rerere.rikkahub.web.routes.assetsRoutes
import me.rerere.rikkahub.web.routes.conversationRoutes
import me.rerere.rikkahub.web.routes.eventsRoutes
import me.rerere.rikkahub.web.routes.filesRoutes
import me.rerere.rikkahub.web.routes.folderRoutes
import me.rerere.rikkahub.web.routes.hoshioRoutes
import me.rerere.rikkahub.web.routes.pcRoutes
import me.rerere.rikkahub.web.routes.settingsRoutes
import java.security.MessageDigest

private const val WEB_JWT_ISSUER = "rikkahub-web"
private const val WEB_JWT_AUDIENCE = "rikkahub-web-client"
private const val WEB_JWT_SUBJECT = "web-access"
private const val WEB_JWT_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000
private const val WEB_ACCESS_TOKEN_QUERY_KEY = "access_token"
private const val WEB_AUTH_PROVIDER = "auth-jwt"

/**
 * Configure Web API for the Ktor application.
 * This should be called from app module when starting the web server.
 *
 * Example usage:
 * ```
 * startWebServer(port = 8080) {
 *     configureWebApi(context, chatService, conversationRepo, settingsStore, filesManager)
 * }
 * ```
 */
fun Application.configureWebApi(
    context: Context,
    chatService: ChatService,
    conversationRepo: ConversationRepository,
    folderRepo: FolderRepository,
    settingsStore: SettingsStore,
    filesManager: FilesManager
) {
    // 星汐: 對區網開放時一律要密碼（沒設密碼＝受保護的路一律拒絕）；只有「僅限本機」才能不設
    val appContextForLog: Context = context
    val startSettings = settingsStore.settingsFlow.value
    val jwtEnabled = startSettings.webServerJwtEnabled || !startSettings.webServerLocalhostOnly

    install(ContentNegotiation) {
        json(JsonInstant)
    }

    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorResponse("Not Found", status.value))
        }
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ErrorResponse(cause.message, cause.status.value))
        }
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(cause.message ?: "Internal server error", 500)
            )
        }
    }

    if (jwtEnabled) {
        install(Authentication) {
            provider(WEB_AUTH_PROVIDER) {
                authenticate { context ->
                    // Dynamically read the current password on each request so that
                    // tokens signed after a password change are validated correctly.
                    val currentPassword = settingsStore.settingsFlow.value.webServerAccessPassword
                    // 星汐: 網址上的憑證只給即時更新（SSE，瀏覽器沒辦法帶標頭）用，其他一律要放在標頭
                    val path = context.call.request.local.uri
                    val allowQuery = path.contains("/events") || path.contains("/stream")
                    val token = extractAccessToken(
                        authorizationHeader = context.call.request.headers[HttpHeaders.Authorization],
                        queryToken = if (allowQuery) context.call.request.queryParameters[WEB_ACCESS_TOKEN_QUERY_KEY] else null
                    )
                    // Keep protected routes closed when jwt is enabled but password is missing.
                    if (token != null && currentPassword.isNotBlank() && verifyWebJwt(token, currentPassword)) {
                        context.principal(WEB_AUTH_PROVIDER, UserIdPrincipal(WEB_JWT_SUBJECT))
                        return@authenticate
                    }
                    val cause = if (token == null) {
                        AuthenticationFailedCause.NoCredentials
                    } else {
                        AuthenticationFailedCause.InvalidCredentials
                    }
                    context.challenge(WEB_AUTH_PROVIDER, cause) { challenge, call ->
                        me.rerere.rikkahub.web.WebSecurity.logDenied(
                            appContextForLog,
                            call.request.local.remoteAddress, call.request.local.method.value, call.request.local.uri,
                            if (currentPassword.isBlank()) 403 else 401,
                        )
                        if (currentPassword.isBlank()) {
                            call.respond(
                                HttpStatusCode.Forbidden,
                                ErrorResponse("Access password is not configured", HttpStatusCode.Forbidden.value)
                            )
                        } else {
                            call.respond(
                                HttpStatusCode.Unauthorized,
                                ErrorResponse("Unauthorized", HttpStatusCode.Unauthorized.value)
                            )
                        }
                        challenge.complete()
                    }
                }
            }
        }
    }

    routing {
        route("/api") {
            post("/auth/token") {
                val settings = settingsStore.settingsFlow.value
                if (!jwtEnabled) {
                    throw BadRequestException("JWT auth is disabled")
                }

                val accessPassword = settings.webServerAccessPassword
                if (accessPassword.isBlank()) {
                    throw BadRequestException("Access password is not configured")
                }

                val remote = call.request.local.remoteAddress
                if (me.rerere.rikkahub.web.WebSecurity.isLocked(remote)) {
                    me.rerere.rikkahub.web.WebSecurity.logDenied(appContextForLog, remote, "POST", "/api/auth/token（鎖定中）", 429)
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("Too many attempts, try again later", 429))
                    return@post
                }
                val request = call.receive<WebAuthTokenRequest>()
                if (!secureEquals(request.password, accessPassword)) {
                    me.rerere.rikkahub.web.WebSecurity.recordFailure(remote)
                    me.rerere.rikkahub.web.WebSecurity.logDenied(appContextForLog, remote, "POST", "/api/auth/token（密碼錯誤）", 401)
                    throw UnauthorizedException("Invalid password")
                }
                me.rerere.rikkahub.web.WebSecurity.recordSuccess(remote)

                val (token, expiresAt) = createWebJwt(accessPassword)
                call.respond(
                    HttpStatusCode.OK,
                    WebAuthTokenResponse(
                        token = token,
                        expiresAt = expiresAt
                    )
                )
            }

            aiIconRoutes(context)

            if (jwtEnabled) {
                authenticate(WEB_AUTH_PROVIDER) {
                    conversationRoutes(chatService, conversationRepo, folderRepo, settingsStore)
                    folderRoutes(chatService, folderRepo, settingsStore)
                    eventsRoutes(chatService, conversationRepo, folderRepo, settingsStore)
                    settingsRoutes(settingsStore)
                    filesRoutes(filesManager, context)
                    assetsRoutes(context)
                    hoshioRoutes(context, settingsStore)
                    pcRoutes(context, settingsStore)
                }
            } else {
                conversationRoutes(chatService, conversationRepo, folderRepo, settingsStore)
                folderRoutes(chatService, folderRepo, settingsStore)
                eventsRoutes(chatService, conversationRepo, folderRepo, settingsStore)
                settingsRoutes(settingsStore)
                filesRoutes(filesManager, context)
                assetsRoutes(context)
                hoshioRoutes(context, settingsStore)
            }
        }
    }
}

private fun createWebJwt(secret: String): Pair<String, Long> {
    val now = System.currentTimeMillis()
    val expiresAt = now + WEB_JWT_TTL_MILLIS
    val token = WebJwt.sign(
        secret = secret,
        issuer = WEB_JWT_ISSUER,
        audience = WEB_JWT_AUDIENCE,
        subject = WEB_JWT_SUBJECT,
        issuedAtMillis = now,
        expiresAtMillis = expiresAt,
    )
    return token to expiresAt
}

private fun verifyWebJwt(token: String, secret: String): Boolean {
    return WebJwt.verify(
        token = token,
        secret = secret,
        issuer = WEB_JWT_ISSUER,
        audience = WEB_JWT_AUDIENCE,
        subject = WEB_JWT_SUBJECT,
    )
}

private fun extractBearerToken(authorizationHeader: String?): String? {
    if (authorizationHeader.isNullOrBlank()) return null
    val prefix = "Bearer "
    if (!authorizationHeader.startsWith(prefix, ignoreCase = true)) return null
    return authorizationHeader.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
}

private fun extractAccessToken(authorizationHeader: String?, queryToken: String?): String? {
    return extractBearerToken(authorizationHeader)
        ?: queryToken?.trim()?.takeIf { it.isNotEmpty() }
}

private fun secureEquals(left: String, right: String): Boolean {
    return MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
}
