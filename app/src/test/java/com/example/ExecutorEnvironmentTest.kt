package com.example

import com.example.data.model.EnvironmentInfo
import com.example.data.model.ExecutorEnvironment
import com.example.data.model.ShellInfo
import org.junit.Assert.*
import org.junit.Test

class ExecutorEnvironmentTest {
    private fun linux(cwd: String = "file:///home/z/my-project") = EnvironmentInfo(
        cwd = cwd, userHomeDir = "file:///home/z", shell = ShellInfo("bash", "/bin/bash"), platformOs = "linux")

    @Test fun linuxUsesReportedShellAndProjectWithoutAndroidEnvironment() {
        val executor = ExecutorEnvironment.from(linux())
        assertEquals(listOf("/bin/bash", "-c", "pwd"), executor.commandArgv("pwd"))
        assertEquals(listOf("/bin/bash", "-l"), executor.interactiveArgv())
        assertEquals("file:///home/z/my-project", executor.defaultWorkingUri)
        assertTrue(executor.configUri.startsWith("file:///home/z/.local/state/codex-goal/workspaces/"))
        assertFalse(executor.termux)
        assertEquals("executor-inherited", executor.environmentProfile)
        assertTrue(executor.environment(false).isEmpty())
        assertEquals(mapOf("TERM" to "xterm-256color"), executor.environment(true))
    }

    @Test fun nativePreservesExistingWorkspaceSettingAndAdvertisedShell() {
        val executor = ExecutorEnvironment.from(EnvironmentInfo(cwd = "file:///data/data/com.termux/files/home",
            userHomeDir = "file:///data/data/com.termux/files/home", shell = ShellInfo("sh", "/bin/sh"), platformOs = "android"))
        assertTrue(executor.termux)
        assertEquals("/bin/sh", executor.shellPath)
        assertEquals("file:///data/data/com.termux/files/home/.codex", executor.defaultWorkingUri)
        assertEquals("file:///data/data/com.termux/files/home/.codex/android-client/workspace.json", executor.configUri)
        assertEquals("native-termux-configured", executor.environmentProfile)
        assertEquals(mapOf("HOME" to "/data/data/com.termux/files/home", "PREFIX" to "/data/data/com.termux/files/usr",
            "PATH" to "/data/data/com.termux/files/home/.local/bin:/data/data/com.termux/files/usr/bin:/system/bin"), executor.environment(false))
        assertEquals("xterm-256color", executor.environment(true)["TERM"])
    }

    @Test fun workspaceScopesRemainStableAndDifferentRootsDoNotShareSettings() {
        val first = ExecutorEnvironment.from(linux())
        assertEquals(first.configUri, ExecutorEnvironment.from(linux()).configUri)
        assertNotEquals(first.configUri, ExecutorEnvironment.from(linux("file:///home/z/other-project")).configUri)
    }

    @Test fun uriEscapedWorkspaceIsNotDoubleEncoded() {
        val uri = "file:///home/z/project%20with%20%23%20%C3%BC"
        val executor = ExecutorEnvironment.from(linux(uri))
        assertEquals(uri, executor.defaultWorkingUri)
        assertEquals("file:///home/z/project%20with%20%23%20%C3%BC/new%20%23%20%C3%BC.txt",
            ExecutorEnvironment.childUri(uri, "new # ü.txt"))
    }

    @Test fun remoteHealthUsesDocumentedPathAndPreservesPreviewSelector() {
        assertEquals("https://preview.example/v1/health?XTransformPort=8260&key=a%2Fb%20c",
            ExecutorEnvironment.healthUrl("wss://preview.example/v1/exec-server?XTransformPort=8260&key=a%2Fb%20c"))
        assertEquals("https://tunnel.example/v1/health", ExecutorEnvironment.healthUrl("wss://tunnel.example/v1/exec-server"))
    }

    @Test fun nativeAndArbitraryExecServerRetainTheirHealthLayout() {
        assertEquals("http://127.0.0.1:33995/readyz", ExecutorEnvironment.healthUrl("ws://127.0.0.1:33995"))
        assertEquals("https://example/base/readyz?selector=7", ExecutorEnvironment.healthUrl("wss://example/base?selector=7"))
    }

    @Test fun missingExecutorShellIsAnExplicitFailure() {
        try { ExecutorEnvironment.from(linux().copy(shell = null)); fail("Missing environment must not select an Android shell") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("shell")) }
    }

    @Test fun linuxIdentityNeverSelectsNativeConfigurationFromPathAlone() {
        val executor = ExecutorEnvironment.from(linux().copy(userHomeDir = "file:///data/data/com.termux/files/home"))
        assertFalse(executor.termux)
        assertEquals("file:///home/z/my-project", executor.defaultWorkingUri)
    }
}
