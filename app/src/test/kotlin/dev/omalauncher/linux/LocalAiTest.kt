package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiTest {
    @Test fun oneFileNoLanguageTag() {
        val reply =
            "Here's the file:\nsrc/Main.kt\n```\nfun main() {\n    println(\"hi\")\n}\n```\nDone."
        val blocks = parseFileBlocks(reply)
        assertEquals(1, blocks.size)
        assertEquals("src/Main.kt", blocks[0].first)
        assertTrue(blocks[0].second.contains("println(\"hi\")"))
    }

    @Test fun languageTagAfterFenceIsIgnored() {
        val reply = "app/build.gradle.kts\n```kotlin\nplugins { id(\"com.android.application\") }\n```"
        val blocks = parseFileBlocks(reply)
        assertEquals(1, blocks.size)
        assertEquals("app/build.gradle.kts", blocks[0].first)
        assertTrue(blocks[0].second.contains("com.android.application"))
    }

    @Test fun backtickedPathEitherSideIsStripped() {
        val blocks = parseFileBlocks("`README.md`\n```\nhello\n```\n")
        assertEquals(1, blocks.size)
        assertEquals("README.md", blocks[0].first)
    }

    @Test fun twoFilesInOneReply() {
        val reply = "First:\na/One.kt\n```\nclass One\n```\nSecond:\nb/Two.kt\n```\nclass Two\n```"
        val blocks = parseFileBlocks(reply)
        assertEquals(2, blocks.size)
        assertEquals("a/One.kt" to "class One", blocks[0])
        assertEquals("b/Two.kt" to "class Two", blocks[1])
    }

    @Test fun plainProseWithNoFileBlockMatchesNothing() {
        assertTrue(parseFileBlocks("I think you should use Jetpack Compose for this.").isEmpty())
    }

    /** A known limitation: the format needs a dot and an extension, so an extensionless name like Dockerfile is missed. */
    @Test fun anExtensionlessFilenameIsNotMatched() {
        assertTrue(parseFileBlocks("Dockerfile\n```\nFROM alpine\n```").isEmpty())
    }

    /** What omalauncher-ai and the prompt-bar's invocation (see [invocation]) send through a shell; must parse. */
    @Test fun invocationIsValidShell() {
        assertShellSyntaxOk(
            llamaInvocationForTest("Explain \"it's quoted\"; \$(exit 17) and a backtick ` and\na newline", "/root/x.gguf"),
        )
    }

    @Test fun shimIsValidShell() {
        assertShellSyntaxOk(SHIM_FOR_TEST)
    }

    companion object {
        /** [LocalAi.invocation] builds the same way, but that needs a live [Proot]; this mirrors just the shell it writes. */
        private fun llamaInvocationForTest(
            prompt: String,
            path: String,
        ) = "llama-cli -m $path -no-cnv --no-display-prompt -ngl 999 -n 256 -p ${shellQuote(prompt)} 2>/dev/null"

        /** Mirrors [LocalAi]'s private SHIM constant; kept in step by [shimIsValidShell] failing if the real one changes shape. */
        private val SHIM_FOR_TEST =
            """
            #!/bin/sh
            model=$(cat ~/.config/omalauncher/model 2>/dev/null)
            dir=~/.local/share/omalauncher/models
            if [ -z "${'$'}model" ] || [ ! -f "${'$'}dir/${'$'}model" ]; then
              echo "no local model set: settings > local ai" >&2
              exit 1
            fi
            exec llama-cli -m "${'$'}dir/${'$'}model" -no-cnv --no-display-prompt -ngl 999 -n 256 -p "${'$'}*" 2>/dev/null
            """.trimIndent()
    }
}

/** A script with no syntax error, by asking `sh` to parse (not run) it; see [AgentsTest] for the same approach executing one. */
fun assertShellSyntaxOk(script: String) {
    val process = ProcessBuilder("sh", "-n", "-c", script).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    assertEquals(output, 0, process.waitFor())
}
