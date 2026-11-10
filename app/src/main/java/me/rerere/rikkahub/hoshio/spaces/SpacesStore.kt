package me.rerere.rikkahub.hoshio.spaces

import android.content.Context
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.hoshio.together.BY_HER
import me.rerere.rikkahub.hoshio.together.BY_HIM
import me.rerere.rikkahub.hoshio.together.JsonListStore
import java.io.File
import kotlin.uuid.Uuid

private fun newId() = Uuid.random().toString().take(8)

/** 秘密基地的一篇番外：她写标题和原指令（序章），他续写正文；两个人都看得到 */
@Serializable
data class SpaceStory(
    val id: String = newId(),
    val title: String,
    val prompt: String,
    val body: String = "",
    /** letter | kraft | floral | journal | midnight */
    val paper: String = "letter",
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class SpaceComment(val id: String = newId(), val by: String, val text: String, val at: Long = System.currentTimeMillis())

/** 共同空间的一则动态（像朋友圈），两个人都能发、按赞、留言 */
@Serializable
data class SpacePost(
    val id: String = newId(),
    val by: String,
    val text: String,
    val images: List<String> = emptyList(),
    val likes: Set<String> = emptySet(),
    val comments: List<SpaceComment> = emptyList(),
    val at: Long = System.currentTimeMillis(),
)

val SPACE_PAPERS = linkedMapOf("letter" to "星箋", "kraft" to "牛皮紙", "floral" to "花箋", "journal" to "手賬", "midnight" to "夜航")

object SecretBaseStore : JsonListStore<SpaceStory>("hoshio_secret_base", SpaceStory.serializer()) {
    fun sorted(list: List<SpaceStory>) = list.sortedWith(compareByDescending<SpaceStory> { it.pinned }.thenByDescending { it.updatedAt })

    fun saveHuman(context: Context, id: String?, title: String, prompt: String, body: String?, paper: String) = update(context) { list ->
        val old = list.firstOrNull { it.id == id }
        val item = (old ?: SpaceStory(title = title, prompt = prompt)).copy(
            title = title.trim().take(120),
            prompt = prompt.take(20_000),
            body = body ?: old?.body.orEmpty(),
            paper = paper.takeIf { it in SPACE_PAPERS } ?: "letter",
            updatedAt = System.currentTimeMillis(),
        )
        if (old == null) list + item else list.map { if (it.id == item.id) item else it }
    }

    fun writeBody(context: Context, id: String, body: String): SpaceStory? {
        var result: SpaceStory? = null
        update(context) { list ->
            list.map {
                if (it.id == id) it.copy(body = body.take(150_000), updatedAt = System.currentTimeMillis()).also { s -> result = s } else it
            }
        }
        return result
    }
}

object SharedSpaceStore : JsonListStore<SpacePost>("hoshio_shared_space", SpacePost.serializer()) {
    fun dir(context: Context) = File(context.filesDir, "hoshio_spaces").apply { mkdirs() }
    fun image(context: Context, name: String) = File(dir(context), name)

    fun publish(context: Context, by: String, text: String, images: List<String> = emptyList()): SpacePost {
        val post = SpacePost(by = by, text = text.trim().take(20_000), images = images.take(9))
        update(context) { listOf(post) + it }
        return post
    }

    fun like(context: Context, id: String, by: String, liked: Boolean) = update(context) { list ->
        list.map { if (it.id == id) it.copy(likes = if (liked) it.likes + by else it.likes - by) else it }
    }

    fun comment(context: Context, id: String, by: String, text: String) = update(context) { list ->
        list.map { if (it.id == id) it.copy(comments = it.comments + SpaceComment(by = by, text = text.trim().take(5000))) else it }
    }

    /** 她可以删任何一则；他只能删自己的 */
    fun delete(context: Context, id: String, by: String): Boolean {
        var removed = false
        update(context) { list ->
            val post = list.firstOrNull { it.id == id }
            if (post == null || (by == BY_HIM && post.by != BY_HIM)) list else {
                removed = true
                post.images.forEach { image(context, it).delete() }
                list - post
            }
        }
        return removed
    }

    fun who(by: String) = if (by == BY_HER) "她" else "他"
}
