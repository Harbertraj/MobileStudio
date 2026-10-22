package dev.mobilestudio.terminal

import java.io.File

data class GitCredential(val user: String, val token: String)

/** What the built-in git remembers between sessions: credentials per host, and the folder holding `.gitconfig`. */
interface GitSettings {
    /** Acts as the user's home for git: `~/.gitconfig` (user.name, user.email, ...) lives here. */
    val home: File

    fun credential(host: String): GitCredential?
    fun saveCredential(host: String, user: String, token: String)
    fun removeCredential(host: String): Boolean
    fun hosts(): List<String>
}

class MemoryGitSettings(override val home: File) : GitSettings {
    private val creds = linkedMapOf<String, GitCredential>()
    override fun credential(host: String) = creds[host.lowercase()]
    override fun saveCredential(host: String, user: String, token: String) { creds[host.lowercase()] = GitCredential(user, token) }
    override fun removeCredential(host: String) = creds.remove(host.lowercase()) != null
    override fun hosts() = creds.keys.toList()
}
