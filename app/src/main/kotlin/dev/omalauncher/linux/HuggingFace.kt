package dev.omalauncher.linux

import android.util.Log
import androidx.annotation.StringRes
import dev.omalauncher.R
import dev.omalauncher.httpGet
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** One quantized file of a model on Hugging Face, small enough to list before deciding to download it. */
data class GgufFile(
    val filename: String,
    val sizeBytes: Long,
)

/** A broad grouping of what a model is for, so search results can be sorted into sections. */
enum class ModelCategory(
    @StringRes val label: Int,
) {
    Chat(R.string.localai_category_chat),
    Code(R.string.localai_category_code),
    Vision(R.string.localai_category_vision),
    Audio(R.string.localai_category_audio),
    Embedding(R.string.localai_category_embedding),
    Other(R.string.localai_category_other),
}

internal fun categoryOf(
    pipelineTag: String?,
    tags: List<String>,
): ModelCategory {
    val all = (listOfNotNull(pipelineTag) + tags).map { it.lowercase() }
    return when {
        all.any { "code" in it } -> ModelCategory.Code
        all.any { "image" in it || "vision" in it || "vqa" in it || "vl" == it } -> ModelCategory.Vision
        all.any { "audio" in it || "speech" in it || "whisper" in it } -> ModelCategory.Audio
        all.any { "embedding" in it || "feature-extraction" in it || "sentence-similarity" in it } -> ModelCategory.Embedding
        pipelineTag != null && ("generation" in pipelineTag || "conversational" in pipelineTag) -> ModelCategory.Chat
        all.any { "text-generation" in it || "chat" in it } -> ModelCategory.Chat
        else -> ModelCategory.Other
    }
}

/** A Hugging Face model from a search; its files (and their sizes) are listed separately, see [HuggingFace.files]. */
data class HfModel(
    val id: String,
    val downloads: Long,
    val likes: Long,
    val category: ModelCategory,
) {
    /** The repo name without its author, for a shorter row. */
    val name: String get() = id.substringAfter('/')
    val author: String get() = id.substringBefore('/', "")
}

/**
 * Hugging Face's public model hub, searched straight from Android, the same way [Aur] searches the AUR: no
 * proot needed for a search, only for running llama.cpp on what's downloaded.
 */
object HuggingFace {
    private const val TAG = "huggingface"
    private const val API = "https://huggingface.co/api/models"

    /**
     * Models matching [query] (everything, if blank), most-downloaded first. [ggufOnly] is the "compatible with
     * my device" filter: llama.cpp only runs a model that has a GGUF build at all, which rules out the great
     * majority of Hugging Face, where most repos hold a format meant for a GPU server.
     *
     * Throws on a network/parse failure instead of returning an empty list, so the caller can tell "no results"
     * from "the request failed" (a silent empty list there used to look exactly like a real empty search).
     */
    suspend fun search(
        query: String,
        ggufOnly: Boolean,
        limit: Int = 30,
    ): List<HfModel> {
        val q = query.trim()
        val url =
            buildString {
                append(API)
                append("?sort=downloads&direction=-1&limit=$limit")
                if (ggufOnly) append("&filter=gguf")
                if (q.isNotEmpty()) append("&search=").append(URLEncoder.encode(q, "UTF-8"))
            }
        val array =
            try {
                JSONArray(String(httpGet(url, readTimeout = 20_000)))
            } catch (e: Exception) {
                Log.w(TAG, "search failed", e)
                throw e
            }
        return (0 until array.length()).mapNotNull { i -> runCatching { array.getJSONObject(i).toModel() }.getOrNull() }
    }

    private fun JSONObject.toModel(): HfModel {
        val id = getString("id")
        val pipelineTag = if (isNull("pipeline_tag")) null else optString("pipeline_tag", null)
        val tags = optJSONArray("tags")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty()
        return HfModel(id, optLong("downloads", 0), optLong("likes", 0), categoryOf(pipelineTag, tags))
    }

    /** [id]'s GGUF files, smallest first, and their sizes, from the repo's file tree; empty if it has none. */
    suspend fun files(id: String): List<GgufFile> =
        runCatching {
            val url = "$API/${id.encodedPath()}/tree/main?recursive=true"
            val array = JSONArray(String(httpGet(url, readTimeout = 20_000)))
            (0 until array.length())
                .map { array.getJSONObject(it) }
                .filter { it.optString("type") == "file" && it.optString("path").endsWith(".gguf", ignoreCase = true) }
                .map { GgufFile(it.getString("path"), it.optLong("size", 0)) }
                .sortedBy { it.sizeBytes }
        }.getOrElse {
            Log.w(TAG, "files failed for $id", it)
            emptyList()
        }

    /** Where [file] of model [id] downloads from. */
    fun downloadUrl(
        id: String,
        file: String,
    ) = "https://huggingface.co/${id.encodedPath()}/resolve/main/${file.encodedPath()}"

    /** [this] as path segments, URL-encoded; [URLEncoder] is meant for a query string, so a space (it encodes as `+`) is fixed up to `%20`. */
    private fun String.encodedPath() = split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
}
