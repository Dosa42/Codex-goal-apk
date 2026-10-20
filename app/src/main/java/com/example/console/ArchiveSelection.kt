package com.example.console

/** A disk/network result can only update the archive view that requested it. */
class ArchiveSelection {
    data class Token(val endpoint: String, val archive: String, val generation: Long)
    private var generation = 0L
    private var selected: Token? = null
    @Synchronized fun begin(endpoint: String, archive: String): Token =
        Token(endpoint, archive, ++generation).also { selected = it }
    @Synchronized fun current(): Token? = selected
    @Synchronized fun matches(token: Token?): Boolean = token != null && selected == token
}
