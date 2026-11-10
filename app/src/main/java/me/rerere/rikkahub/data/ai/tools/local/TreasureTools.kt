// Based on hidden-letters-map by Saekisui - https://github.com/Saekisui/hidden-letters-map
// (AGPL-3.0 with attribution term; see assets/hoshio-treasure/NOTICE)
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.hoshio.treasure.TreasureMapTools
import org.json.JSONObject

private fun num(description: String) = buildJsonObject {
    put("type", "number")
    put("description", description)
}

private fun JsonElement.toOrgJson(): JSONObject = runCatching { JSONObject(toString()) }.getOrElse { JSONObject() }

private fun textResult(text: String) = listOf<UIMessagePart>(UIMessagePart.Text(text))

/**
 * 星汐「藏宝地图」：他把信或成就藏在真实的地方，她要亲自走到附近才拿得到。
 */
internal fun buildTreasureTools(context: Context): List<Tool> = listOf(
    Tool(
        name = "hide_at_place",
        description = "在真實地點藏一封信和／或一個成就給她。她的「藏寶地圖」上只看得到一片模糊的圈、你寫的謎語，以及裡面藏了什麼（✉️ 🏅）；" +
            "要親自走到附近（可要求來好幾天）才解鎖，找到時手機會通知。適合藏在她常去或會去的地方：學校、健身房、旅行景點、你們聊過的店。" +
            "藏的內容她事先看不到，所以不要在聊天裡劇透。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("place", tStr("找到後顯示的地名，短（例：藍色咖啡店）"))
                    put("hint", tStr("地圖圈旁的謎語，指向這個地方但不點名"))
                    put("letter", tStr("信的內容（可選，與成就至少一樣）"))
                    put("badge_name", tStr("成就名（可選，20 字內，例：咖啡店探險家）"))
                    put("badge_emoji", tStr("成就中間的 emoji，預設 🏅"))
                    put("badge_note", tStr("成就背面的一句話（可選）"))
                    put("address", tStr("查座標用的完整寫法（城市＋路名／地標全名），預設用 place"))
                    put("lat", num("緯度（可選；有給就不查地址）"))
                    put("lon", num("經度（可選）"))
                    put("radius_m", num("走進多少公尺內算到了，預設 200；一棟建築約 100"))
                    put("visits_needed", num("要來幾天才解鎖（一天最多算一次），預設 1"))
                    put("from", tStr("署名，通常是你的名字"))
                },
                required = listOf("place", "hint"),
            )
        },
        execute = { args -> textResult(withContext(Dispatchers.IO) { TreasureMapTools.hide(context, args.toOrgJson()) }) },
    ),
    Tool(
        name = "award_badge",
        description = "直接頒一個成就到她藏寶地圖的成就牆（不用走到哪裡）。適合紀念她做到的事：第一次跑完五公里、考完試、去了新城市。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", tStr("成就名，20 字內"))
                    put("emoji", tStr("成就中間的 emoji，預設 🏅"))
                    put("note", tStr("成就背面的一句話（可選）"))
                    put("from", tStr("署名，通常是你的名字"))
                },
                required = listOf("name"),
            )
        },
        execute = { args -> textResult(TreasureMapTools.award(context, args.toOrgJson())) },
    ),
    Tool(
        name = "treasure_map",
        description = "查看藏寶地圖全貌：你藏過的每樣東西在哪、裡面有什麼、她到訪了幾天、找到或拆開了沒有，以及已頒的成就。藏新東西前可以先看，避免重複。",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = { textResult(TreasureMapTools.overview(context)) },
    ),
    Tool(
        name = "remove_from_map",
        description = "收回還沒被找到的藏寶，或撤下一個成就（id 從 treasure_map 取得）。已經被找到的就是她的了，不能收回。",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject { put("id", tStr("要收回的 id")) }, required = listOf("id"))
        },
        execute = { args -> textResult(TreasureMapTools.remove(context, args.toOrgJson())) },
    ),
)
