package dev.omalauncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.omalauncher.R
import dev.omalauncher.Runtime
import dev.omalauncher.linux.AppliedFile
import kotlinx.coroutines.launch

/** One request/reply in this screen; kept only for the session, nothing here is saved. */
private data class CodeTurn(
    val prompt: String,
    val reply: String?,
    val files: List<AppliedFile>,
    val failed: Boolean,
)

/**
 * A second way to use the local model besides chat: ask it for files instead of an answer to read,
 * and it writes what it generates straight into a project folder under
 * [dev.omalauncher.linux.AndroidSdk.projectsDir] (the same one "build an app" uses), so its cloud
 * agent can pick up from there to get the project building and pushed to GitHub.
 * See [dev.omalauncher.linux.LocalAi.autoApply].
 */
@Composable
fun LocalAiCodeScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val localAi = runtime.localAi
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf("app") }
    var prompt by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val turns = remember { mutableStateListOf<CodeTurn>() }

    fun send() {
        val text = prompt.trim()
        if (text.isEmpty() || working) return
        prompt = ""
        working = true
        val dir = "${runtime.androidSdk.projectsDir}/${project.trim().ifEmpty { "app" }}"
        scope.launch {
            val result = runCatching { localAi.autoApply(dir, text) }.getOrNull()
            turns.add(0, CodeTurn(text, result?.reply, result?.files.orEmpty(), result == null))
            working = false
        }
    }

    BackHandler(onBack = onDismiss)
    OverlayPanel(stringResource(R.string.localai_code_title), onDismiss, contentWidth = ReadingWidth) {
        BasicText(stringResource(R.string.localai_code_screen_intro), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        Row(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(stringResource(R.string.localai_code_project), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            Spacer(Modifier.padding(start = 6.dp))
            BasicTextField(
                value = project,
                onValueChange = { project = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = Mono.copy(fontSize = TextSize.Small),
                cursorBrush = SolidColor(Palette.accent),
            )
        }
        SearchField(prompt, onChange = { prompt = it }, onGo = ::send, placeholder = stringResource(R.string.localai_code_placeholder))
        Row(Modifier.padding(top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (working) {
                BasicText(stringResource(R.string.localai_code_working), style = Mono.copy(color = Palette.accent))
            } else {
                BracketAction(stringResource(R.string.localai_code_send), Palette.accent, onClick = ::send)
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(turns) { turn -> TurnRow(turn) }
        }
    }
}

@Composable
private fun TurnRow(turn: CodeTurn) {
    Column(Modifier.card(10.dp)) {
        BasicText(turn.prompt, style = Mono.copy(color = Palette.accent, fontSize = TextSize.Small), maxLines = 3)
        when {
            turn.failed -> BasicText(stringResource(R.string.localai_code_failed), Modifier.padding(top = 4.dp), style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
            else -> {
                turn.reply?.takeIf { it.isNotBlank() }?.let {
                    BasicText(it, Modifier.padding(top = 4.dp), style = Mono.copy(fontSize = TextSize.Small))
                }
                for (file in turn.files) {
                    BasicText(
                        stringResource(R.string.localai_code_wrote, file.path, formatSize(file.sizeBytes)),
                        Modifier.padding(top = 4.dp),
                        style = Mono.copy(color = Palette.green, fontSize = TextSize.Small),
                    )
                }
            }
        }
    }
}
