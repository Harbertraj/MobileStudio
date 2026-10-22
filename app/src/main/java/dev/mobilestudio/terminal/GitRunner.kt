package dev.mobilestudio.terminal

import org.eclipse.jgit.api.CheckoutCommand
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.InvalidConfigurationException
import org.eclipse.jgit.api.errors.JGitInternalException
import org.eclipse.jgit.api.errors.NoHeadException
import org.eclipse.jgit.api.errors.RefNotFoundException
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.StoredConfig
import org.eclipse.jgit.lib.TextProgressMonitor
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.FetchResult
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.filter.PathFilterGroup
import org.eclipse.jgit.util.FS
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * `git` for the terminal, built on JGit (a phone has no git binary). Output goes to [out] as plain text.
 * Supports HTTPS remotes; sign in with `git auth login`. SSH remotes, hooks, submodules, shallow clones and
 * interactive commands (rebase -i, add -p) are not supported.
 */
class GitRunner(private val settings: GitSettings, private val out: (String) -> Unit) {

    private class Fail(message: String) : Exception(message)

    private fun fail(message: String): Nothing = throw Fail(message)
    private fun p(line: String = "") = out(line + "\n")

    init {
        // JGit reads ~/.gitconfig from here; `git config --global` writes to the same file.
        FS.DETECTED.setUserHome(settings.home)
    }

    /** Runs `git <args>` as typed in [cwd]. Returns true on success (like exit code 0). */
    fun run(args: List<String>, cwd: File): Boolean {
        val command = args.firstOrNull()
        val rest = args.drop(1)
        return try {
            when (command) {
                null, "help", "--help", "-h" -> { help(); true }
                "--version", "version" -> { p("git (MobileStudio, built on JGit)"); true }
                "init" -> init(rest, cwd)
                "clone" -> clone(rest, cwd)
                "auth" -> auth(rest)
                "config" -> config(rest, cwd)
                else -> inRepo(cwd) { git, repo -> dispatch(command, rest, git, repo, cwd) }
            }
        } catch (e: Fail) {
            p("fatal: ${e.message}"); false
        } catch (e: Throwable) {
            p(describe(e)); false
        }
    }

    private fun dispatch(command: String, rest: List<String>, git: Git, repo: Repository, cwd: File): Boolean = when (command) {
        "status" -> status(git, repo, Args(rest), cwd)
        "add" -> add(git, repo, Args(rest), cwd)
        "rm" -> rm(git, repo, Args(rest), cwd)
        "mv" -> mv(git, repo, Args(rest), cwd)
        "commit" -> commit(git, repo, Args(rest, setOf("-m", "--message", "--author")))
        "log" -> log(git, repo, Args(rest, setOf("-n", "--max-count")), cwd)
        "diff" -> diff(git, repo, Args(rest), cwd)
        "show" -> show(git, repo, Args(rest))
        "branch" -> branch(git, repo, Args(rest, setOf("-u", "--set-upstream-to", "-m", "-M")))
        "checkout" -> checkout(git, repo, Args(rest, setOf("-b", "-B")), cwd)
        "switch" -> checkout(git, repo, Args(rest, setOf("-c", "-C")), cwd, switchCommand = true)
        "restore" -> restore(git, repo, Args(rest, setOf("--source", "-s")), cwd)
        "merge" -> merge(git, repo, Args(rest, setOf("-m", "--message")))
        "rebase" -> rebase(git, repo, Args(rest))
        "fetch" -> fetch(git, repo, Args(rest))
        "pull" -> pull(git, repo, Args(rest))
        "push" -> push(git, repo, Args(rest))
        "remote" -> remote(repo, Args(rest))
        "reset" -> reset(git, repo, Args(rest), cwd)
        "stash" -> stash(git, repo, Args(rest, setOf("-m", "--message")))
        "tag" -> tag(git, repo, Args(rest, setOf("-m", "--message")))
        "cherry-pick" -> cherryPick(git, repo, Args(rest))
        "revert" -> revert(git, repo, Args(rest))
        "clean" -> clean(git, repo, Args(rest), cwd)
        "ls-files" -> lsFiles(repo, cwd)
        else -> {
            p("git: '$command' is not available in MobileStudio's built-in git. Run 'git help' for the list.")
            false
        }
    }

    // ---- repository access -------------------------------------------------------------------

    private fun <T> inRepo(cwd: File, block: (Git, Repository) -> T): T {
        val builder = FileRepositoryBuilder().findGitDir(cwd)
        if (builder.gitDir == null) fail("not a git repository (or any of the parent directories): .git")
        return builder.build().use { repo ->
            relaxOnSharedStorage(repo)
            Git(repo).use { git -> block(git, repo) }
        }
    }

    /** Shared storage cannot hold the executable bit or symlinks; without this every checked-out script shows as modified. */
    private fun relaxOnSharedStorage(repo: Repository) {
        if (repo.isBare) return
        if (!isSharedStorage(repo.workTree.path)) return
        val cfg = repo.config
        if (cfg.getString("core", null, "filemode") == null) cfg.setBoolean("core", null, "filemode", false) // in memory only
        if (cfg.getString("core", null, "symlinks") == null) cfg.setBoolean("core", null, "symlinks", false)
    }

    private fun isSharedStorage(path: String) =
        listOf("/storage/", "/sdcard/", "/mnt/sdcard", "/mnt/media_rw/").any { path.startsWith(it) }

    private fun persistSharedStorageSettings(repo: Repository) {
        if (repo.isBare || !isSharedStorage(repo.workTree.path)) return
        val cfg = repo.config
        cfg.setBoolean("core", null, "filemode", false)
        cfg.setBoolean("core", null, "symlinks", false)
        cfg.save()
    }

    private fun workTree(repo: Repository): File =
        if (repo.isBare) fail("this operation must be run in a work tree") else repo.workTree

    private fun rel(repo: Repository, cwd: File, arg: String): String =
        GitPaths.repoRelative(workTree(repo), cwd, arg) ?: fail("'$arg' is outside repository at '${workTree(repo).path}'")

    // ---- init / clone / auth ----------------------------------------------------------------

    private fun init(rest: List<String>, cwd: File): Boolean {
        val a = Args(rest, setOf("-b", "--initial-branch"))
        val dir = a.positional.firstOrNull()?.let { if (File(it).isAbsolute) File(it) else File(cwd, it) } ?: cwd
        dir.mkdirs()
        val existed = File(dir, ".git").exists()
        Git.init().setDirectory(dir).setInitialBranch(a.value("-b", "--initial-branch") ?: "main").call().use { git ->
            persistSharedStorageSettings(git.repository)
        }
        p(if (existed) "Reinitialized existing Git repository in ${File(dir, ".git").path}/" else "Initialized empty Git repository in ${File(dir, ".git").path}/")
        return true
    }

    private fun clone(rest: List<String>, cwd: File): Boolean {
        val a = Args(rest, setOf("-b", "--branch", "--depth"))
        val url = a.positional.getOrNull(0) ?: fail("You must specify a repository to clone.")
        rejectSsh(url)
        if (a.value("--depth") != null) p("warning: --depth is not supported by the built-in git; cloning the full history")
        val name = a.positional.getOrNull(1) ?: repoNameFrom(url)
        val target = if (File(name).isAbsolute) File(name) else File(cwd, name)
        if (target.exists() && (!target.isDirectory || target.list()?.isNotEmpty() != false)) {
            fail("destination path '$name' already exists and is not an empty directory.")
        }
        val created = !target.exists()
        p("Cloning into '$name'...")
        try {
            val cmd = Git.cloneRepository().setURI(url).setDirectory(target)
                .setProgressMonitor(progress()).setCredentialsProvider(credentialsFor(url))
            a.value("-b", "--branch")?.let { cmd.setBranch(it) }
            cmd.call().use { git -> persistSharedStorageSettings(git.repository) }
        } catch (e: Throwable) {
            if (created) target.deleteRecursively()
            throw e
        }
        return true
    }

    private fun repoNameFrom(url: String): String =
        url.trimEnd('/').substringAfterLast('/').substringAfterLast(':').removeSuffix(".git").ifEmpty { fail("cannot work out a folder name for '$url'") }

    private fun auth(rest: List<String>): Boolean {
        when (rest.firstOrNull()) {
            "login" -> {
                val words = rest.drop(1)
                val (host, user, token) = when (words.size) {
                    2 -> Triple("github.com", words[0], words[1])
                    3 -> Triple(words[0], words[1], words[2])
                    else -> fail("usage: git auth login [host] <username> <token>   (host defaults to github.com)")
                }
                settings.saveCredential(host, user, token)
                p("Saved the token for $user on $host. It is used for git clone, fetch, pull and push over HTTPS.")
            }
            "logout" -> {
                val host = rest.getOrNull(1) ?: "github.com"
                p(if (settings.removeCredential(host)) "Removed the saved token for $host." else "No saved token for $host.")
            }
            "status", null -> {
                val hosts = settings.hosts()
                if (hosts.isEmpty()) p("No saved credentials. Use: git auth login [host] <username> <token>")
                else hosts.forEach { h -> p("$h  (user ${settings.credential(h)?.user})") }
            }
            else -> fail("usage: git auth login [host] <username> <token> | logout [host] | status")
        }
        return true
    }

    private fun rejectSsh(url: String) {
        if (url.startsWith("ssh://") || Regex("""^[\w.-]+@[\w.-]+:""").containsMatchIn(url)) {
            fail("SSH remotes are not supported here. Use the https:// address and 'git auth login'.")
        }
    }

    private fun credentialsFor(url: String?): CredentialsProvider? {
        val host = url?.let { runCatching { URIish(it).host }.getOrNull() } ?: return null
        val c = settings.credential(host) ?: return null
        return UsernamePasswordCredentialsProvider(c.user, c.token)
    }

    private fun remoteUrl(repo: Repository, remote: String): String? = repo.config.getString("remote", remote, "url")

    private fun progress(): ProgressMonitor = TextProgressMonitor(object : Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) { out(String(cbuf, off, len)) }
        override fun flush() {}
        override fun close() {}
    })

    // ---- config -----------------------------------------------------------------------------

    private fun globalConfig(): FileBasedConfig =
        FileBasedConfig(File(settings.home, ".gitconfig"), FS.DETECTED).also { it.load() }

    private fun config(rest: List<String>, cwd: File): Boolean {
        val a = Args(rest)
        val global = a.flag("--global")
        val unset = a.flag("--unset")
        val list = a.flag("-l", "--list")
        val keys = a.positional

        fun split(key: String): Triple<String, String?, String> {
            val parts = key.split('.')
            if (parts.size < 2 || parts.any { it.isEmpty() }) fail("key does not contain a section: $key")
            return Triple(parts.first(), parts.drop(1).dropLast(1).joinToString(".").ifEmpty { null }, parts.last())
        }

        val repoCfg: StoredConfig? = if (global) null else runCatching {
            FileRepositoryBuilder().findGitDir(cwd).takeIf { it.gitDir != null }?.build()?.config
        }.getOrNull()
        val target: StoredConfig = if (global) globalConfig() else repoCfg ?: if (list || keys.size == 1 && !unset) globalConfig()
        else fail("not in a git repository: use 'git config --global ...'")

        if (list) {
            for (section in target.sections) {
                for (name in target.getNames(section)) p("$section.$name=${target.getString(section, null, name)}")
                for (sub in target.getSubsections(section)) {
                    for (name in target.getNames(section, sub)) p("$section.$sub.$name=${target.getString(section, sub, name)}")
                }
            }
            return true
        }
        val key = keys.getOrNull(0) ?: fail("usage: git config [--global] [--unset] <key> [<value>]")
        val (section, sub, name) = split(key)
        return when {
            unset -> { target.unset(section, sub, name); target.save(); true }
            keys.size >= 2 -> { target.setString(section, sub, name, keys[1]); target.save(); true }
            else -> {
                val v = target.getString(section, sub, name)
                if (v == null) false else { p(v); true }
            }
        }
    }

    private fun requireIdentity(repo: Repository): PersonIdent {
        val name = repo.config.getString("user", null, "name")?.takeIf { it.isNotBlank() }
        val email = repo.config.getString("user", null, "email")?.takeIf { it.isNotBlank() }
        if (name == null || email == null) {
            fail("Author identity unknown\n\n  git config --global user.name \"Your Name\"\n  git config --global user.email \"you@example.com\"\n\nto set who you are, then try again.")
        }
        return PersonIdent(name, email)
    }

    // ---- status / add / rm / mv --------------------------------------------------------------

    private fun currentBranchLine(repo: Repository): String {
        val full = repo.fullBranch ?: return "HEAD"
        return if (full.startsWith(Constants.R_HEADS)) "On branch ${Repository.shortenRefName(full)}"
        else "HEAD detached at ${full.take(7)}"
    }

    private fun trackingLine(repo: Repository): String? {
        val branch = repo.branch ?: return null
        val t = runCatching { BranchTrackingStatus.of(repo, branch) }.getOrNull() ?: return null
        val upstream = Repository.shortenRefName(t.remoteTrackingBranch)
        fun commits(n: Int) = if (n == 1) "1 commit" else "$n commits"
        return when {
            t.aheadCount == 0 && t.behindCount == 0 -> "Your branch is up to date with '$upstream'."
            t.behindCount == 0 -> "Your branch is ahead of '$upstream' by ${commits(t.aheadCount)}."
            t.aheadCount == 0 -> "Your branch is behind '$upstream' by ${commits(t.behindCount)}, and can be fast-forwarded."
            else -> "Your branch and '$upstream' have diverged,\nand have ${t.aheadCount} and ${t.behindCount} different commits each, respectively."
        }
    }

    private fun status(git: Git, repo: Repository, a: Args, cwd: File): Boolean {
        val root = workTree(repo)
        val st = git.status().call()
        fun show(path: String) = GitPaths.display(root, cwd, path)

        if (a.flag("-s", "--short")) {
            val x = sortedMapOf<String, CharArray>()
            fun mark(path: String, i: Int, c: Char) { x.getOrPut(path) { charArrayOf(' ', ' ') }[i] = c }
            st.added.forEach { mark(it, 0, 'A') }
            st.changed.forEach { mark(it, 0, 'M') }
            st.removed.forEach { mark(it, 0, 'D') }
            st.modified.forEach { mark(it, 1, 'M') }
            st.missing.forEach { mark(it, 1, 'D') }
            st.conflicting.forEach { x[it] = charArrayOf('U', 'U') }
            x.forEach { (path, m) -> p("${m[0]}${m[1]} ${show(path)}") }
            val folders = st.untrackedFolders.sorted()
            folders.forEach { p("?? ${show(it)}/") }
            st.untracked.sorted().filter { f -> folders.none { f.startsWith("$it/") } }.forEach { p("?? ${show(it)}") }
            return true
        }

        p(currentBranchLine(repo))
        trackingLine(repo)?.let { p(it) }
        if (repo.resolve(Constants.HEAD) == null) { p(); p("No commits yet") }
        p()
        var shown = false
        fun section(title: String, hint: String?, rows: List<Pair<String, String>>) {
            if (rows.isEmpty()) return
            shown = true
            p(title)
            hint?.let { p("  ($it)") }
            rows.forEach { (label, path) -> p("\t${label.padEnd(12)}${show(path)}") }
            p()
        }
        section("Unmerged paths:", "fix conflicts and run \"git add <file>...\"", st.conflicting.sorted().map { "both modified:" to it })
        section(
            "Changes to be committed:", "use \"git restore --staged <file>...\" to unstage",
            st.added.sorted().map { "new file:" to it } + st.changed.sorted().map { "modified:" to it } + st.removed.sorted().map { "deleted:" to it },
        )
        section(
            "Changes not staged for commit:", "use \"git add <file>...\" to update what will be committed",
            (st.modified.map { "modified:" to it } + st.missing.map { "deleted:" to it }).sortedBy { it.second },
        )
        val folders = st.untrackedFolders.sorted()
        val loose = st.untracked.sorted().filter { f -> folders.none { f.startsWith("$it/") } }
        if (folders.isNotEmpty() || loose.isNotEmpty()) {
            shown = true
            p("Untracked files:"); p("  (use \"git add <file>...\" to include in what will be committed)")
            (folders.map { show(it) + "/" } + loose.map { show(it) }).sorted().forEach { p("\t$it") }
            p()
        }
        if (!shown) p("nothing to commit, working tree clean")
        else if (st.added.isEmpty() && st.changed.isEmpty() && st.removed.isEmpty()) {
            p(if (st.untracked.isNotEmpty() && st.modified.isEmpty() && st.missing.isEmpty())
                "nothing added to commit but untracked files present (use \"git add\" to track)"
            else "no changes added to commit (use \"git add\" and/or \"git commit -a\")")
        }
        return true
    }

    private fun add(git: Git, repo: Repository, a: Args, cwd: File): Boolean {
        val update = a.flag("-u", "--update")
        val all = a.flag("-A", "--all")
        val given = a.positional + a.afterDashes
        val specs = if ((all || update) && given.isEmpty()) listOf(".") else given.map { rel(repo, cwd, it) }
        if (specs.isEmpty()) {
            p("Nothing specified, nothing added."); p("hint: Maybe you wanted to say 'git add .'?")
            return true
        }
        val root = workTree(repo)
        for ((i, spec) in specs.withIndex()) {
            git.add().addFilepattern(spec).setUpdate(update).call()
            // JGit's add stages new and changed files; deleted files have to be staged separately.
            val missing = git.status().apply { if (spec != ".") addPath(spec) }.call().missing
            if (missing.isNotEmpty()) git.rm().setCached(true).apply { missing.forEach { addFilepattern(it) } }.call()
            if (spec != "." && missing.isEmpty() && !File(root, spec).exists() && !isTracked(repo, spec)) {
                fail("pathspec '${given.getOrElse(i) { spec }}' did not match any files")
            }
        }
        return true
    }

    private fun isTracked(repo: Repository, path: String): Boolean =
        repo.readDirCache().let { dc -> dc.findEntry(path) >= 0 || dc.getEntriesWithin(path).isNotEmpty() }

    private fun rm(git: Git, repo: Repository, a: Args, cwd: File): Boolean {
        val paths = (a.positional + a.afterDashes).map { rel(repo, cwd, it) }
        if (paths.isEmpty()) fail("No pathspec was given. Which files should I remove?")
        paths.forEach { if (!isTracked(repo, it)) fail("pathspec '$it' did not match any files") }
        val cmd = git.rm().setCached(a.flag("--cached"))
        paths.forEach { cmd.addFilepattern(it) }
        cmd.call()
        (a.positional + a.afterDashes).forEach { p("rm '$it'") }
        return true
    }

    private fun mv(git: Git, repo: Repository, a: Args, cwd: File): Boolean {
        val args = a.positional + a.afterDashes
        if (args.size != 2) fail("usage: git mv <source> <destination>")
        val root = workTree(repo)
        val src = File(root, rel(repo, cwd, args[0]))
        var dest = File(root, rel(repo, cwd, args[1]))
        if (!src.exists()) fail("bad source, source=${args[0]}, destination=${args[1]}")
        if (dest.isDirectory) dest = File(dest, src.name)
        if (dest.exists()) fail("destination exists, source=${args[0]}, destination=${args[1]}")
        dest.parentFile?.mkdirs()
        if (!src.renameTo(dest)) fail("could not move ${args[0]}")
        git.rm().setCached(true).addFilepattern(src.relativeTo(root).path.replace(File.separatorChar, '/')).call()
        git.add().addFilepattern(dest.relativeTo(root).path.replace(File.separatorChar, '/')).call()
        return true
    }

    // ---- commit / log / diff / show ------------------------------------------------------------

    private fun commit(git: Git, repo: Repository, a: Args): Boolean {
        val all = a.flag("-a", "--all")
        val amend = a.flag("--amend")
        val allowEmpty = a.flag("--allow-empty")
        val message = a.values("-m", "--message").joinToString("\n\n")
        val ident = requireIdentity(repo)
        if (message.isBlank() && !amend) fail("no commit message given. Use: git commit -m \"message\"")
        if (!amend && !allowEmpty) {
            val st = git.status().call()
            val staged = st.added.isNotEmpty() || st.changed.isNotEmpty() || st.removed.isNotEmpty()
            val tracked = st.modified.isNotEmpty() || st.missing.isNotEmpty()
            if (!staged && !(all && tracked) && repo.repositoryState.name != "MERGING") {
                p(currentBranchLine(repo))
                p(if (tracked) "no changes added to commit (use \"git add\" and/or \"git commit -a\")"
                else if (st.untracked.isNotEmpty()) "nothing added to commit but untracked files present (use \"git add\" to track)"
                else "nothing to commit, working tree clean")
                return false
            }
        }
        val cmd = git.commit().setAll(all).setAmend(amend).setAllowEmpty(true)
            .setAuthor(a.value("--author")?.let(::parseAuthor) ?: ident).setCommitter(ident)
            .setSign(false).setNoVerify(true)
        if (message.isNotBlank()) cmd.setMessage(message)
        val c = cmd.call()
        val root = if (c.parentCount == 0) " (root-commit)" else ""
        p("[${Repository.shortenRefName(repo.fullBranch ?: "HEAD")}$root ${c.name.take(7)}] ${c.shortMessage}")
        return true
    }

    private fun parseAuthor(text: String): PersonIdent {
        val m = Regex("""^(.*?)\s*<(.+)>$""").find(text.trim()) ?: fail("--author must look like 'Name <email>'")
        return PersonIdent(m.groupValues[1], m.groupValues[2])
    }

    private fun formatDate(ident: PersonIdent): String {
        val f = SimpleDateFormat("EEE MMM d HH:mm:ss yyyy Z", Locale.US)
        f.timeZone = ident.timeZone ?: TimeZone.getDefault()
        return f.format(Date(ident.getWhen().time))
    }

    private fun printCommit(c: RevCommit) {
        p("commit ${c.name}")
        if (c.parentCount > 1) p("Merge: " + c.parents.joinToString(" ") { it.name.take(7) })
        p("Author: ${c.authorIdent.name} <${c.authorIdent.emailAddress}>")
        p("Date:   ${formatDate(c.authorIdent)}")
        p()
        c.fullMessage.trimEnd().lines().forEach { p("    $it") }
        p()
    }

    private fun log(git: Git, repo: Repository, a: Args, cwd: File): Boolean {
        val oneline = a.flag("--oneline")
        val limit = a.value("-n", "--max-count")?.toIntOrNull() ?: DEFAULT_LOG_LIMIT
        val cmd = git.log().setMaxCount(limit)
        if (a.flag("--all")) cmd.all()
        a.positional.forEach { arg ->
            val id = repo.resolve(arg) ?: fail("ambiguous argument '$arg': unknown revision or path not in the working tree.")
            cmd.add(id)
        }
        a.afterDashes.forEach { cmd.addPath(rel(repo, cwd, it)) }
        var n = 0
        try {
            for (c in cmd.call()) {
                n++
                if (oneline) p("${c.name.take(7)} ${c.shortMessage}") else printCommit(c)
            }
        } catch (e: NoHeadException) {
            fail("your current branch '${repo.branch}' does not have any commits yet")
        }
        if (n >= limit && a.value("-n", "--max-count") == null) p("(showing the latest $limit commits; use -n <count> for more)")
        return true
    }

    private fun treeOf(repo: Repository, rev: String): AbstractTreeIterator {
        val id = repo.resolve("$rev^{tree}") ?: fail("bad revision '$rev'")
        return CanonicalTreeParser().also { parser -> repo.newObjectReader().use { parser.reset(it, id) } }
    }

    private fun diff(git: Git, repo: Repository, a: Args, cwd: File): Boolean {
        val revs = mutableListOf<String>()
        val paths = a.afterDashes.map { rel(repo, cwd, it) }.toMutableList()
        for (arg in a.positional) {
            if (revs.size < 2 && repo.resolve(arg) != null) revs += arg else paths += rel(repo, cwd, arg)
        }
        val os = ByteArrayOutputStream()
        val cmd = git.diff().setOutputStream(os).setCached(a.flag("--cached", "--staged"))
        if (a.flag("--name-status", "--name-only")) cmd.setShowNameAndStatusOnly(true)
        if (revs.isNotEmpty()) cmd.setOldTree(treeOf(repo, revs[0]))
        if (revs.size > 1) cmd.setNewTree(treeOf(repo, revs[1]))
        if (paths.isNotEmpty() && "." !in paths) cmd.setPathFilter(PathFilterGroup.createFromStrings(paths))
        try {
            cmd.call()
        } catch (e: NoHeadException) {
            fail("your current branch '${repo.branch}' does not have any commits yet")
        }
        val text = os.toString(Charsets.UTF_8.name())
        if (text.isNotEmpty()) out(if (text.endsWith("\n")) text else text + "\n")
        return true
    }

    private fun show(git: Git, repo: Repository, a: Args): Boolean {
        val rev = a.positional.firstOrNull() ?: Constants.HEAD
        val id = repo.resolve(rev) ?: fail("ambiguous argument '$rev': unknown revision")
        RevWalk(repo).use { walk ->
            val c = walk.parseCommit(id)
            printCommit(c)
            val os = ByteArrayOutputStream()
            DiffFormatter(os).use { df ->
                df.setRepository(repo)
                df.setDetectRenames(true)
                val old: AbstractTreeIterator = if (c.parentCount > 0) {
                    val parent = walk.parseCommit(c.getParent(0).id)
                    CanonicalTreeParser().also { t -> repo.newObjectReader().use { t.reset(it, parent.tree.id) } }
                } else EmptyTreeIterator()
                val new = CanonicalTreeParser().also { t -> repo.newObjectReader().use { t.reset(it, c.tree.id) } }
                df.format(df.scan(old, new))
            }
            val text = os.toString(Charsets.UTF_8.name())
            if (text.isNotEmpty()) out(if (text.endsWith("\n")) text else text + "\n")
        }
        return true
    }

    // ---- branches / checkout / restore -------------------------------------------------------------

    private fun branch(git: Git, repo: Repository, a: Args): Boolean {
        val names = a.positional
        val delete = a.flag("-d", "-D", "--delete")
        val rename = a.value("-m", "-M")
        val upstream = a.value("-u", "--set-upstream-to")
        when {
            a.flag("--show-current") -> { p(repo.branch); return true }
            delete -> {
                if (names.isEmpty()) fail("branch name required")
                val deleted = git.branchDelete().setBranchNames(*names.toTypedArray()).setForce(a.flag("-D") || a.flag("--force", "-f")).call()
                if (deleted.size < names.size) {
                    val missing = names.filter { n -> deleted.none { Repository.shortenRefName(it) == n } }
                    fail("could not delete ${missing.joinToString(", ")} (not found, or not fully merged; use -D to force)")
                }
                names.forEach { p("Deleted branch $it") }
                return true
            }
            upstream != null -> {
                val branch = names.firstOrNull() ?: repo.branch
                val slash = upstream.indexOf('/')
                if (slash < 0) fail("'$upstream' is not a remote-tracking branch (expected <remote>/<branch>)")
                setUpstream(repo, branch, upstream.substring(0, slash), upstream.substring(slash + 1))
                p("branch '$branch' set up to track '$upstream'.")
                return true
            }
            a.flag("--unset-upstream") -> {
                val branch = names.firstOrNull() ?: repo.branch
                repo.config.unset("branch", branch, "remote"); repo.config.unset("branch", branch, "merge"); repo.config.save()
                return true
            }
            rename != null || a.flag("-m", "-M") -> {
                val from = if (names.isEmpty()) repo.branch else (rename?.takeIf { names.isNotEmpty() } ?: names[0])
                val to = if (names.isEmpty()) rename ?: fail("branch name required") else names.last()
                git.branchRename().setOldName(from).setNewName(to).call()
                return true
            }
            names.isEmpty() -> {
                val mode = when {
                    a.flag("-a", "--all") -> ListBranchCommand.ListMode.ALL
                    a.flag("-r", "--remotes") -> ListBranchCommand.ListMode.REMOTE
                    else -> null
                }
                val refs = git.branchList().apply { if (mode != null) setListMode(mode) }.call().sortedBy { it.name }
                val current = repo.fullBranch
                if (refs.isEmpty()) { p("(no branches yet: make a commit first)"); return true }
                RevWalk(repo).use { walk ->
                    for (ref in refs) {
                        val name = Repository.shortenRefName(ref.name).let { if (ref.name.startsWith(Constants.R_REMOTES) && mode == ListBranchCommand.ListMode.ALL) "remotes/$it" else it }
                        val star = if (ref.name == current) "* " else "  "
                        if (a.flag("-v", "-vv", "--verbose") && ref.objectId != null) {
                            p("$star$name ${ref.objectId.name.take(7)} ${walk.parseCommit(ref.objectId).shortMessage}")
                        } else p("$star$name")
                    }
                }
                return true
            }
            else -> {
                val cmd = git.branchCreate().setName(names[0])
                names.getOrNull(1)?.let { cmd.setStartPoint(it) }
                if (repo.resolve(Constants.HEAD) == null) fail("not a valid object name: '${repo.branch}' (make a commit first)")
                cmd.call()
                return true
            }
        }
    }

    private fun setUpstream(repo: Repository, branch: String, remote: String, remoteBranch: String) {
        repo.config.setString("branch", branch, "remote", remote)
        repo.config.setString("branch", branch, "merge", Constants.R_HEADS + remoteBranch)
        repo.config.save()
    }

    private fun checkout(git: Git, repo: Repository, a: Args, cwd: File, switchCommand: Boolean = false): Boolean {
        val force = a.flag("-f", "--force")
        val create = a.value("-b", "-c")
        val recreate = a.value("-B", "-C")
        val newName = create ?: recreate
        val positional = a.positional

        // Restoring files: `checkout -- path`, `checkout <rev> -- path`, or `checkout path` when no such branch exists.
        val looksLikePath = !switchCommand && newName == null && positional.size == 1 && a.afterDashes.isEmpty() &&
            repo.findRef(positional[0]) == null && repo.resolve(positional[0]) == null &&
            File(workTree(repo), rel(repo, cwd, positional[0])).exists()
        if (!switchCommand && (a.afterDashes.isNotEmpty() || looksLikePath)) {
            val source = if (a.afterDashes.isNotEmpty()) positional.firstOrNull() else null
            val paths = (if (looksLikePath) positional else a.afterDashes).map { rel(repo, cwd, it) }
            restorePaths(git, paths, source)
            p("Updated ${paths.size} path(s) from ${source ?: "the index"}")
            return true
        }

        val cmd = git.checkout()
        if (newName != null) {
            cmd.setCreateBranch(true).setName(newName)
            positional.firstOrNull()?.let { cmd.setStartPoint(it) }
            if (recreate != null) cmd.setForceRefUpdate(true)
            if (positional.isEmpty() && repo.resolve(Constants.HEAD) == null) {
                // an unborn branch can only be renamed, not "created from"
                repo.updateRef(Constants.HEAD).link(Constants.R_HEADS + newName)
                p("Switched to a new branch '$newName'")
                return true
            }
        } else {
            val target = positional.firstOrNull() ?: fail("missing branch or commit argument")
            val local = repo.findRef(Constants.R_HEADS + target)
            if (local == null) {
                val remoteRef = remoteBranchesNamed(repo, target).firstOrNull()
                if (remoteRef != null) {
                    cmd.setCreateBranch(true).setName(target).setStartPoint(remoteRef.name)
                        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                } else {
                    if (repo.resolve(target) == null) fail("pathspec '$target' did not match any file(s) known to git")
                    cmd.setName(target)
                }
            } else cmd.setName(target)
        }
        if (force) cmd.setForced(true)
        val before = repo.fullBranch
        try {
            cmd.call()
        } catch (e: CheckoutConflictException) {
            p("error: Your local changes to the following files would be overwritten by checkout:")
            e.conflictingPaths.forEach { p("\t$it") }
            p("Please commit your changes or stash them before you switch branches.")
            return false
        } catch (e: RefNotFoundException) {
            fail("invalid reference: ${positional.firstOrNull() ?: newName}")
        }
        val now = repo.fullBranch ?: ""
        when {
            now.startsWith(Constants.R_HEADS) && now == before -> p("Already on '${Repository.shortenRefName(now)}'")
            now.startsWith(Constants.R_HEADS) && newName != null -> p("Switched to a new branch '${Repository.shortenRefName(now)}'")
            now.startsWith(Constants.R_HEADS) -> p("Switched to branch '${Repository.shortenRefName(now)}'")
            else -> p("HEAD is now at ${now.take(7)} (detached HEAD)")
        }
        return true
    }

    private fun remoteBranchesNamed(repo: Repository, name: String): List<Ref> =
        repo.refDatabase.getRefsByPrefix(Constants.R_REMOTES)
            .filter { it.name.substringAfter(Constants.R_REMOTES).substringAfter('/') == name }
            .sortedBy { if (it.name.startsWith(Constants.R_REMOTES + "origin/")) 0 else 1 }

    private fun restorePaths(git: Git, paths: List<String>, source: String?) {
        val cmd: CheckoutCommand = git.checkout()
        if (source != null) cmd.setStartPoint(source)
        if ("." in paths) cmd.setAllPaths(true) else paths.forEach { cmd.addPath(it) }
        cmd.call()
    }

    private fun restore(git: Git, repo: Repository, a: Args, cwd: File): Boolean {
        val paths = (a.positional + a.afterDashes).map { rel(repo, cwd, it) }
        if (paths.isEmpty()) fail("you must specify path(s) to restore")
        val staged = a.flag("--staged", "-S")
        val worktree = a.flag("--worktree", "-W") || !staged
        val source = a.value("--source", "-s")
        if (staged) {
            val cmd = git.reset().setRef(source ?: Constants.HEAD)
            if ("." !in paths) paths.forEach { cmd.addPath(it) }
            cmd.call()
        }
        if (worktree) restorePaths(git, paths, source)
        return true
    }

    // ---- merge / rebase / cherry-pick / revert -------------------------------------------------

    private fun printMerge(r: MergeResult): Boolean {
        when (r.mergeStatus) {
            MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> p("Already up to date.")
            MergeResult.MergeStatus.FAST_FORWARD, MergeResult.MergeStatus.FAST_FORWARD_SQUASHED -> p("Fast-forward")
            MergeResult.MergeStatus.MERGED, MergeResult.MergeStatus.MERGED_SQUASHED,
            MergeResult.MergeStatus.MERGED_NOT_COMMITTED, MergeResult.MergeStatus.MERGED_SQUASHED_NOT_COMMITTED -> p("Merge made.")
            MergeResult.MergeStatus.CONFLICTING -> {
                r.conflicts?.keys?.sorted()?.forEach { p("CONFLICT (content): Merge conflict in $it") }
                p("Automatic merge failed; fix conflicts and then commit the result.")
                return false
            }
            MergeResult.MergeStatus.FAILED -> {
                p("error: the merge could not be done:")
                r.failingPaths?.forEach { (path, reason) -> p("\t$path ($reason)") }
                return false
            }
            MergeResult.MergeStatus.ABORTED, MergeResult.MergeStatus.NOT_SUPPORTED, MergeResult.MergeStatus.CHECKOUT_CONFLICT -> {
                p("Merge did not complete (${r.mergeStatus}).")
                r.checkoutConflicts?.forEach { p("\t$it") }
                return false
            }
            else -> p("Merge result: ${r.mergeStatus}")
        }
        return true
    }

    private fun merge(git: Git, repo: Repository, a: Args): Boolean {
        if (a.flag("--abort")) {
            git.reset().setMode(ResetCommand.ResetType.HARD).call()
            p("Merge aborted.")
            return true
        }
        val rev = a.positional.firstOrNull() ?: fail("specify a branch or commit to merge")
        val id = repo.resolve(rev) ?: fail("$rev - not something we can merge")
        requireIdentity(repo)
        val cmd = git.merge().include(rev, id)
        cmd.setFastForward(
            when {
                a.flag("--no-ff") -> MergeCommand.FastForwardMode.NO_FF
                a.flag("--ff-only") -> MergeCommand.FastForwardMode.FF_ONLY
                else -> MergeCommand.FastForwardMode.FF
            }
        )
        if (a.flag("--squash")) cmd.setSquash(true)
        a.value("-m", "--message")?.let { cmd.setMessage(it) }
        return printMerge(cmd.call())
    }

    private fun printRebase(r: RebaseResult): Boolean {
        when (r.status) {
            RebaseResult.Status.OK -> p("Successfully rebased.")
            RebaseResult.Status.UP_TO_DATE -> p("Current branch is up to date.")
            RebaseResult.Status.FAST_FORWARD -> p("Fast-forwarded.")
            RebaseResult.Status.ABORTED -> p("Rebase aborted.")
            RebaseResult.Status.NOTHING_TO_COMMIT -> p("Nothing to commit; skipped.")
            RebaseResult.Status.CONFLICTS -> {
                r.conflicts?.forEach { p("CONFLICT: $it") }
                p("Resolve the conflicts, 'git add' the files, then run 'git rebase --continue' (or '--abort').")
                return false
            }
            RebaseResult.Status.UNCOMMITTED_CHANGES -> { p("error: cannot rebase: you have unstaged or uncommitted changes."); return false }
            else -> { p("Rebase stopped: ${r.status}"); return r.status.isSuccessful }
        }
        return true
    }

    private fun rebase(git: Git, repo: Repository, a: Args): Boolean {
        val op = when {
            a.flag("--abort") -> RebaseCommand.Operation.ABORT
            a.flag("--continue") -> RebaseCommand.Operation.CONTINUE
            a.flag("--skip") -> RebaseCommand.Operation.SKIP
            else -> RebaseCommand.Operation.BEGIN
        }
        val cmd = git.rebase().setOperation(op)
        if (op == RebaseCommand.Operation.BEGIN) {
            val upstream = a.positional.firstOrNull() ?: fail("specify the branch to rebase onto")
            if (repo.resolve(upstream) == null) fail("invalid upstream '$upstream'")
            requireIdentity(repo)
            cmd.setUpstream(upstream)
        }
        return printRebase(cmd.call())
    }

    private fun cherryPick(git: Git, repo: Repository, a: Args): Boolean {
        val rev = a.positional.firstOrNull() ?: fail("specify a commit to cherry-pick")
        val id = repo.resolve(rev) ?: fail("bad revision '$rev'")
        requireIdentity(repo)
        val r = git.cherryPick().include(id).call()
        return when (r.status) {
            org.eclipse.jgit.api.CherryPickResult.CherryPickStatus.OK -> { p("Cherry-picked ${id.name.take(7)}."); true }
            org.eclipse.jgit.api.CherryPickResult.CherryPickStatus.CONFLICTING -> { p("CONFLICT: fix the conflicts, then commit."); false }
            else -> { p("Cherry-pick failed (${r.status})."); false }
        }
    }

    private fun revert(git: Git, repo: Repository, a: Args): Boolean {
        val rev = a.positional.firstOrNull() ?: fail("specify a commit to revert")
        val id = repo.resolve(rev) ?: fail("bad revision '$rev'")
        requireIdentity(repo)
        val c = RevWalk(repo).use { it.parseCommit(id) }
        val reverted = git.revert().include(c).call()
        if (reverted == null) { p("Revert stopped because of conflicts; fix them and commit."); return false }
        p("[${reverted.name.take(7)}] ${reverted.shortMessage}")
        return true
    }

    // ---- remotes: fetch / pull / push ----------------------------------------------------------

    private fun remote(repo: Repository, a: Args): Boolean {
        val cfg = repo.config
        val names = cfg.getSubsections("remote").sorted()
        val args = a.positional
        when (args.firstOrNull()) {
            null -> names.forEach { p(it) }
            "-v" -> names.forEach { n -> remoteUrl(repo, n)?.let { p("$n\t$it (fetch)"); p("$n\t$it (push)") } }
            "add" -> {
                val name = args.getOrNull(1); val url = args.getOrNull(2)
                if (name == null || url == null) fail("usage: git remote add <name> <url>")
                if (name in names) fail("remote $name already exists.")
                rejectSsh(url)
                cfg.setString("remote", name, "url", url)
                cfg.setString("remote", name, "fetch", "+refs/heads/*:refs/remotes/$name/*")
                cfg.save()
            }
            "remove", "rm" -> {
                val name = args.getOrNull(1) ?: fail("usage: git remote remove <name>")
                if (name !in names) fail("No such remote: '$name'")
                cfg.unsetSection("remote", name)
                cfg.save()
            }
            "rename" -> {
                val old = args.getOrNull(1); val new = args.getOrNull(2)
                if (old == null || new == null) fail("usage: git remote rename <old> <new>")
                val url = remoteUrl(repo, old) ?: fail("No such remote: '$old'")
                cfg.unsetSection("remote", old)
                cfg.setString("remote", new, "url", url)
                cfg.setString("remote", new, "fetch", "+refs/heads/*:refs/remotes/$new/*")
                cfg.save()
            }
            "set-url" -> {
                val name = args.getOrNull(1); val url = args.getOrNull(2)
                if (name == null || url == null) fail("usage: git remote set-url <name> <url>")
                if (name !in names) fail("No such remote '$name'")
                rejectSsh(url)
                cfg.setString("remote", name, "url", url); cfg.save()
            }
            "get-url" -> p(remoteUrl(repo, args.getOrNull(1) ?: "origin") ?: fail("No such remote '${args.getOrNull(1) ?: "origin"}'"))
            else -> fail("unknown remote command '${args[0]}'")
        }
        return true
    }

    private fun printFetch(r: FetchResult?) {
        r?.trackingRefUpdates?.forEach { u ->
            val local = Repository.shortenRefName(u.localName)
            val remote = Repository.shortenRefName(u.remoteName)
            when (u.result) {
                RefUpdate.Result.NEW -> p(" * [new ${if (u.localName.startsWith(Constants.R_TAGS)) "tag" else "branch"}]  $remote -> $local")
                RefUpdate.Result.FAST_FORWARD -> p("   ${u.oldObjectId.name.take(7)}..${u.newObjectId.name.take(7)}  $remote -> $local")
                RefUpdate.Result.FORCED -> p(" + ${u.oldObjectId.name.take(7)}...${u.newObjectId.name.take(7)}  $remote -> $local (forced update)")
                RefUpdate.Result.REJECTED -> p(" ! [rejected]  $remote -> $local (non-fast-forward)")
                RefUpdate.Result.NO_CHANGE -> Unit
                else -> p("   ${u.result}  $remote -> $local")
            }
        }
        r?.messages?.takeIf { it.isNotBlank() }?.lines()?.filter { it.isNotBlank() }?.forEach { p("remote: $it") }
    }

    private fun fetch(git: Git, repo: Repository, a: Args): Boolean {
        val remotes = if (a.flag("--all")) repo.config.getSubsections("remote").sorted() else listOf(a.positional.firstOrNull() ?: "origin")
        if (remotes.isEmpty()) fail("no remotes configured. Use: git remote add origin <url>")
        for (remote in remotes) {
            val url = remoteUrl(repo, remote) ?: fail("'$remote' does not appear to be a git repository (git remote add $remote <url>)")
            rejectSsh(url)
            val cmd = git.fetch().setRemote(remote).setProgressMonitor(progress()).setCredentialsProvider(credentialsFor(url))
                .setRemoveDeletedRefs(a.flag("--prune", "-p"))
            if (a.flag("--tags")) cmd.setTagOpt(org.eclipse.jgit.transport.TagOpt.FETCH_TAGS)
            printFetch(cmd.call())
        }
        return true
    }

    private fun pull(git: Git, repo: Repository, a: Args): Boolean {
        val branch = repo.branch
        if (repo.fullBranch?.startsWith(Constants.R_HEADS) != true) fail("You are not currently on a branch.")
        val remote = a.positional.getOrNull(0)
        val remoteBranch = a.positional.getOrNull(1)
        val configured = repo.config.getString("branch", branch, "merge")
        if (remote == null && configured == null) {
            p("There is no tracking information for the current branch.")
            p("Specify which branch to merge with: git pull <remote> <branch>")
            p("or set it once with:  git branch --set-upstream-to=<remote>/<branch> $branch")
            return false
        }
        requireIdentity(repo)
        val url = remoteUrl(repo, remote ?: repo.config.getString("branch", branch, "remote") ?: "origin")
        url?.let(::rejectSsh)
        val cmd = git.pull().setProgressMonitor(progress()).setCredentialsProvider(credentialsFor(url))
            .setRebase(a.flag("--rebase", "-r"))
        remote?.let { cmd.setRemote(it) }
        remoteBranch?.let { cmd.setRemoteBranchName(it) }
        if (a.flag("--ff-only")) cmd.setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
        if (a.flag("--no-ff")) cmd.setFastForward(MergeCommand.FastForwardMode.NO_FF)
        val r = cmd.call()
        printFetch(r.fetchResult)
        r.rebaseResult?.let { return printRebase(it) }
        return r.mergeResult?.let { printMerge(it) } ?: true
    }

    private fun push(git: Git, repo: Repository, a: Args): Boolean {
        val args = a.positional
        val remotes = repo.config.getSubsections("remote")
        val force = a.flag("-f", "--force")
        val setUp = a.flag("-u", "--set-upstream")
        val delete = a.flag("-d", "--delete")
        if (remotes.isEmpty()) fail("No configured push destination.\nAdd one with: git remote add origin <url>")

        val branch = repo.branch
        val onBranch = repo.fullBranch?.startsWith(Constants.R_HEADS) == true
        val upstreamRemote = if (onBranch) repo.config.getString("branch", branch, "remote") else null
        val upstreamMerge = if (onBranch) repo.config.getString("branch", branch, "merge") else null
        val remote = args.getOrNull(0) ?: upstreamRemote ?: if ("origin" in remotes) "origin" else remotes.first()
        val url = remoteUrl(repo, remote) ?: fail("'$remote' does not appear to be a git repository")
        rejectSsh(url)

        val specs: List<RefSpec> = when {
            delete -> args.drop(1).map { RefSpec(":" + (if (it.startsWith("refs/")) it else Constants.R_HEADS + it)) }
            args.size >= 2 -> args.drop(1).map { s ->
                val spec = when {
                    ':' in s || s.startsWith("refs/") && false -> s
                    s == "HEAD" -> "HEAD:${Constants.R_HEADS}$branch"
                    else -> "${Constants.R_HEADS}${s.removePrefix("+")}:${Constants.R_HEADS}${s.removePrefix("+")}".let { if (s.startsWith("+")) "+$it" else it }
                }
                RefSpec(spec).setForceUpdate(force || spec.startsWith("+"))
            }
            a.flag("--tags") -> emptyList()
            !onBranch -> fail("You are not currently on a branch; name what to push: git push $remote <branch>")
            args.isEmpty() && upstreamRemote == null && !setUp -> {
                fail("The current branch $branch has no upstream branch.\nTo push it and set the remote as upstream, use\n\n    git push --set-upstream $remote $branch")
            }
            else -> {
                val dst = if (remote == upstreamRemote && upstreamMerge != null) upstreamMerge else Constants.R_HEADS + branch
                listOf(RefSpec("${Constants.R_HEADS}$branch:$dst").setForceUpdate(force))
            }
        }
        val cmd = git.push().setRemote(remote).setProgressMonitor(progress()).setCredentialsProvider(credentialsFor(url)).setForce(force)
        if (specs.isNotEmpty()) cmd.setRefSpecs(specs)
        if (a.flag("--tags")) cmd.setPushTags()
        p("To $url")
        var ok = true
        var upToDate = 0
        for (result in cmd.call()) {
            result.messages?.takeIf { it.isNotBlank() }?.lines()?.filter { it.isNotBlank() }?.forEach { p("remote: $it") }
            for (u in result.remoteUpdates) {
                val src = u.srcRef?.let { Repository.shortenRefName(it) } ?: "(delete)"
                val dst = Repository.shortenRefName(u.remoteName)
                when (u.status) {
                    RemoteRefUpdate.Status.OK -> p(if (u.isDelete) " - [deleted]  $dst" else "   ${u.newObjectId.name.take(7)}  $src -> $dst" + if (force) " (forced update)" else "")
                    RemoteRefUpdate.Status.UP_TO_DATE -> upToDate++
                    RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD -> {
                        ok = false
                        p(" ! [rejected]  $src -> $dst (non-fast-forward)")
                        p("hint: the remote has commits you do not have. Run 'git pull' first, then push again.")
                    }
                    RemoteRefUpdate.Status.REJECTED_OTHER_REASON, RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED, RemoteRefUpdate.Status.REJECTED_NODELETE -> {
                        ok = false
                        p(" ! [remote rejected]  $src -> $dst (${u.message ?: u.status})")
                    }
                    else -> { ok = false; p(" ! ${u.status}  $src -> $dst ${u.message.orEmpty()}") }
                }
            }
        }
        if (upToDate > 0 && ok) p("Everything up-to-date")
        if (ok && setUp && onBranch) {
            val spec = specs.firstOrNull()
            val remoteBranch = spec?.destination?.removePrefix(Constants.R_HEADS) ?: branch
            setUpstream(repo, branch, remote, remoteBranch)
            p("branch '$branch' set up to track '$remote/$remoteBranch'.")
        }
        return ok
    }

    // ---- reset / stash / tag / clean / ls-files ---------------------------------------------------

    private fun reset(git: Git, repo: Repository, a: Args, cwd: File): Boolean {
        val mode = when {
            a.flag("--hard") -> ResetCommand.ResetType.HARD
            a.flag("--soft") -> ResetCommand.ResetType.SOFT
            a.flag("--keep") -> ResetCommand.ResetType.KEEP
            a.flag("--merge") -> ResetCommand.ResetType.MERGE
            else -> ResetCommand.ResetType.MIXED
        }
        var ref: String? = null
        val paths = a.afterDashes.map { rel(repo, cwd, it) }.toMutableList()
        for (arg in a.positional) {
            if (ref == null && paths.isEmpty() && repo.resolve(arg) != null && !File(workTree(repo), arg).exists()) ref = arg
            else if (ref == null && paths.isEmpty() && repo.resolve(arg) != null) ref = arg
            else paths += rel(repo, cwd, arg)
        }
        val cmd = git.reset().setRef(ref ?: Constants.HEAD)
        if (paths.isNotEmpty()) {
            if ("." !in paths) paths.forEach { cmd.addPath(it) }
            cmd.call()
            return true
        }
        cmd.setMode(mode).call()
        if (mode == ResetCommand.ResetType.HARD) {
            val head = repo.resolve(Constants.HEAD)?.let { RevWalk(repo).use { w -> w.parseCommit(it) } }
            if (head != null) p("HEAD is now at ${head.name.take(7)} ${head.shortMessage}")
        }
        return true
    }

    private fun stash(git: Git, repo: Repository, a: Args): Boolean {
        val sub = a.positional.firstOrNull() ?: "push"
        val index = a.positional.getOrNull(1)?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() } ?: 0
        when (sub) {
            "push", "save" -> {
                requireIdentity(repo)
                val c = git.stashCreate().setIncludeUntracked(a.flag("-u", "--include-untracked"))
                    .apply { a.value("-m", "--message")?.let { setWorkingDirectoryMessage(it) } }.call()
                if (c == null) { p("No local changes to save"); return true }
                p("Saved working directory and index state: ${c.shortMessage}")
            }
            "list" -> git.stashList().call().forEachIndexed { i, c -> p("stash@{$i}: ${c.shortMessage}") }
            "apply", "pop" -> {
                if (git.stashList().call().isEmpty()) fail("No stash entries found.")
                val id = git.stashApply().setStashRef("stash@{$index}").call()
                if (id == null) fail("could not apply stash@{$index}")
                if (sub == "pop") { git.stashDrop().setStashRef(index).call(); p("Dropped stash@{$index}") }
            }
            "drop" -> {
                if (git.stashList().call().size <= index) fail("stash@{$index} is not a valid reference")
                git.stashDrop().setStashRef(index).call(); p("Dropped stash@{$index}")
            }
            "clear" -> { git.stashDrop().setAll(true).call() }
            else -> fail("unknown stash command '$sub' (push, list, apply, pop, drop, clear)")
        }
        return true
    }

    private fun tag(git: Git, repo: Repository, a: Args): Boolean {
        val names = a.positional
        if (a.flag("-d", "--delete")) {
            if (names.isEmpty()) fail("tag name required")
            git.tagDelete().setTags(*names.toTypedArray()).call()
            names.forEach { p("Deleted tag '$it'") }
            return true
        }
        if (names.isEmpty() || a.flag("-l", "--list")) {
            git.tagList().call().map { Repository.shortenRefName(it.name) }.sorted().forEach { p(it) }
            return true
        }
        val message = a.value("-m", "--message")
        val annotated = a.flag("-a", "--annotate") || message != null
        if (annotated) requireIdentity(repo)
        val cmd = git.tag().setName(names[0]).setAnnotated(annotated).setSigned(false)
        if (annotated) cmd.setMessage(message ?: names[0])
        names.getOrNull(1)?.let { rev ->
            val id: ObjectId = repo.resolve(rev) ?: fail("Failed to resolve '$rev' as a valid ref.")
            cmd.setObjectId(RevWalk(repo).use { it.parseAny(id) })
        }
        cmd.call()
        return true
    }

    private fun clean(git: Git, repo: Repository, a: Args, cwd: File): Boolean {
        val dry = a.flag("-n", "--dry-run")
        if (!dry && !a.flag("-f", "--force")) fail("clean.requireForce is true: use -f to delete files, or -n to preview.")
        val cmd = git.clean().setCleanDirectories(a.flag("-d")).setDryRun(dry)
        val paths = (a.positional + a.afterDashes).map { rel(repo, cwd, it) }
        if (paths.isNotEmpty() && "." !in paths) cmd.setPaths(paths.toSet())
        cmd.call().sorted().forEach { p((if (dry) "Would remove " else "Removing ") + it) }
        return true
    }

    private fun lsFiles(repo: Repository, cwd: File): Boolean {
        val root = workTree(repo)
        val dc = repo.readDirCache()
        for (i in 0 until dc.entryCount) p(GitPaths.display(root, cwd, dc.getEntry(i).pathString))
        return true
    }

    // ---- errors and help ---------------------------------------------------------------------------

    private fun describe(e: Throwable): String {
        val message = (e.message ?: e.javaClass.simpleName).trim()
        val chain = generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        return when {
            e is TransportException && Regex("""not authorized|401|403|Authentication is required|authentication not supported""", RegexOption.IGNORE_CASE).containsMatchIn(chain) ->
                "fatal: authentication failed. Sign in with a personal access token:\n  git auth login <host> <username> <token>\n($message)"
            e is TransportException && Regex("""UnknownHost|Unable to resolve|timed out|Connection refused|Network is unreachable""", RegexOption.IGNORE_CASE).containsMatchIn(chain) ->
                "fatal: could not reach the server. Check the connection.\n($message)"
            e is TransportException -> "fatal: $message"
            e is NoHeadException -> "fatal: your current branch does not have any commits yet"
            e is InvalidConfigurationException -> "fatal: $message"
            e is CheckoutConflictException -> "error: local changes would be overwritten:\n" + e.conflictingPaths.joinToString("\n") { "\t$it" }
            e is RefNotFoundException -> "fatal: $message"
            e is GitAPIException || e is JGitInternalException -> "error: ${e.cause?.message ?: message}"
            e is java.io.IOException -> "fatal: $message"
            else -> "error: ${e.javaClass.simpleName}: $message"
        }
    }

    private fun help() {
        p("MobileStudio git (built on JGit): HTTPS remotes only")
        p()
        p("  git init [dir] [-b branch]      git clone <url> [dir] [-b branch]")
        p("  git status [-s]                 git add <paths>|.|-A|-u")
        p("  git commit -m <msg> [-a] [--amend]")
        p("  git log [--oneline] [-n N]      git diff [--staged] [rev] [-- path]    git show [rev]")
        p("  git branch [-a|-r|-v] [name] [-d|-D name] [-m new]   git branch -u origin/main")
        p("  git checkout <branch|rev> | -b <new> | -- <path>     git switch [-c] <branch>")
        p("  git restore [--staged] <path>   git reset [--soft|--hard] [rev] | <path>")
        p("  git merge <rev> [--no-ff]       git rebase <upstream> | --continue | --abort")
        p("  git fetch [remote] [--prune]    git pull [--rebase]    git push [-u] [-f] [remote] [branch]")
        p("  git remote [-v] | add | remove | rename | set-url | get-url")
        p("  git stash [push|list|apply|pop|drop|clear]   git tag [-a] [-m msg] [name] [rev] | -d name")
        p("  git rm [--cached] / mv / clean -fd / cherry-pick / revert / ls-files")
        p("  git config [--global] [--unset] <key> [<value>] | -l")
        p()
        p("Sign in once for private repos and pushing:")
        p("  git auth login [host] <username> <token>   (a personal access token; host defaults to github.com)")
        p("First time? git config --global user.name \"Your Name\"  and  user.email \"you@example.com\"")
        p("Run git on its own line (no pipes, && or ;). Run 'cd' on its own line too.")
    }

    // ---- argument parsing --------------------------------------------------------------------------

    /**
     * Minimal option parser. [valueOptions] take the following word as their value (or `--opt=value`).
     * Bundled short flags (`-am`) are split, and `-5` / `-n5` mean `-n 5`.
     */
    private class Args(raw: List<String>, private val valueOptions: Set<String> = emptySet()) {
        val positional = mutableListOf<String>()
        val afterDashes = mutableListOf<String>()
        private val flags = HashSet<String>()
        private val values = HashMap<String, MutableList<String>>()

        init {
            val words = expand(raw)
            var i = 0
            var dashes = false
            while (i < words.size) {
                val w = words[i]
                when {
                    dashes -> afterDashes += w
                    w == "--" -> dashes = true
                    w.startsWith("--") && '=' in w -> values.getOrPut(w.substringBefore('=')) { mutableListOf() } += w.substringAfter('=')
                    w in valueOptions -> {
                        val v = words.getOrNull(i + 1) ?: throw Fail("option '$w' requires a value")
                        values.getOrPut(w) { mutableListOf() } += v; i++
                    }
                    w.startsWith("-") && w.length > 1 -> flags += w
                    else -> positional += w
                }
                i++
            }
        }

        fun flag(vararg names: String) = names.any { it in flags }
        fun value(vararg names: String): String? = names.firstNotNullOfOrNull { values[it]?.lastOrNull() }
        fun values(vararg names: String): List<String> = names.flatMap { values[it].orEmpty() }

        private fun expand(raw: List<String>): List<String> = raw.flatMap { w ->
            when {
                Regex("""^-\d+$""").matches(w) -> listOf("-n", w.substring(1))
                Regex("""^-n\d+$""").matches(w) -> listOf("-n", w.substring(2))
                Regex("""^-[A-Za-z]{2,}$""").matches(w) && w !in valueOptions -> w.drop(1).map { "-$it" }
                else -> listOf(w)
            }
        }
    }

    private companion object { const val DEFAULT_LOG_LIMIT = 50 }
}
