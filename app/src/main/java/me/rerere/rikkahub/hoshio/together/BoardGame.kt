package me.rerere.rikkahub.hoshio.together

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * 星汐「下棋」：圈圈叉叉（3×3，连 3）和五子棋（11×11，连 5）。
 * 她是 X、他是 O。cells 由左上往右逐行，'.' 是空格。
 */
@Serializable
data class BoardGame(
    /** ttt | gomoku */
    val type: String,
    val size: Int,
    val cells: String,
    /** her | him */
    val turn: String,
    /** her | him | draw | null */
    val winner: String? = null,
    val lastMove: Int? = null,
    val moves: Int = 0,
    val hisComment: String = "",
    val startedAt: Long = System.currentTimeMillis(),
) {
    val winLength: Int get() = if (type == "ttt") 3 else 5
    val name: String get() = if (type == "ttt") "圈圈叉叉" else "五子棋"
    fun at(row: Int, col: Int): Char = cells[row * size + col]

    /** 给模型看的棋盘：列号 A..，行号 1.. */
    fun ascii(): String = buildString {
        append("   ")
        (0 until size).forEach { append(('A' + it)).append(' ') }
        appendLine()
        for (r in 0 until size) {
            append((r + 1).toString().padStart(2)).append(' ')
            for (c in 0 until size) append(at(r, c)).append(' ')
            appendLine()
        }
    }

    fun coord(index: Int) = "${'A' + index % size}${index / size + 1}"
}

object BoardGameStore {
    private const val PREFS = "hoshio_board_game"
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()
    private var askedKey: Pair<Long, Int>? = null

    /**
     * 轮到他时请他下一步。为了快，不走工具：请他直接回「C4｜一句话」，一次生成就好。
     * 在 App 自己的 scope 里跑，离开页面也会下完；他没回或看不懂就帮他随手下。
     */
    fun requestHisMove(context: Context) {
        val g = load(context) ?: return
        if (g.turn != "him" || g.winner != null) return
        val key = g.startedAt to g.moves
        if (askedKey == key) return
        askedKey = key
        _thinking.value = true
        scope.launch {
            try {
                val herMove = g.lastMove?.let { "She just played ${g.coord(it)}. " } ?: "You move first. "
                val reply = me.rerere.rikkahub.hoshio.HoshioChat.ask(
                    title = "下棋",
                    text = "Your move in ${g.name} (she is X, you are O, ${g.winLength} in a row wins). $herMove Board:\n${g.ascii()}\n" +
                        "Reply with ONLY your move and one short in-character comment, exactly like: C4｜comment. Do not call any tools.",
                    timeoutMinutes = 3,
                )
                val now = load(context)
                if (now != null && now.startedAt == g.startedAt && now.moves == g.moves && now.turn == "him") {
                    val parsed = reply?.text?.let { parseReply(now, it) }
                    if (parsed != null) {
                        move(context, "him", parsed.first, parsed.second)
                    } else {
                        move(context, "him", fallbackMove(now), if (reply == null) "（還沒有對話，他隨手下了一步）" else "（他想太久，隨手下了一步）")
                    }
                }
            } finally {
                _thinking.value = false
            }
        }
    }

    private val COORD = Regex("([A-Ka-k])\\s*(1[01]|[1-9])")

    private fun parseReply(game: BoardGame, text: String): Pair<Int, String>? {
        val match = COORD.findAll(text).firstOrNull { m ->
            parseCoord(game, m.groupValues[1] + m.groupValues[2])?.let { game.cells[it] == '.' } == true
        } ?: return null
        val index = parseCoord(game, match.groupValues[1] + match.groupValues[2]) ?: return null
        val comment = text.substringAfter('｜', text.substringAfter('|', text.removeRange(match.range)))
            .replace(Regex("<think>[\\s\\S]*?</think>"), "")
            .trim().take(120)
        return index to comment
    }
    const val HER = 'X'
    const val HIM = 'O'

    private val _game = MutableStateFlow<BoardGame?>(null)
    val game: StateFlow<BoardGame?> = _game.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): BoardGame? {
        val g = prefs(context).getString("game", null)?.let { runCatching { togetherJson.decodeFromString<BoardGame>(it) }.getOrNull() }
        _game.value = g
        return g
    }

    private fun save(context: Context, game: BoardGame?) {
        prefs(context).edit { if (game == null) remove("game") else putString("game", togetherJson.encodeToString(game)) }
        _game.value = game
    }

    fun record(context: Context): Triple<Int, Int, Int> = prefs(context).let {
        Triple(it.getInt("her_wins", 0), it.getInt("his_wins", 0), it.getInt("draws", 0))
    }

    fun start(context: Context, type: String, herFirst: Boolean): BoardGame {
        val size = if (type == "ttt") 3 else 11
        val game = BoardGame(type = type, size = size, cells = ".".repeat(size * size), turn = if (herFirst) "her" else "him")
        save(context, game)
        return game
    }

    fun clear(context: Context) = save(context, null)

    /** 下一步；不合法时回传错误讯息 */
    fun move(context: Context, by: String, index: Int, comment: String? = null): Result<BoardGame> {
        val game = load(context) ?: return Result.failure(IllegalStateException("No game in progress"))
        if (game.winner != null) return Result.failure(IllegalStateException("The game is already over"))
        if (game.turn != by) return Result.failure(IllegalStateException("It is not ${if (by == "him") "your" else "her"} turn"))
        if (index !in game.cells.indices || game.cells[index] != '.') return Result.failure(IllegalArgumentException("That square is not empty or off the board"))
        val mark = if (by == "her") HER else HIM
        val cells = StringBuilder(game.cells).apply { setCharAt(index, mark) }.toString()
        var next = game.copy(
            cells = cells,
            turn = if (by == "her") "him" else "her",
            lastMove = index,
            moves = game.moves + 1,
            hisComment = if (by == "him") comment?.take(120).orEmpty() else game.hisComment,
        )
        val winner = when {
            wins(next, index, mark) -> by
            '.' !in cells -> "draw"
            else -> null
        }
        if (winner != null) {
            next = next.copy(winner = winner)
            prefs(context).edit {
                val key = when (winner) { "her" -> "her_wins"; "him" -> "his_wins"; else -> "draws" }
                putInt(key, prefs(context).getInt(key, 0) + 1)
            }
        }
        save(context, next)
        return Result.success(next)
    }

    private fun wins(game: BoardGame, index: Int, mark: Char): Boolean {
        val r0 = index / game.size
        val c0 = index % game.size
        for ((dr, dc) in listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)) {
            var count = 1
            for (sign in listOf(1, -1)) {
                var r = r0 + dr * sign
                var c = c0 + dc * sign
                while (r in 0 until game.size && c in 0 until game.size && game.at(r, c) == mark) {
                    count++
                    r += dr * sign
                    c += dc * sign
                }
            }
            if (count >= game.winLength) return true
        }
        return false
    }

    /** 他想太久时的备用一步：能赢就赢、该挡就挡，不然下在已有棋子旁边 */
    fun fallbackMove(game: BoardGame): Int {
        val empty = game.cells.indices.filter { game.cells[it] == '.' }
        fun tryMark(i: Int, mark: Char) = wins(game.copy(cells = StringBuilder(game.cells).apply { setCharAt(i, mark) }.toString()), i, mark)
        empty.firstOrNull { tryMark(it, HIM) }?.let { return it }
        empty.firstOrNull { tryMark(it, HER) }?.let { return it }
        val center = (game.size / 2) * game.size + game.size / 2
        if (game.cells[center] == '.') return center
        val near = empty.filter { i ->
            val r = i / game.size
            val c = i % game.size
            (-1..1).any { dr -> (-1..1).any { dc ->
                val rr = r + dr
                val cc = c + dc
                rr in 0 until game.size && cc in 0 until game.size && game.at(rr, cc) != '.'
            } }
        }
        return (near.ifEmpty { empty }).random()
    }

    /** "C4" 这样的座标转成 index */
    fun parseCoord(game: BoardGame, text: String): Int? {
        val t = text.trim().uppercase()
        if (t.length < 2) return null
        val col = t[0] - 'A'
        val row = t.substring(1).toIntOrNull()?.minus(1) ?: return null
        if (col !in 0 until game.size || row !in 0 until game.size) return null
        return row * game.size + col
    }
}
