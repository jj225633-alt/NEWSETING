// 藏宝地图：Based on hidden-letters-map by Saekisui - https://github.com/Saekisui/hidden-letters-map
// (AGPL-3.0 with attribution term; see assets/hoshio-treasure/NOTICE)
// 幸运转盘：Ruota della Fortuna, MIT, Copper (29-Cu); see assets/hoshio-ruota/LICENSE
package me.rerere.rikkahub.ui.pages.hoshio

import me.rerere.rikkahub.hoshio.Diagnostics as HoshioDiagnostics
import android.annotation.SuppressLint
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.tools.local.currentLocationOrNull
import me.rerere.rikkahub.hoshio.treasure.TreasureLetter
import me.rerere.rikkahub.hoshio.treasure.TreasureLogic
import me.rerere.rikkahub.hoshio.treasure.TreasureMapStore
import me.rerere.rikkahub.hoshio.treasure.TreasureState
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val ASSET_ORIGIN = "https://appassets.androidplatform.net"

private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

/** 只载入 App 内建的一个网页；[allowNetwork] 决定哪些外部网址可以下载 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AssetWebView(
    assetDir: String,
    file: String,
    channel: String,
    modifier: Modifier = Modifier,
    domStorage: Boolean = false,
    allowNetwork: (String) -> Boolean = { false },
    onCreated: (WebView) -> Unit = {},
    onMessage: (String) -> Unit,
) {
    val home = "$ASSET_ORIGIN/assets/$assetDir/$file"
    val currentOnMessage by rememberUpdatedState(onMessage)
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        Text("這支手機的 WebView 版本太舊，暫時無法開啟。", modifier.padding(16.dp))
        return
    }
    AndroidView(modifier = modifier, factory = { ctx ->
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/$assetDir/") { path ->
            // 星汐: 同資料夾裡打包的檔案（例如地圖用的 leaflet）也從 App 裡讀，不靠外部 CDN
            val mime = when {
                path == file || path.endsWith(".html") -> "text/html"
                path.endsWith(".js") -> "application/javascript"
                path.endsWith(".css") -> "text/css"
                path.endsWith(".png") -> "image/png"
                else -> null
            }
            if (mime == null || path.contains("..") || path.contains('/')) null
            else runCatching { WebResourceResponse(mime, "UTF-8", ctx.assets.open("$assetDir/$path")) }.getOrNull()
        }.build()
        WebView(ctx).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = domStorage
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.setGeolocationEnabled(false)
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) = request.deny()
                override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                    callback?.invoke(origin, false, false)
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    if (request.method == "GET" && url.startsWith("$ASSET_ORIGIN/assets/$assetDir/")) loader.shouldInterceptRequest(request.url)?.let { return it }
                    if (request.method == "GET" && allowNetwork(url)) return null
                    return blocked()
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail) = true
            }
            WebViewCompat.addWebMessageListener(this, channel, setOf(ASSET_ORIGIN)) { _, message, origin, isMainFrame, _ ->
                if (isMainFrame && origin.toString() == ASSET_ORIGIN) message.data?.let { currentOnMessage(it) }
            }
            onCreated(this)
            loadUrl(home)
        }
    }, onRelease = { released ->
        runCatching { WebViewCompat.removeWebMessageListener(released, channel) }
        released.stopLoading()
        released.destroy()
    })
}

// ───────────────────────── 幸运转盘 ─────────────────────────

@Composable
fun RuotaPage() {
    val callHim = rememberCallHim()
    PlayScaffold("幸運轉盤") { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            AssetWebView(
                assetDir = "hoshio-ruota",
                file = "index.html",
                channel = "HoshioRuota",
                domStorage = true, // 自订标签与纪录存在本机
                modifier = Modifier.fillMaxSize(),
            ) { raw ->
                val root = runCatching { Json.parseToJsonElement(raw.take(8000)).jsonObject }.getOrNull() ?: return@AssetWebView
                if (root["type"]?.jsonPrimitive?.content != "send") return@AssetWebView
                val text = root["text"]?.jsonPrimitive?.content?.take(4000)?.trim().orEmpty()
                if (text.isNotEmpty()) callHim("幸運轉盤", "She spun the lucky wheel and wants to share the result with you:\n$text\nReact in character.")
            }
        }
    }
}

// ───────────────────────── 藏宝地图 ─────────────────────────

private object TreasureWebPolicy {
    private val leaflet = setOf("https://unpkg.com/leaflet@1.9.4/dist/leaflet.css", "https://unpkg.com/leaflet@1.9.4/dist/leaflet.js")
    private val tiles = Regex("^https://[a-d]\\.basemaps\\.cartocdn\\.com/(light_all|dark_all)/\\d{1,2}/\\d+/\\d+(@2x)?\\.png$")

    /** 只允许：固定版本的 Leaflet、CARTO 底图图砖 */
    fun network(url: String): Boolean = url in leaflet || tiles.matches(url) || url.startsWith("https://unpkg.com/leaflet@1.9.4/dist/images/")
}

private val TREASURE_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("M 月 d 日")
private fun treasureDay(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(TREASURE_DAY) }.orEmpty()

@Composable
fun TreasurePage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dark = LocalDarkMode.current
    val accent = MaterialTheme.colorScheme.primary
    var state by remember { mutableStateOf(TreasureState()) }
    var me by remember { mutableStateOf<Triple<Double, Double, Float>?>(null) }
    var focusMe by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var mapReady by remember { mutableStateOf(false) }
    var mapStatus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(8000)
        if (!mapReady) {
            mapStatus = "地圖頁面沒有回應（WebView 沒有載入）。可以到 Play 商店更新「Android System WebView」再試。"
            HoshioDiagnostics.record(context, "藏寶地圖", mapStatus.orEmpty())
        }
    }

    LaunchedEffect(Unit) { state = withContext(Dispatchers.IO) { TreasureMapStore.load(context) } }
    DisposableEffect(Unit) {
        val listener: () -> Unit = { web?.post { state = TreasureMapStore.load(context) } }
        TreasureMapStore.addListener(listener)
        onDispose { TreasureMapStore.removeListener(listener) }
    }
    // 把资料画到地图上（只送她能看的样子：封着的没有座标与内容）
    LaunchedEffect(state, me, mapReady, dark) {
        val view = web ?: return@LaunchedEffect
        if (!mapReady) return@LaunchedEffect
        val payload = JSONObject().apply {
            put("dark", dark)
            put("accent", String.format("#%06X", accent.toArgb() and 0xFFFFFF))
            put("letters", JSONArray().apply { state.letters.forEach { put(TreasureLogic.publicLetter(it)) } })
            me?.let { put("me", JSONObject().put("lat", it.first).put("lon", it.second).put("acc", it.third.toDouble())) }
            put("focusMe", focusMe)
        }
        focusMe = false
        view.evaluateJavascript("window.hoshioRender && window.hoshioRender($payload);", null)
    }

    fun checkHere() {
        locating = true
        message = "正在定位…"
        scope.launch {
            val location = context.currentLocationOrNull()
            locating = false
            if (location == null) {
                message = "取不到位置。請確認星汐有定位權限（精確位置），定位服務和 Wi-Fi 都開著。"
                return@launch
            }
            val acc = if (location.hasAccuracy()) location.accuracy else 100f
            me = Triple(location.latitude, location.longitude, acc)
            focusMe = true
            if (acc > 100f) {
                message = "定位誤差約 ${acc.toInt()} 公尺，太不準了。開啟 Wi-Fi 或到戶外再試一次。"
                return@launch
            }
            val events = withContext(Dispatchers.IO) { TreasureMapStore.visit(context, location.latitude, location.longitude, acc) }
            state = TreasureMapStore.load(context)
            val found = events.filter { it.event == "found" }
            val visits = events.filter { it.event == "visit" }
            message = when {
                found.isNotEmpty() -> "🎁 找到了！「${found.joinToString("」「") { it.letter.place }}」，在下面拆開吧。"
                visits.isNotEmpty() -> "今天的到訪記下了：" + visits.joinToString("、") { "${it.letter.visitDays.size}/${it.letter.visitsNeeded} 天" } + "。"
                else -> "這裡還沒有藏寶。看看地圖上的圈和謎語，再找找看～"
            }
        }
    }

    PlayScaffold("藏寶地圖") { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            Box(
                Modifier.fillMaxWidth().weight(.45f).padding(horizontal = 12.dp).clip(MaterialTheme.shapes.extraLarge),
            ) {
                AssetWebView(
                    assetDir = "hoshio-treasure",
                    file = "map.html",
                    channel = "HoshioTreasure",
                    allowNetwork = TreasureWebPolicy::network,
                    onCreated = { web = it },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // 星汐: 地圖回報的狀態，載不出來時讓她看得到原因
                    when {
                        it == "ready" -> { mapReady = true; if (mapStatus == null) mapStatus = "地圖載入中…（底圖要網路）" }
                        it == "tiles-ok" -> mapStatus = ""
                        it == "tiles-fail" -> mapStatus = "地圖底圖下載失敗：檢查網路，或 VPN／廣告阻擋是否擋了 basemaps.cartocdn.com"
                        it.startsWith("err:") -> {
                            mapStatus = "地圖出錯：" + it.removePrefix("err:").take(120)
                            HoshioDiagnostics.record(context, "藏寶地圖", mapStatus.orEmpty())
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Button(onClick = ::checkHere, enabled = !locating, modifier = Modifier.fillMaxWidth()) {
                    Text(if (locating) "定位中…" else "📍 我就在這附近")
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                mapStatus?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
            }
            LazyColumn(
                Modifier.fillMaxWidth().weight(.55f),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val wall = state.badges.map { it.badge } + state.letters.filter { it.status == "opened" }.mapNotNull { it.badge }
                if (wall.isNotEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("成就牆", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(wall) { b ->
                                Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                                    Column(Modifier.width(96.dp).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(b.emoji, fontSize = 28.sp)
                                        Text(b.name, style = MaterialTheme.typography.labelMedium, maxLines = 2)
                                        if (b.note.isNotEmpty()) Text(b.note, style = MaterialTheme.typography.labelSmall, maxLines = 3)
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.letters.isEmpty()) item {
                    Text("還沒有藏寶。在聊天裡請他把信或成就藏在你會去的地方吧！", style = MaterialTheme.typography.bodyMedium)
                }
                items(state.letters.sortedByDescending { it.createdAt }, key = { it.id }) { letter ->
                    TreasureCard(letter) { state = TreasureMapStore.open(context, letter.id) }
                }
                item {
                    Text(
                        "Based on hidden-letters-map by Saekisui - https://github.com/Saekisui/hidden-letters-map（AGPL-3.0）\n地圖 © OpenStreetMap contributors © CARTO",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TreasureCard(letter: TreasureLetter, onOpen: () -> Unit) {
    val contents = listOfNotNull(if (letter.body.isNotEmpty()) "✉️ 信" else null, if (letter.badge != null) "🏅 成就" else null).joinToString("  ")
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (letter.status == "found") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (letter.status) {
                "sealed" -> {
                    Text("🔒 ${letter.hint}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        contents + if (letter.visitsNeeded > 1) "　·　到訪 ${letter.visitDays.size}/${letter.visitsNeeded} 天" else "",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (letter.author.isNotEmpty()) Text("${letter.author} 藏的 · ${treasureDay(letter.createdAt)}", style = MaterialTheme.typography.labelSmall)
                }

                "found" -> {
                    Text("🎁 找到了：${letter.place}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${treasureDay(letter.foundAt)} 找到 · $contents", style = MaterialTheme.typography.labelMedium)
                    Button(onClick = onOpen) { Text("拆開") }
                }

                else -> {
                    Text("📖 ${letter.place}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${treasureDay(letter.foundAt)} 找到 · ${treasureDay(letter.openedAt)} 拆開", style = MaterialTheme.typography.labelSmall)
                    if (letter.body.isNotEmpty()) Text(letter.body, style = MaterialTheme.typography.bodyLarge)
                    letter.badge?.let {
                        Text(
                            "${it.emoji} 獲得成就「${it.name}」" + if (it.note.isNotEmpty()) "：${it.note}" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (letter.author.isNotEmpty()) Text("— ${letter.author}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
