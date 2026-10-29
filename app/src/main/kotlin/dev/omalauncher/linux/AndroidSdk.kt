package dev.omalauncher.linux

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.R

/**
 * A minimal Android SDK inside Linux, so a coding agent can scaffold a real Android app (not an owl
 * widget) and either build it into an APK right here, or push it to a new GitHub repo and let GitHub
 * Actions build it there instead (see [androidAppCommand]) — more reliable, since GitHub's own
 * runners have Google's real build tools with no ARM64 workaround needed. A JDK, Google's
 * command-line tools, one platform, one build-tools and the GitHub CLI (`gh`) are what either path
 * needs; all of that is plain JVM bytecode or a regular Linux tool and runs on any CPU. Only `aapt`,
 * `aapt2`, `aidl` and `zipalign` are native code, and Google only publishes those four as x86_64
 * Linux binaries — so [install] swaps them for an ARM64 build, for the local build path. `d8` and
 * `apksigner` are JVM tools too and need no swap.
 *
 * Unlike [Bootstrap]'s `CLIAMP_URL` or [Gpu]'s drivers, that ARM64 swap isn't pinned to one version
 * with a checked hash: it tracks lzhiyong/android-sdk-tools' own releases (GitHub's
 * `/releases/latest/download/`), a small, actively built, source-built (AOSP via CMake, statically
 * linked) third-party project, not Google's own binaries. That's a real trust tradeoff — anyone
 * uneasy with it can skip the local build and rely on the GitHub one instead.
 */
class AndroidSdk(
    private val proot: Proot,
    private val pacman: Pacman,
) {
    private val sdkDir = "${Proot.GUEST_HOME}/.android-sdk"

    /** Where a new Android app project goes; see [androidAppCommand]. */
    val projectsDir = "${Proot.GUEST_HOME}/android-apps"

    var state by mutableStateOf<JobState>(JobState.Idle)
        private set

    /** Whether the SDK looks set up: cmdline-tools' sdkmanager and the swapped-in aapt2 are both there. */
    fun installed(): Boolean =
        proot.hostFile("$sdkDir/cmdline-tools/latest/bin/sdkmanager").isFile &&
            proot.hostFile("$sdkDir/build-tools/$BUILD_TOOLS/aapt2").isFile

    /** Downloads and sets all of the above up; safe to call again (every step is skip-if-done or idempotent). */
    suspend fun install(): Boolean {
        if (state is JobState.Working) return false
        val (ok, _) = proot.trackedJob(R.string.androidsdk_job_installing, SCRIPT, pacman.lock) { state = it }
        return ok
    }

    private val SCRIPT =
        pacmanInstall(listOf("jdk-openjdk", "gradle", "unzip", "github-cli")) + " && " +
            "mkdir -p $sdkDir ~/android-apps ~/.gradle && " +
            "{ [ -x $sdkDir/cmdline-tools/latest/bin/sdkmanager ] || { " +
            "curl -fL -o /tmp/cmdline-tools.zip ${shellQuote(CMDLINE_TOOLS_URL)} && " +
            "echo '$CMDLINE_TOOLS_SHA256  /tmp/cmdline-tools.zip' | sha256sum -c - && " +
            "rm -rf $sdkDir/cmdline-tools && unzip -q /tmp/cmdline-tools.zip -d $sdkDir/cmdline-tools && " +
            "mv $sdkDir/cmdline-tools/cmdline-tools $sdkDir/cmdline-tools/latest && rm /tmp/cmdline-tools.zip; }; } && " +
            "yes | $sdkDir/cmdline-tools/latest/bin/sdkmanager --sdk_root=$sdkDir " +
            "${shellQuote("platform-tools")} ${shellQuote("platforms;android-$PLATFORM")} ${shellQuote("build-tools;$BUILD_TOOLS")} && " +
            "curl -fL -o /tmp/android-sdk-tools-aarch64.zip ${shellQuote(ARM64_TOOLS_URL)} && " +
            "rm -rf /tmp/android-sdk-tools-aarch64 && unzip -q /tmp/android-sdk-tools-aarch64.zip -d /tmp/android-sdk-tools-aarch64 && " +
            "cp /tmp/android-sdk-tools-aarch64/build-tools/aapt /tmp/android-sdk-tools-aarch64/build-tools/aapt2 " +
            "/tmp/android-sdk-tools-aarch64/build-tools/aidl /tmp/android-sdk-tools-aarch64/build-tools/zipalign $sdkDir/build-tools/$BUILD_TOOLS/ && " +
            "chmod +x $sdkDir/build-tools/$BUILD_TOOLS/aapt $sdkDir/build-tools/$BUILD_TOOLS/aapt2 $sdkDir/build-tools/$BUILD_TOOLS/aidl " +
            "$sdkDir/build-tools/$BUILD_TOOLS/zipalign && rm -rf /tmp/android-sdk-tools-aarch64 /tmp/android-sdk-tools-aarch64.zip && " +
            // AGP downloads its own aapt2 from Maven (an x86_64 build) unless told otherwise; the SDK's build-tools
            // copy, which is now the ARM64 one, is what every new project should point it at.
            "{ grep -q aapt2FromMavenOverride ~/.gradle/gradle.properties 2>/dev/null || " +
            "echo \"android.aapt2FromMavenOverride=$sdkDir/build-tools/$BUILD_TOOLS/aapt2\" >> ~/.gradle/gradle.properties; }"

    private companion object {
        const val PLATFORM = "35"
        const val BUILD_TOOLS = "35.0.0"

        // developer.android.com/studio#command-tools, the Linux command-line-tools-only package.
        const val CMDLINE_TOOLS_URL = "https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip"
        const val CMDLINE_TOOLS_SHA256 = "4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583"

        // Not pinned to a tag: GitHub's own "latest release" redirect, so it keeps following that project's releases.
        const val ARM64_TOOLS_URL = "https://github.com/lzhiyong/android-sdk-tools/releases/latest/download/android-sdk-tools-static-aarch64.zip"
    }
}

/**
 * Starts [agentBin] in [AndroidSdk.projectsDir], told the SDK's set up, to scaffold a real Android
 * app (as opposed to an owl widget, which stays inside the launcher) and get it built, preferring a
 * push to GitHub over building right here; see [AndroidSdk.install].
 */
fun androidAppCommand(agentBin: String): String {
    val prompt =
        "A JDK, Gradle, the Android SDK and the GitHub CLI (gh) are already set up here: ANDROID_HOME is " +
            "~/.android-sdk, with one platform and build-tools installed, and aapt2/aapt/aidl/zipalign are an " +
            "ARM64 build (this is a phone, not a PC), already set as android.aapt2FromMavenOverride in " +
            "~/.gradle/gradle.properties so a new project's own Gradle-downloaded aapt2 (an x86_64 build, which " +
            "won't run here) is never used. Ask me what Android app to build, then scaffold a new Gradle/Kotlin " +
            "project for it in its own folder here (the system gradle command can write its wrapper).\n\n" +
            "Prefer building it on GitHub over building it here: GitHub's own runners have Google's real " +
            "x86_64 build tools, so there's nothing to work around there, while building right here depends on " +
            "a third-party ARM64 port of a few tools that may lag behind. To do that: run `gh auth status`, and " +
            "if it says I'm not logged in, tell me to run `gh auth login` myself in this same terminal (it's an " +
            "interactive browser login only I can do) and wait for me to say I'm done. If a commit fails asking " +
            "who I am, set a placeholder `git config --global user.name`/`user.email` rather than stopping. " +
            "Once gh is signed in, run " +
            "`gh repo create <name> --private --source=. --remote=origin --push` to create the repo and push the " +
            "project, add `.github/workflows/build.yml` (actions/checkout, actions/setup-java with " +
            "distribution temurin and a java-version that matches the Android Gradle Plugin version you used, " +
            "then `./gradlew assembleDebug`, then `gh release create` tagged by the run number or date with the " +
            "built .apk attached and `permissions: contents: write` set so GITHUB_TOKEN can create it) on push " +
            "to main, commit and push that workflow, then run `gh run watch` to follow the build and give me " +
            "the release's download link once it finishes.\n\n" +
            "If I'd rather see something working right away, a quick `./gradlew assembleDebug` here first is " +
            "fine too (tell me where the .apk ended up so I can open it in the launcher's Files app and install " +
            "it), but say plainly if it fails that the GitHub build is the one to trust."
    return "cd ~/android-apps && ${agentInvocation(agentBin, prompt)}; exec bash"
}
