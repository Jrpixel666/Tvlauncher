package dev.omalauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.omalauncher.R
import dev.omalauncher.Runtime
import dev.omalauncher.linux.GgufFile
import dev.omalauncher.linux.HfModel
import dev.omalauncher.linux.HuggingFace
import dev.omalauncher.linux.JobState
import dev.omalauncher.linux.LocalAi
import dev.omalauncher.linux.LocalModel
import dev.omalauncher.linux.ModelCategory
import dev.omalauncher.linux.Package
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Local, offline text generation: search Hugging Face for a model, see which ones have a GGUF build
 * (llama.cpp's format, the only kind small enough to run here), download one and pick it as the default.
 * llama.cpp itself installs just below, through the same packages screen uses.
 */
@Composable
fun LocalAiScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
    onChat: (LocalModel) -> Unit,
    onBuildApp: () -> Unit,
) {
    val localAi = runtime.localAi
    val scope = rememberCoroutineScope()
    var coding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var compatibleOnly by remember { mutableStateOf(true) }
    val loaded by
        produceState<Pair<String, List<HfModel>>?>(null, query, compatibleOnly) {
            val q = query.trim()
            if (q.isNotEmpty()) delay(350) // debounce typing, as the packages search does
            value = q to HuggingFace.search(q, ggufOnly = compatibleOnly)
        }
    val searching = loaded?.first != query.trim()
    val results = loaded?.second.orEmpty()
    val grouped = remember(results) { results.groupBy { it.category } }

    OverlayPanel(stringResource(R.string.localai_title), onDismiss, contentWidth = ReadingWidth) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { BasicText(stringResource(R.string.localai_intro), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small)) }
            item { EngineSection(runtime) }
            item { CodeSection(localAi, onOpen = { coding = true }) }
            item { AndroidAppSection(runtime, onBuildApp) }
            if (localAi.models.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.localai_downloaded)) }
                items(localAi.models, key = { it.name }) { model ->
                    DownloadedModelRow(
                        model,
                        isDefault = model.name == localAi.preferred,
                        onChoose = { localAi.choose(model) },
                        onRemove = { localAi.remove(model) },
                        onChat = { onChat(model) },
                    )
                }
            }
            item { SuggestionsRow(localAi) }
            item {
                Column {
                    SectionLabel(stringResource(R.string.localai_browse))
                    SearchField(query, onChange = { query = it }, onGo = {}, placeholder = stringResource(R.string.localai_search_placeholder))
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        BracketAction(
                            stringResource(if (compatibleOnly) R.string.localai_compatible_on else R.string.localai_compatible_off),
                            if (compatibleOnly) Palette.accent else Palette.muted,
                            onClick = { compatibleOnly = !compatibleOnly },
                        )
                    }
                    val status =
                        when {
                            searching -> stringResource(R.string.packages_searching)
                            results.isEmpty() -> stringResource(R.string.localai_none)
                            else -> pluralStringResource(R.plurals.packages_results, results.size, results.size)
                        }
                    BasicText(status, Modifier.padding(top = 6.dp), style = Mono.copy(color = Palette.muted))
                }
            }
            if (!searching) {
                for (category in ModelCategory.entries) {
                    val models = grouped[category].orEmpty()
                    if (models.isEmpty()) continue
                    item(key = "cat-${category.name}") { SectionLabel(stringResource(category.label)) }
                    items(models, key = { it.id }) { model -> ModelRow(model, localAi, scope) }
                }
            }
        }
    }
    if (coding) LocalAiCodeScreen(runtime, onDismiss = { coding = false })
}

/** The "code with it" row: opens [LocalAiCodeScreen], the local model writing files straight to disk. */
@Composable
private fun CodeSection(
    localAi: LocalAi,
    onOpen: () -> Unit,
) {
    Row(Modifier.card(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            BasicText(stringResource(R.string.localai_code_title), style = Mono.copy(fontSize = TextSize.Item))
            BasicText(stringResource(R.string.localai_code_intro), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        }
        if (localAi.default != null) {
            BracketAction(stringResource(R.string.localai_code_open), Palette.accent, onClick = onOpen)
        } else {
            BasicText(stringResource(R.string.localai_code_needs_model), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        }
    }
}

/** llama.cpp, searched the same way the packages screen searches anything else; installing it needs no code of our own. */
@Composable
private fun EngineSection(runtime: Runtime) {
    val pacman = runtime.pacman
    val revision = pacman.revision.intValue
    val matches by produceState(emptyList<Package>(), revision) { value = pacman.search(ENGINE_QUERY) }
    val scope = rememberCoroutineScope()
    Column(Modifier.card(10.dp)) {
        BasicText(stringResource(R.string.localai_engine_title), style = Mono.copy(fontSize = TextSize.Item))
        BasicText(stringResource(R.string.localai_engine_intro), Modifier.padding(top = 2.dp, bottom = 6.dp), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        if (matches.isEmpty()) {
            BasicText(stringResource(R.string.packages_loading), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        } else {
            for (pkg in matches) {
                val state = pacman.states[pkg.name] ?: JobState.Idle
                ActionsRow(
                    actions = {
                        when {
                            state is JobState.Working -> BasicText("${stringResource(state.action)}…", style = Mono.copy(color = Palette.accent))
                            pkg.installed -> BracketAction(stringResource(R.string.packages_remove), Palette.red) { scope.launch { pacman.remove(pkg.name) } }
                            else -> BracketAction(stringResource(R.string.packages_install), Palette.accent) { scope.launch { pacman.install(pkg) } }
                        }
                    },
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        BasicText(pkg.name, style = Mono.copy(fontSize = TextSize.Item))
                        Spacer(Modifier.padding(start = 8.dp))
                        BasicText(listOfNotNull(pkg.repo, pkg.version).joinToString(" "), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                    }
                }
                JobLog(state)
            }
        }
    }
}

/**
 * A separate, second window from the chat one: a real Android project, built into an APK by a cloud
 * coding agent (small local models aren't capable enough for that), in an environment set up once so
 * `./gradlew assembleDebug` works on this phone's CPU; see [dev.omalauncher.linux.AndroidSdk].
 */
@Composable
private fun AndroidAppSection(
    runtime: Runtime,
    onBuildApp: () -> Unit,
) {
    val sdk = runtime.androidSdk
    val scope = rememberCoroutineScope()
    val state = sdk.state
    val installed = sdk.installed()
    val hasAgent = runtime.agents.default != null
    Column(Modifier.card(10.dp)) {
        ActionsRow(
            actions = {
                when {
                    state is JobState.Working -> BasicText("${stringResource(state.action)}…", style = Mono.copy(color = Palette.accent))
                    installed && hasAgent -> BracketAction(stringResource(R.string.localai_android_build), Palette.accent, onClick = onBuildApp)
                    installed -> BasicText(stringResource(R.string.localai_android_needs_agent), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                    else -> BracketAction(stringResource(R.string.localai_android_install), Palette.accent) { scope.launch { sdk.install() } }
                }
            },
        ) {
            Column {
                BasicText(stringResource(R.string.localai_android_title), style = Mono.copy(fontSize = TextSize.Item))
                BasicText(stringResource(R.string.localai_android_intro), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
        }
        JobLog(state)
    }
}

@Composable
private fun DownloadedModelRow(
    model: LocalModel,
    isDefault: Boolean,
    onChoose: () -> Unit,
    onRemove: () -> Unit,
    onChat: () -> Unit,
) {
    Column(Modifier.card(10.dp)) {
        ActionsRow(
            actions = {
                BracketAction(stringResource(R.string.localai_chat), Palette.accent, onClick = onChat)
                if (!isDefault) BracketAction(stringResource(R.string.localai_use), Palette.accent, onClick = onChoose)
                BracketAction(stringResource(R.string.packages_remove), Palette.red, onClick = onRemove)
            },
        ) {
            Column {
                BasicText(model.name, style = Mono.copy(fontSize = TextSize.Item))
                BasicText(
                    if (isDefault) stringResource(R.string.localai_default_tag, formatSize(model.sizeBytes)) else formatSize(model.sizeBytes),
                    style = Mono.copy(color = if (isDefault) Palette.accent else Palette.muted, fontSize = TextSize.Small),
                )
            }
        }
    }
}

@Composable
private fun SuggestionsRow(localAi: LocalAi) {
    Row(Modifier.card(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.padding(end = 8.dp)) {
            BasicText(stringResource(R.string.localai_suggestions_title), style = Mono.copy(fontSize = TextSize.Item))
            BasicText(stringResource(R.string.localai_suggestions_intro), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        }
        Spacer(Modifier.padding(start = 8.dp))
        BracketAction(
            stringResource(if (localAi.suggestionsEnabled) R.string.localai_suggestions_on else R.string.localai_suggestions_off),
            if (localAi.suggestionsEnabled) Palette.accent else Palette.muted,
            onClick = { localAi.setSuggestionsEnabled(!localAi.suggestionsEnabled) },
        )
    }
}

/** A Hugging Face search result; tapping it lists its GGUF files (sizes included) to download. */
@Composable
private fun ModelRow(
    model: HfModel,
    localAi: LocalAi,
    scope: CoroutineScope,
) {
    var expanded by remember(model.id) { mutableStateOf(false) }
    Column(Modifier.card(10.dp)) {
        Row {
            Column(Modifier.weight(1f)) {
                BasicText(model.id, style = Mono.copy(fontSize = TextSize.Item), maxLines = 1)
                BasicText(
                    stringResource(R.string.localai_model_stats, model.downloads, model.likes),
                    style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                )
            }
            BracketAction(stringResource(if (expanded) R.string.localai_hide_files else R.string.localai_show_files), Palette.accent) { expanded = !expanded }
        }
        if (expanded) {
            val files by produceState<List<GgufFile>>(emptyList(), model.id) { value = HuggingFace.files(model.id) }
            if (files.isEmpty()) {
                BasicText(stringResource(R.string.localai_no_gguf), Modifier.padding(top = 6.dp), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            } else {
                for (file in files) {
                    val name = file.filename.substringAfterLast('/')
                    val state = localAi.states[name] ?: JobState.Idle
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            BasicText(name, style = Mono.copy(fontSize = TextSize.Small), maxLines = 1)
                        }
                        when {
                            state is JobState.Working -> BasicText("${stringResource(state.action)}…", style = Mono.copy(color = Palette.accent, fontSize = TextSize.Small))
                            else -> BasicText(formatSize(file.sizeBytes), Modifier.padding(end = 8.dp), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                        }
                        if (state !is JobState.Working) {
                            BracketAction(stringResource(R.string.localai_download), Palette.accent) { scope.launch { localAi.download(model, file) } }
                        }
                    }
                    JobLog(state)
                }
            }
        }
    }
}

internal fun formatSize(bytes: Long): String =
    when {
        bytes <= 0 -> ""
        bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1e9)
        bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1e6)
        else -> "%.0f KB".format(bytes / 1e3)
    }

/** What [EngineSection] searches for: llama.cpp itself, and any GPU-accelerated build the AUR has for this phone's driver. */
private const val ENGINE_QUERY = "llama.cpp"
