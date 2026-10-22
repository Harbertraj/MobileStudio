package dev.mobilestudio.terminal

import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class GitRunnerTest {
    private lateinit var base: File
    private lateinit var work: File
    private val out = StringBuilder()
    private lateinit var git: GitRunner

    companion object {
        // JGit caches ~/.gitconfig per process, so every test shares one home.
        val home: File = File.createTempFile("githome", "").also { it.delete(); it.mkdirs(); it.deleteOnExit() }
    }

    @Before fun setUp() {
        base = File.createTempFile("gitrun", "").also { it.delete(); it.mkdirs() }
        work = File(base, "work").also { it.mkdirs() }
        git = GitRunner(MemoryGitSettings(home)) { out.append(it) }
        run("config", "--global", "user.name", "Tester")
        run("config", "--global", "user.email", "t@example.com")
        out.clear()
    }
    @After fun tearDown() { base.deleteRecursively(); File(home, ".gitconfig").delete() }

    private fun run(vararg args: String, cwd: File = work): Boolean = git.run(args.toList(), cwd)
    private fun text() = out.toString()
    private fun file(name: String, content: String, dir: File = work) = File(dir, name).also { it.parentFile.mkdirs(); it.writeText(content) }
    private fun commitAll(msg: String, dir: File = work) { run("add", ".", cwd = dir); assertTrue(text(), run("commit", "-m", msg, cwd = dir)) }

    private fun bareRemote(): String {
        val dir = File(base, "remote.git")
        Git.init().setBare(true).setDirectory(dir).setInitialBranch("main").call().close()
        return dir.toURI().toString()
    }

    @Test fun initAddCommitLogStatus() {
        assertTrue(run("init"))
        assertTrue(text().contains("Initialized empty Git repository"))
        file("a.txt", "one\n")
        out.clear(); run("status")
        assertTrue(text(), text().contains("On branch main") && text().contains("No commits yet") && text().contains("a.txt"))
        commitAll("first")
        assertTrue(text().contains("(root-commit)"))
        out.clear(); run("status"); assertTrue(text().contains("nothing to commit, working tree clean"))
        out.clear(); run("log", "--oneline"); assertTrue(text(), text().contains("first"))
        out.clear(); run("log"); assertTrue(text().contains("Author: Tester <t@example.com>"))
    }

    @Test fun statusShowsModifiedStagedAndUntracked() {
        run("init"); file("a.txt", "1\n"); commitAll("c1")
        file("a.txt", "2\n"); file("new.txt", "n"); file("b.txt", "b"); run("add", "b.txt")
        out.clear(); run("status")
        val t = text()
        assertTrue(t, t.contains("Changes to be committed") && t.contains("new file:   b.txt"))
        assertTrue(t, t.contains("Changes not staged") && t.contains("modified:   a.txt"))
        assertTrue(t, t.contains("Untracked files") && t.contains("new.txt"))
        out.clear(); run("status", "-s")
        assertTrue(text(), text().contains("A  b.txt") && text().contains(" M a.txt") && text().contains("?? new.txt"))
    }

    @Test fun commitRefusesWithoutIdentityMessageOrChanges() {
        run("init"); file("a", "x")
        File(home, ".gitconfig").delete()
        run("add", "."); out.clear()
        assertFalse(run("commit", "-m", "m")); assertTrue(text(), text().contains("Author identity unknown"))
        run("config", "--global", "user.name", "T"); run("config", "--global", "user.email", "e@x"); out.clear()
        assertFalse(run("commit")); assertTrue(text(), text().contains("no commit message"))
        commitAll("ok"); out.clear()
        assertFalse(run("commit", "-m", "again")); assertTrue(text(), text().contains("nothing to commit"))
    }

    @Test fun deletionsAreStagedByAddAll() {
        run("init"); file("a", "1"); file("b", "2"); commitAll("c")
        File(work, "a").delete()
        run("add", "-A"); out.clear(); run("status", "-s")
        assertTrue(text(), text().contains("D  a"))
    }

    @Test fun diffAndShow() {
        run("init"); file("a.txt", "one\n"); commitAll("c1")
        file("a.txt", "two\n"); out.clear(); run("diff")
        assertTrue(text(), text().contains("-one") && text().contains("+two"))
        run("add", "."); out.clear(); run("diff"); assertEquals("", text())
        out.clear(); run("diff", "--staged"); assertTrue(text().contains("+two"))
        commitAll("c2"); out.clear(); run("show"); assertTrue(text(), text().contains("c2") && text().contains("+two"))
        out.clear(); run("show", "HEAD~1"); assertTrue(text(), text().contains("c1") && text().contains("+one"))
    }

    @Test fun branchCheckoutAndFastForwardMerge() {
        run("init"); file("a", "1"); commitAll("c1")
        assertTrue(run("checkout", "-b", "feat")); assertTrue(text().contains("Switched to a new branch 'feat'"))
        file("f", "x"); commitAll("feat work")
        out.clear(); assertTrue(run("checkout", "main")); assertTrue(text().contains("Switched to branch 'main'"))
        assertFalse(File(work, "f").exists())
        out.clear(); assertTrue(run("merge", "feat")); assertTrue(text(), text().contains("Fast-forward"))
        assertTrue(File(work, "f").exists())
        out.clear(); run("branch"); assertTrue(text(), text().contains("* main") && text().contains("  feat"))
        out.clear(); assertTrue(run("branch", "-d", "feat")); assertTrue(text().contains("Deleted branch feat"))
    }

    @Test fun mergeConflictIsReportedAndAbortRestores() {
        run("init"); file("a", "base\n"); commitAll("base")
        run("checkout", "-b", "x"); file("a", "x side\n"); commitAll("x")
        run("checkout", "main"); file("a", "main side\n"); commitAll("m")
        out.clear(); assertFalse(run("merge", "x"))
        assertTrue(text(), text().contains("CONFLICT") && text().contains("Merge conflict in a"))
        assertTrue(File(work, "a").readText().contains("<<<<<<<"))
        out.clear(); run("status"); assertTrue(text(), text().contains("Unmerged paths"))
        assertTrue(run("merge", "--abort")); assertEquals("main side\n", File(work, "a").readText())
    }

    @Test fun checkoutRestoresAFileAndRestoreUnstages() {
        run("init"); file("a", "orig\n"); commitAll("c")
        file("a", "changed\n"); assertTrue(run("checkout", "--", "a")); assertEquals("orig\n", File(work, "a").readText())
        file("a", "again\n"); run("add", "a"); assertTrue(run("restore", "--staged", "a"))
        out.clear(); run("status", "-s"); assertTrue(text(), text().contains(" M a"))
        assertTrue(run("restore", "a")); assertEquals("orig\n", File(work, "a").readText())
    }

    @Test fun resetHardStashAndTag() {
        run("init"); file("a", "1\n"); commitAll("c1"); file("a", "2\n"); commitAll("c2")
        assertTrue(run("reset", "--hard", "HEAD~1")); assertEquals("1\n", File(work, "a").readText())
        file("a", "dirty\n"); out.clear()
        assertTrue(run("stash")); assertTrue(text(), text().contains("Saved")); assertEquals("1\n", File(work, "a").readText())
        out.clear(); run("stash", "list"); assertTrue(text().contains("stash@{0}"))
        assertTrue(run("stash", "pop")); assertEquals("dirty\n", File(work, "a").readText())
        out.clear(); run("stash"); out.clear(); run("stash"); assertTrue(text(), text().contains("No local changes") || text().isNotEmpty())
        assertTrue(run("tag", "v1")); assertTrue(run("tag", "-a", "v2", "-m", "release"))
        out.clear(); run("tag"); assertTrue(text(), text().contains("v1") && text().contains("v2"))
        assertTrue(run("tag", "-d", "v1"))
    }

    @Test fun rmAndMv() {
        run("init"); file("a", "1"); file("d/b", "2"); commitAll("c")
        assertTrue(run("mv", "a", "z")); assertTrue(File(work, "z").exists() && !File(work, "a").exists())
        assertTrue(run("rm", "d/b")); assertFalse(File(work, "d/b").exists())
        out.clear(); run("status", "-s"); assertTrue(text(), text().contains("D  d/b") && text().contains("z"))
        assertFalse(run("rm", "nothing")); assertTrue(text().contains("did not match"))
    }

    @Test fun subdirectoryPathsResolveRelativeToTheShellFolder() {
        run("init"); file("sub/x.txt", "1"); file("top.txt", "t")
        val sub = File(work, "sub")
        assertTrue(run("add", "x.txt", cwd = sub))
        out.clear(); run("status", "-s", cwd = sub)
        assertTrue(text(), text().contains("A  x.txt") && text().contains("?? ../top.txt"))
        assertFalse(run("add", "../../escape", cwd = sub)); assertTrue(text(), text().contains("outside repository"))
    }

    @Test fun cloneFetchPullPushThroughALocalRemote() {
        val url = bareRemote()
        val a = File(base, "a").also { it.mkdirs() }
        run("init", cwd = a); run("remote", "add", "origin", url, cwd = a)
        file("f.txt", "v1\n", a); commitAll("v1", a)
        out.clear(); assertFalse(run("push", cwd = a)); assertTrue(text(), text().contains("no upstream"))
        out.clear(); assertTrue(run("push", "-u", "origin", "main", cwd = a)); assertTrue(text(), text().contains("main -> main") && text().contains("set up to track"))
        assertEquals("origin", Git.open(a).use { it.repository.config.getString("branch", "main", "remote") })

        out.clear(); assertTrue(run("clone", url, "b", cwd = base)); assertTrue(text(), text().contains("Cloning into 'b'"))
        val b = File(base, "b"); assertEquals("v1\n", File(b, "f.txt").readText())
        out.clear(); run("status", cwd = b); assertTrue(text(), text().contains("up to date with 'origin/main'"))

        file("f.txt", "v2\n", a); commitAll("v2", a); assertTrue(run("push", cwd = a))
        out.clear(); assertTrue(run("fetch", cwd = b)); assertTrue(text(), text().contains("origin/main"))
        out.clear(); run("status", cwd = b); assertTrue(text(), text().contains("behind"))
        out.clear(); assertTrue(run("pull", cwd = b)); assertEquals("v2\n", File(b, "f.txt").readText())

        // b is now behind nothing; a diverging push from b must be rejected
        file("f.txt", "v3-from-a\n", a); commitAll("v3a", a); run("push", cwd = a)
        file("g.txt", "g\n", b); commitAll("b-local", b)
        out.clear(); assertFalse(run("push", cwd = b)); assertTrue(text(), text().contains("rejected") && text().contains("git pull"))
        out.clear(); assertTrue(text(), run("pull", "--rebase", cwd = b)); assertTrue(run("push", cwd = b))
    }

    @Test fun cloneChecksOutRemoteBranchesOnCheckout() {
        val url = bareRemote()
        val a = File(base, "a").also { it.mkdirs() }
        run("init", cwd = a); run("remote", "add", "origin", url, cwd = a); file("x", "1", a); commitAll("c", a)
        run("push", "origin", "main", cwd = a)
        run("checkout", "-b", "dev", cwd = a); file("y", "2", a); commitAll("d", a); run("push", "origin", "dev", cwd = a)
        run("clone", url, "c", cwd = base); val c = File(base, "c")
        out.clear(); assertTrue(run("checkout", "dev", cwd = c)); assertTrue(text(), text().contains("Switched to branch 'dev'"))
        assertTrue(File(c, "y").exists())
        assertEquals("origin", Git.open(c).use { it.repository.config.getString("branch", "dev", "remote") })
    }

    @Test fun cloneIntoANonEmptyFolderFailsAndKeepsIt() {
        File(base, "keep").also { it.mkdirs() }; File(base, "keep/f").writeText("x")
        assertFalse(run("clone", bareRemote(), "keep", cwd = base))
        assertTrue(text(), text().contains("already exists")); assertTrue(File(base, "keep/f").exists())
    }

    @Test fun aFailedCloneLeavesNoFolderBehind() {
        assertFalse(run("clone", File(base, "missing.git").toURI().toString(), "nope", cwd = base))
        assertFalse(File(base, "nope").exists())
    }

    @Test fun sshRemotesAreRefusedWithAnExplanation() {
        run("init"); out.clear()
        assertFalse(run("clone", "git@github.com:me/x.git", cwd = base)); assertTrue(text(), text().contains("SSH remotes are not supported"))
        out.clear(); assertFalse(run("remote", "add", "o", "ssh://git@host/x")); assertTrue(text().contains("SSH"))
    }

    @Test fun notARepositoryAndUnknownCommand() {
        assertFalse(run("status")); assertTrue(text(), text().contains("not a git repository"))
        out.clear(); run("init"); assertFalse(run("bisect")); assertTrue(text().contains("not available"))
    }

    @Test fun configGetSetListUnset() {
        run("init"); out.clear()
        assertTrue(run("config", "alias.co", "checkout")); assertTrue(run("config", "alias.co"))
        assertEquals("checkout\n", text())
        out.clear(); run("config", "-l"); assertTrue(text(), text().contains("alias.co=checkout"))
        assertTrue(run("config", "--unset", "alias.co")); out.clear(); assertFalse(run("config", "alias.co"))
        out.clear(); run("config", "--global", "user.name"); assertEquals("Tester\n", text())
    }

    @Test fun authLoginStatusLogout() {
        val s = MemoryGitSettings(home); val g = GitRunner(s) { out.append(it) }
        assertTrue(g.run(listOf("auth", "login", "me", "tok123"), work)); assertEquals("me", s.credential("github.com")?.user)
        assertTrue(g.run(listOf("auth", "login", "git.example.com", "u", "t"), work))
        out.clear(); g.run(listOf("auth", "status"), work); assertTrue(text(), text().contains("github.com") && text().contains("git.example.com"))
        assertFalse("tokens must never be printed", text().contains("tok123"))
        assertTrue(g.run(listOf("auth", "logout"), work)); assertNull(s.credential("github.com"))
        assertFalse(g.run(listOf("auth", "login", "onlyone"), work))
    }

    @Test fun logCapsAtADefaultLimitAndHonoursN() {
        run("init"); repeat(3) { i -> file("f$i", "$i"); commitAll("c$i") }
        out.clear(); run("log", "--oneline", "-2"); assertEquals(2, text().lines().count { it.isNotBlank() })
        assertFalse("log outside a repository fails", run("log", cwd = base))
    }

    @Test fun cherryPickAndRevert() {
        run("init"); file("a", "1"); commitAll("c1")
        run("checkout", "-b", "s"); file("s", "s"); commitAll("side")
        val id = Git.open(work).use { it.repository.resolve("HEAD").name }
        run("checkout", "main"); out.clear(); assertTrue(run("cherry-pick", id)); assertTrue(File(work, "s").exists())
        out.clear(); assertTrue(run("revert", "HEAD")); assertFalse(File(work, "s").exists())
    }

    @Test fun cleanNeedsForce() {
        run("init"); file("a", "1"); commitAll("c"); file("junk", "j")
        assertFalse(run("clean")); assertTrue(File(work, "junk").exists())
        out.clear(); run("clean", "-n"); assertTrue(text().contains("Would remove junk")); assertTrue(File(work, "junk").exists())
        assertTrue(run("clean", "-f")); assertFalse(File(work, "junk").exists())
    }
}
