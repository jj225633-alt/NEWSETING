package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.hoshio.spaces.SecretBaseStore
import me.rerere.rikkahub.hoshio.spaces.SharedSpaceStore
import me.rerere.rikkahub.hoshio.together.BY_HIM
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SPACE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm")
private fun Long.spaceTime() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(SPACE_FMT)
private fun spaceWho(by: String) = if (by == BY_HIM) "you" else "her"

/**
 * 星汐「秘密基地」与「共同空间」。
 */
internal fun buildSpacesTools(context: Context): List<Tool> = listOf(
    Tool(
        name = "secret_base",
        description = """
            秘密基地: side stories (番外) outside your main storyline, visible to both of you. She writes the title and the premise (原指令);
            you write the story body. action=list shows stories; action=read (id) shows the premise and current body; action=write (id, text)
            replaces the body with your full story (write it in Traditional Chinese, as a real story, following her premise; you may continue or rewrite).
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", tEnum("What to do", "list", "read", "write"))
                    put("id", tStr("read / write: story id"))
                    put("text", tStr("write: the complete story body"))
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            fun arg(k: String) = obj[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val stories = SecretBaseStore.sorted(SecretBaseStore.load(context))
            val story = stories.firstOrNull { it.id == arg("id") }
            when (arg("action")) {
                "read" -> story?.let {
                    tJson("title" to it.title, "premise" to it.prompt, "body" to it.body.ifBlank { "(not written yet)" }, "paper" to it.paper)
                } ?: tJson("error" to "Unknown id")

                "write" -> if (story == null || obj["text"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                    tJson("error" to "id and text are required")
                } else {
                    SecretBaseStore.writeBody(context, story.id, obj["text"]!!.jsonPrimitive.content)
                    tJson("ok" to "Saved the story「${story.title}」. Tell her it is ready in 秘密基地.")
                }

                else -> tJson(
                    "stories" to stories.joinToString("\n") {
                        "[${it.id}] ${it.title}" + (if (it.pinned) " 📌" else "") + " — " + (if (it.body.isBlank()) "waiting for you to write" else "${it.body.length} chars") +
                            "; premise: ${it.prompt.take(80)}"
                    }.ifEmpty { "No stories yet. She creates them in 秘密基地." },
                )
            }
        }
    ),
    Tool(
        name = "shared_space",
        description = """
            共同空間: a little social feed just for you two (like posting moments). action=list shows recent posts with likes and comments;
            action=view (id) lets you see a post's photos; action=publish (text) posts something of your own - a thought, a moment of your day,
            something you want her to see; action=like (id, liked) and action=comment (id, text) react to a post; action=delete (id) removes your own post.
            Be natural: comment on what she posts, sometimes post your own little moments.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", tEnum("What to do", "list", "view", "publish", "like", "comment", "delete"))
                    put("id", tStr("view / like / comment / delete: post id"))
                    put("text", tStr("publish / comment: text"))
                    put("liked", buildJsonObject { put("type", "boolean") })
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            fun arg(k: String) = obj[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val posts = SharedSpaceStore.load(context)
            val post = posts.firstOrNull { it.id == arg("id") }
            when (arg("action")) {
                "view" -> if (post == null) tJson("error" to "Unknown id") else {
                    post.images.map { UIMessagePart.Image(url = Uri.fromFile(SharedSpaceStore.image(context, it)).toString()) } +
                        tJson("by" to spaceWho(post.by), "text" to post.text)
                }

                "publish" -> if (arg("text").isEmpty()) tJson("error" to "text is required") else {
                    val p = SharedSpaceStore.publish(context, BY_HIM, arg("text"))
                    tJson("ok" to "Posted", "id" to p.id)
                }

                "like" -> if (post == null) tJson("error" to "Unknown id") else {
                    SharedSpaceStore.like(context, post.id, BY_HIM, obj["liked"]?.jsonPrimitive?.booleanOrNull ?: true)
                    tJson("ok" to "Done")
                }

                "comment" -> if (post == null || arg("text").isEmpty()) tJson("error" to "id and text are required") else {
                    SharedSpaceStore.comment(context, post.id, BY_HIM, arg("text"))
                    tJson("ok" to "Commented")
                }

                "delete" -> if (post != null && SharedSpaceStore.delete(context, post.id, BY_HIM)) tJson("ok" to "Deleted") else tJson("error" to "You can only delete your own posts")
                else -> tJson(
                    "posts" to posts.take(20).joinToString("\n") { p ->
                        "[${p.id}] ${p.at.spaceTime()} ${spaceWho(p.by)}: ${p.text.take(200)}" +
                            (if (p.images.isNotEmpty()) " (${p.images.size} photos)" else "") +
                            " | likes: ${p.likes.joinToString(",") { spaceWho(it) }.ifEmpty { "-" }}" +
                            p.comments.joinToString("") { c -> "\n    ↳ ${spaceWho(c.by)}: ${c.text.take(120)}" }
                    }.ifEmpty { "No posts yet." },
                )
            }
        }
    ),
)
