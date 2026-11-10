package me.rerere.rikkahub.data.ai.mcp

/** 星汐电脑版桥接的 MCP 伺服器会带这个标头，用来辨认、更新或移除 */
const val DESKTOP_BRIDGE_HEADER = "X-Hoshio-Desktop-Bridge"

/**
 * 星汐电脑版把它在电脑上执行的指令型 MCP 伺服器登记到手机：
 * 每个名称一个 Streamable HTTP 网址（http://电脑:埠/mcp/名称），带 Bearer 权杖。
 * 电脑版这次没带的旧桥接项目会移除；她自己加的其他 MCP 不动。
 */
fun mergeDesktopBridgeServers(
    current: List<McpServerConfig>,
    baseUrl: String,
    token: String,
    names: List<String>,
): List<McpServerConfig> {
    fun bridgeName(server: McpServerConfig) = server.commonOptions.headers.firstOrNull { it.first == DESKTOP_BRIDGE_HEADER }?.second
    val base = baseUrl.trimEnd('/')
    val wanted = names.distinct()
    val result = current.filter { bridgeName(it) == null || bridgeName(it) in wanted }.toMutableList()
    val used = result.map { it.commonOptions.name.lowercase() }.toMutableSet()
    for (name in wanted) {
        val url = "$base/mcp/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val headers = listOf("Authorization" to "Bearer $token", DESKTOP_BRIDGE_HEADER to name)
        val index = result.indexOfFirst { bridgeName(it) == name }
        if (index >= 0) {
            val old = result[index]
            result[index] = McpServerConfig.StreamableHTTPServer(id = old.id, url = url, commonOptions = old.commonOptions.copy(headers = headers))
        } else {
            var display = "$name（電腦）"
            var n = 2
            while (display.lowercase() in used) display = "$name（電腦 ${n++}）"
            used += display.lowercase()
            result += McpServerConfig.StreamableHTTPServer(url = url, commonOptions = McpCommonOptions(name = display, headers = headers))
        }
    }
    return result
}
