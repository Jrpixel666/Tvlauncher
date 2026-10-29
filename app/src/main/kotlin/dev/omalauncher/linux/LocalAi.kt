package dev.omalauncher.linux

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.LauncherData
import dev.omalauncher.R
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import java.io.File
import java.util.Calendar

/** A GGUF model downloaded into [LocalAi]'s models directory, ready for llama.cpp. */
data class LocalModel(
    val file: File,
) {
    val name: String get() = file.name
    val sizeBytes: Long get() = file.length()
}

/**
 * Local, offline text generation: Hugging Face's GGUF models (browsed and downloaded in
 * [dev.omalauncher.ui.LocalAiScreen]), run by llama.cpp inside Linux once they're on disk. llama.cpp
 * itself installs like any other package, through [Pacman] (search "llama.cpp": the repos have the
 * CPU build, the AUR sometimes a GPU one for this phone's driver) — there's nothing of our own to install.
 *
 * Widgets reach the default model the way they reach anything else in Linux: a `cmd` or `stream` source
 * running `omalauncher-ai "prompt"` ([managed]'s shim), which prints its reply on stdout. The same model
 * can also propose which apps to show first on the home screen; see [refreshSuggestions].
 */
class LocalAi(
    private val context: Context,
    private val proot: Proot,
) {
    private val modelsDir get() = LauncherData.modelsDir(context)
    private val guestModelsDir = "${Proot.GUEST_HOME}/.local/share/omalauncher/models"
    private val preferredFile = File(proot.launcherConfig, "model")
    private val prefs = LauncherData.prefs(context)

    /** One guest shell kept alive for quick prompts (suggestions, a settings-screen "ask"); see [GuestShell]. */
    private val shell = GuestShell(proot)

    /** Download progress by file name; see [Pacman.states]. */
    val states = mutableStateMapOf<String, JobState>()

    /** Downloaded models, newest first. */
    var models by mutableStateOf<List<LocalModel>>(emptyList())
        private set

    /** The model llama.cpp uses when nothing else says otherwise: the one picked, else the newest download. */
    var preferred by mutableStateOf<String?>(null)
        private set

    val default: LocalModel? get() = models.firstOrNull { it.name == preferred } ?: models.firstOrNull()

    /**
     * Whether the home screen may ask the default model to reorder the recent-apps strip; off by default,
     * since every refresh costs it a few seconds of generation. See [refreshSuggestions] and
     * [dev.omalauncher.ui.recentApps].
     */
    var suggestionsEnabled by mutableStateOf(prefs.getBoolean(SUGGESTIONS_ENABLED, false))
        private set

    fun setSuggestionsEnabled(on: Boolean) {
        suggestionsEnabled = on
        prefs.edit().putBoolean(SUGGESTIONS_ENABLED, on).apply()
        if (!on) suggestedKeys = emptyList()
    }

    /** App keys [refreshSuggestions] last proposed, most-likely first; empty until it has run once, or while it's off. */
    var suggestedKeys by mutableStateOf<List<String>>(emptyList())
        private set

    /** Reads what's on disk; call off the main thread before the UI needs [models] or [default]. */
    fun load() {
        modelsDir.mkdirs()
        models = modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }.orEmpty().sortedByDescending { it.lastModified() }.map(::LocalModel)
        preferred = runCatching { preferredFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun choose(model: LocalModel) {
        preferred = model.name
        runCatching {
            preferredFile.parentFile?.mkdirs()
            preferredFile.writeText(model.name + "\n")
        }.onFailure { Log.w(TAG, "cannot save the default model", it) }
    }

    /** Downloads [file] of [model] with curl, into the models directory; progress shows like any other guest job. */
    suspend fun download(
        model: HfModel,
        file: GgufFile,
    ): Boolean {
        val name = file.filename.substringAfterLast('/')
        val url = HuggingFace.downloadUrl(model.id, file.filename)
        val script = "mkdir -p ${shellQuote(guestModelsDir)} && curl -fL --progress-bar -o ${shellQuote("$guestModelsDir/$name")} ${shellQuote(url)}"
        val (ok, _) = proot.trackedJob(R.string.localai_job_downloading, script) { states[name] = it }
        if (ok) {
            load()
            if (preferred == null) models.firstOrNull { it.name == name }?.let(::choose)
        }
        return ok
    }

    /** Deletes a downloaded model; it's a plain file outside the rootfs, no guest job needed. */
    fun remove(model: LocalModel) {
        model.file.delete()
        if (preferred == model.name) {
            preferred = null
            preferredFile.delete()
        }
        load()
    }

    private fun guestPath(model: LocalModel?) = model?.let { shellQuote("$guestModelsDir/${it.name}") }

    /**
     * `llama-cli` asked [prompt] with [model] (or [default]); null without one. `-ngl` always asks for full
     * GPU offload: on a build with no GPU backend (most phones don't have a supported one; see [Gpu]) it's
     * simply ignored and llama.cpp runs on the CPU.
     */
    fun invocation(
        prompt: String,
        model: LocalModel? = default,
    ): String? {
        val path = guestPath(model) ?: return null
        return "llama-cli -m $path -no-cnv --no-display-prompt -ngl 999 -n 256 -p ${shellQuote(prompt)} 2>/dev/null"
    }

    /** An interactive chat with [model] (or [default]) for a terminal tile; null without one. */
    fun chatInvocation(model: LocalModel? = default): String? {
        val path = guestPath(model) ?: return null
        return "llama-cli -m $path -cnv -ngl 999"
    }

    /** Runs [prompt] through [default] and returns its reply; null without a model, or if it didn't answer in time. */
    suspend fun ask(
        prompt: String,
        timeoutSeconds: Int = 40,
    ): String? {
        val command = invocation(prompt) ?: return null
        val result = shell.run(command, timeoutSeconds, retry = false)
        return result.out.trim().takeIf { it.isNotEmpty() && !result.timedOut }
    }

    /**
     * Asks [default] which of [candidates] (app key to label, most-recently-used first) fit the time of
     * day, and keeps its pick in [suggestedKeys] for [dev.omalauncher.ui.recentApps] to try first. Silent
     * on any failure or nonsense reply: that just leaves the strip in its usual, usage-based order.
     */
    suspend fun refreshSuggestions(candidates: List<Pair<String, String>>) {
        if (!suggestionsEnabled || default == null || candidates.size < 3) return
        val byLabel = candidates.associate { (key, label) -> label.lowercase() to key }
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val prompt =
            "It's $hour:00. I open these apps, most recent first: ${candidates.joinToString(", ") { it.second }}. " +
                "Which of them am I most likely to open right now? Answer with only app names from that list, one per line, most likely first, nothing else."
        val reply = runCatching { ask(prompt, timeoutSeconds = 25) }.getOrNull() ?: return
        val picked =
            reply
                .lineSequence()
                .map { it.trim().trimStart('-', '*', '•', ' ').trim() }
                .mapNotNull { byLabel[it.lowercase()] }
                .distinct()
                .toList()
        if (picked.isNotEmpty()) suggestedKeys = picked
    }

    /** The shim at ~/.local/bin/omalauncher-ai, so widgets and the prompt bar can ask the default model without knowing its path. */
    fun managed(): List<Managed> = listOf(Managed.text(File(proot.home, ".local/bin/omalauncher-ai"), Policy.Owned, executable = true) { SHIM })

    private companion object {
        const val TAG = "localai"
        const val SUGGESTIONS_ENABLED = "localai_suggestions"

        val SHIM =
            """
            #!/bin/sh
            # Written by the launcher: the default local model's one-off answer to a prompt.
            model=$(cat ~/.config/omalauncher/model 2>/dev/null)
            dir=~/.local/share/omalauncher/models
            if [ -z "${'$'}model" ] || [ ! -f "${'$'}dir/${'$'}model" ]; then
              echo "no local model set: settings > local ai" >&2
              exit 1
            fi
            exec llama-cli -m "${'$'}dir/${'$'}model" -no-cnv --no-display-prompt -ngl 999 -n 256 -p "${'$'}*" 2>/dev/null
            """.trimIndent() + "\n"
    }
}
