package dev.omalauncher.linux

import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSdkTest {
    /** The agent's prompt (long, full of contractions and backtick-quoted commands) must survive shell-quoting. */
    @Test fun androidAppCommandIsValidShellForEveryAgent() {
        for (agent in AGENTS) assertShellSyntaxOk(androidAppCommand(agent.bin))
    }

    @Test fun androidAppCommandStartsInTheProjectsDir() {
        assertTrue(androidAppCommand("claude").startsWith("cd ~/android-apps && "))
    }
}
