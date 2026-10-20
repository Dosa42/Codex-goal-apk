package com.example.data.model

import java.net.URI
import java.security.MessageDigest

/** Paths describe the connected executor, never this APK's Android identity. */
data class ExecutorEnvironment(
    val shellPath: String,
    val shellName: String,
    val homeUri: String,
    val advertisedCwdUri: String,
    val defaultWorkingUri: String,
    val configDirectoryUri: String,
    val configUri: String,
    val platformOs: String,
    val termux: Boolean
) {
    fun commandArgv(command: String): List<String> = listOf(shellPath, "-c", command)
    fun interactiveArgv(): List<String> = listOf(shellPath, "-l")

    // The executor already owns HOME/PATH/PREFIX. Only terminal presentation is
    // requested here; copying Android paths into a Linux environment breaks it.
    fun environment(tty: Boolean): Map<String, String> = if (tty) mapOf("TERM" to "xterm-256color") else emptyMap()

    companion object {
        fun fileUri(input: String): String {
            val value = if (input.startsWith("/")) URI("file", "", input, null) else URI(input)
            require(value.scheme == "file" && (value.host.isNullOrEmpty() || value.host == "localhost") &&
                value.path?.startsWith('/') == true && value.query == null && value.fragment == null) {
                "Executor must report an absolute local path or file URI"
            }
            return URI("file", "", value.path, null).normalize().toASCIIString()
        }

        fun childUri(parent: String, name: String): String {
            require(name.isNotEmpty() && '/' !in name && '\u0000' !in name && name != "." && name != "..")
            val pathname = URI(fileUri(parent)).path.trimEnd('/') + "/" + name
            return URI("file", "", pathname, null).toASCIIString()
        }

        fun from(info: EnvironmentInfo?): ExecutorEnvironment {
            requireNotNull(info) { "Executor has not reported its environment" }
            val shell = info.shell?.path?.takeIf { it.startsWith('/') && '\u0000' !in it }
                ?: throw IllegalArgumentException("Executor did not report an absolute shell path")
            val home = fileUri(requireNotNull(info.userHomeDir) { "Executor did not report its home" })
            val cwd = fileUri(requireNotNull(info.cwd) { "Executor did not report its working directory" })
            val os = info.platformOs?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Executor did not report its operating system")
            val homePath = URI(home).path.trimEnd('/')
            val termux = os.equals("android", ignoreCase = true) &&
                (homePath == "/data/data/com.termux/files/home" || homePath == "/data/user/0/com.termux/files/home")
            val configDirectory: String
            val working: String
            if (termux) {
                // Preserve the user's established native Codex working folder.
                working = childUri(home, ".codex")
                configDirectory = childUri(working, "android-client")
            } else {
                working = cwd
                val scope = MessageDigest.getInstance("SHA-256").digest("$os\n$home\n$cwd".toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(24)
                var parent = home
                for (name in listOf(".local", "state", "codex-goal", "workspaces", scope)) parent = childUri(parent, name)
                configDirectory = parent
            }
            return ExecutorEnvironment(shell, info.shell?.name ?: shell.substringAfterLast('/'), home, cwd, working,
                configDirectory, childUri(configDirectory, "workspace.json"), os, termux)
        }

        /** Known compatibility endpoint uses its documented health route.
         * Arbitrary exec-server deployments retain their native /readyz layout.
         * The transport's selector is preserved byte for byte.
         */
        fun healthUrl(endpoint: String): String {
            val uri = URI(endpoint)
            val scheme = when (uri.scheme) { "ws" -> "http"; "wss" -> "https"; else -> throw IllegalArgumentException("Expected ws/wss endpoint") }
            require(!uri.rawAuthority.isNullOrBlank()) { "Endpoint authority missing" }
            val endpointPath = uri.rawPath.orEmpty().trimEnd('/')
            val healthPath = if (endpointPath == "/v1/exec-server") "/v1/health" else "$endpointPath/readyz"
            return "$scheme://${uri.rawAuthority}$healthPath" + (uri.rawQuery?.let { "?$it" } ?: "")
        }
    }
}
