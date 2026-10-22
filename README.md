# MobileStudio

An "Android Studio for Android": an IDE app that runs on the phone. Kotlin, Jetpack Compose, MVVM.

## What it does

- **Open any folder** on the phone's storage (folder browser, recent folders) and work on it as a project,
  or create a project (Kotlin or Java template) in the app's own workspace
- **Edit code**: file tree, tabs, line numbers, syntax colouring (Kotlin, Gradle, Java, XML), undo/redo,
  auto-indent and bracket pairing, word completion, find/replace (regex), search across the project,
  live warnings for unbalanced brackets and mismatched XML tags
- **Terminal**: a persistent shell opened in the project folder (`cd` and variables persist), plus built-in
  commands: `build`, `build release`, `install`, `toolchain`, `keystore`, `help` and a full **`git`** (see below).
  No pseudo-terminal: full-screen programs and Ctrl+C do not work; the restart button ends a stuck command
- **Build an APK**: reads `app/build.gradle(.kts)`, patches the manifest, runs aapt2, compiles Kotlin and Java,
  dexes with D8, packages (aligned), signs with a debug key, and offers Install

## Build toolchain (downloaded for you)

A build needs **aapt2**, **android.jar** (matching `compileSdk`) and, for Kotlin, **kotlin-stdlib**. You no longer
have to find them: the build downloads what is missing as its first step ("Prepare toolchain"), and the wrench
dialog has a **Download missing tools** button (terminal: `toolchain setup`).

| Piece | Downloaded from |
|---|---|
| kotlin-stdlib | Maven Central |
| android.jar | Google's SDK repository (the platform package for your `compileSdk`; only `android.jar` is kept) |
| aapt2 | an Android (aarch64) build in a GitHub release (`Lzhiyong/sdk-tools`), or **any address you give**: `toolchain aapt2-url <url>` or the field in the dialog. A `.zip`, `.tar.gz` or the bare binary all work |

Every download goes through the same checks as a manual import (ELF with the right CPU, `Activity.class` inside
android.jar, valid jar), and a downloaded aapt2 is run once (`aapt2 version`) before it is accepted. Manual import
still works. If a download fails, the message says which piece and why.

aapt2 is the one piece without an official Android build from Google. If the default source stops working,
point `toolchain aapt2-url` at your own copy. Bundling it as `jniLibs/arm64-v8a/libaapt2.so` also works and wins.

## Debug and release APKs

- `build` / the play button: **debug**, signed with the shared public debug key (testing only)
- `build release` / menu "Build release APK": D8 release mode, signed with a **release key**:
  1. `keystore.properties` in the project folder (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`), or
  2. otherwise a key **generated once on the device** and kept in the app's private storage
- `keystore export` copies the device key and a ready `keystore.properties` into `<project>/release-keys/`.
  Back it up and do not commit it: an installed app can only be updated by an APK signed with the same key
- Finished APKs are copied to `<project>/build/outputs/apk/<debug|release>/`; `install` opens the installer

## Git in the terminal

There is no git binary on a phone, so `git` is built in (JGit). Supported: `init clone status add rm mv commit log
diff show branch checkout switch restore reset merge rebase fetch pull push remote stash tag cherry-pick revert
clean config ls-files`. Run `git help` for the options.

```
git config --global user.name "Your Name"
git config --global user.email "you@example.com"
git auth login <username> <personal-access-token>        # github.com; or: git auth login <host> <user> <token>
git clone https://github.com/you/repo.git
cd repo
git add .                 # one command per line
git commit -m "message"
git push -u origin main
```

Limits: **HTTPS remotes only** (no SSH), no shallow clone (`--depth` is ignored), no hooks or submodules, nothing
interactive (`rebase -i`, `add -p`). Git commands, `cd` and `build` must be typed on their own line (no pipes,
`&&` or `;`); the app says so when you try. Tokens are kept in the app's private preferences. Git files on shared
storage get `core.filemode=false` because that storage cannot hold the executable bit.

## Why the app targets API 28

`targetSdk = 28` in `app/build.gradle.kts` is deliberate, the same choice Termux makes. From API 29 an app is
not allowed to execute files from its own storage, and this app runs the imported aapt2 and shell commands.
API 28 also keeps ordinary `java.io` access to shared storage for "Open folder" (granted by the storage
permission). The cost: not publishable on Google Play, and Android shows an "built for an older version" notice
on newer devices. To publish later, bundle aapt2 as `jniLibs/<abi>/libaapt2.so` (already supported) and
revisit file access.

## Verification status (please read)

Verified on a plain JVM (216 unit tests, `app/src/test`, compiled with Kotlin 2.0.21 and run with JUnit):
the editor logic, folder browsing, path-confinement of file operations, toolchain import validation, the
terminal against a real `sh` process, Gradle-config and manifest patching, APK packaging and 4-byte
alignment (a deliberate break made the test fail), the pipeline orchestration (with a fake aapt2), and
**mixed Kotlin + Java compilation with the real Kotlin compiler**, loading and running the output.
The debug keystore asset is checked to decode to a usable RSA key.

The downloader is tested against a local HTTP server (zip, tar.gz and bare binaries, redirects, wrong-CPU and
broken binaries). The git commands are tested against real repositories, including clone/push/pull/rebase
through a local bare remote. Release signing is tested with a self-made certificate and generated keystores.

**Not run anywhere**, so treat as untested until you try it:

- the real download sources over the internet (they were unreachable from the environment this was written in),
  and that the downloaded aapt2 actually runs on your phone
- JGit on Android (it is a standard Android-compatible release, but it was only exercised on a desktop JVM)
- authenticated push to GitHub with a token (the transport was only exercised against a local remote)

- the Compose screens (`ui/StudioApp.kt`, `ui/CodeEditor.kt`, `ui/Dialogs.kt`) and the Gradle build itself
  (there was no Android SDK or Maven access). They contain no syntax errors, but expect a few compile fixes on
  the first sync
- the real aapt2, ECJ (Java compiler), D8 (dexer) and apksig (signer) calls, `ApkInstaller`
- running the Kotlin compiler **on a phone**. It works on a desktop JVM; the Android runtime (ART), its
  memory limits and the compiler's own dependencies may not cooperate. The Java template avoids it and is
  the safest first thing to try
- the APK size: the bundled compilers add tens of MB. `META-INF` clashes between them may need extra
  `packaging` rules in `app/build.gradle.kts`

The debug signing key (`assets/debug.p12.b64`) is public and shared. APKs signed with it are for testing only.

## Running it

1. Open this folder in Android Studio and sync (Gradle wrapper 8.10.2, matching Android Gradle Plugin 8.7.3).
2. Run unit tests: `./gradlew :app:testDebugUnitTest`
3. Run the `app` configuration on a device (minSdk 26), grant storage access when asked, import the toolchain.

## Layout

```
app/src/main/java/dev/mobilestudio/
  data/      ProjectRepository, Templates, ProjectSearch, FolderBrowser (+RecentFolders, ModuleLocator)
  editor/    SyntaxHighlighter, UndoStack, AutoEdit, Completion, FindReplace, Diagnostics, TextPositions
  terminal/  ShellSession, TerminalBuffer (+CommandHistory, TerminalCommands), GitRunner, CommandLine, ShellCwd, GitPaths
  build/     BuildEngine, BuildPipeline, BuildSteps, SourceCompilers, K2KotlinRunner, EcjJavaRunner,
             D8Dexer, ApkPackager, ApkSignerAdapter, ApkInstaller, Toolchain, ToolchainStore, ToolchainInstaller,
             ReleaseKeystore, SelfSignedCert, ...
  ui/        StudioViewModel, StudioApp, CodeEditor, Dialogs
```
