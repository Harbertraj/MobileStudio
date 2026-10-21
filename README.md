# MobileStudio

An "Android Studio for Android": an IDE app that runs on the phone. Kotlin, Jetpack Compose, MVVM.

## What it does

- **Open any folder** on the phone's storage (folder browser, recent folders) and work on it as a project,
  or create a project (Kotlin or Java template) in the app's own workspace
- **Edit code**: file tree, tabs, line numbers, syntax colouring (Kotlin, Gradle, Java, XML), undo/redo,
  auto-indent and bracket pairing, word completion, find/replace (regex), search across the project,
  live warnings for unbalanced brackets and mismatched XML tags
- **Terminal**: a persistent shell opened in the project folder (`cd` and variables persist). `build` runs
  the project build, `clear` clears the screen. No pseudo-terminal: full-screen programs and Ctrl+C do not
  work; the restart button ends a stuck command
- **Build an APK**: reads `app/build.gradle(.kts)`, patches the manifest, runs aapt2, compiles Kotlin and Java,
  dexes with D8, packages (aligned), signs with a debug key, and offers Install

## One-time setup on the phone: the build toolchain

An APK cannot be built from nothing. Open **Build toolchain** (the wrench on the home screen) and import:

| File | What it is | Needed for |
|---|---|---|
| **aapt2** | the Android (aarch64) build of aapt2, an ELF executable | every build |
| **android.jar** | from an SDK platform folder (`platforms/android-NN/android.jar`), matching `compileSdk` | every build |
| **kotlin-stdlib-*.jar** | Kotlin standard library jar (find it in `~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib`) | Kotlin projects |

Imports are checked (an ELF file for aapt2, a jar containing `android/app/Activity.class` for android.jar).
The build says exactly which piece is missing. I could not find or verify a download source for the aarch64
aapt2 from the environment this was written in, so you need to supply that binary yourself.

Only projects that use the plain Android framework build. Library dependencies (AppCompat, Compose, ...) are
not resolved; the sample templates use only `android.app.Activity`.

## Why the app targets API 28

`targetSdk = 28` in `app/build.gradle.kts` is deliberate, the same choice Termux makes. From API 29 an app is
not allowed to execute files from its own storage, and this app runs the imported aapt2 and shell commands.
API 28 also keeps ordinary `java.io` access to shared storage for "Open folder" (granted by the storage
permission). The cost: not publishable on Google Play, and Android shows an "built for an older version" notice
on newer devices. To publish later, bundle aapt2 as `jniLibs/<abi>/libaapt2.so` (already supported) and
revisit file access.

## Verification status (please read)

Verified on a plain JVM (151 unit tests, `app/src/test`, compiled with Kotlin 2.0.21 and run with JUnit):
the editor logic, folder browsing, path-confinement of file operations, toolchain import validation, the
terminal against a real `sh` process, Gradle-config and manifest patching, APK packaging and 4-byte
alignment (a deliberate break made the test fail), the pipeline orchestration (with a fake aapt2), and
**mixed Kotlin + Java compilation with the real Kotlin compiler**, loading and running the output.
The debug keystore asset is checked to decode to a usable RSA key.

**Not run anywhere**, so treat as untested until you try it:

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
  terminal/  ShellSession, TerminalBuffer (+CommandHistory, TerminalCommands)
  build/     BuildEngine, BuildPipeline, BuildSteps, SourceCompilers, K2KotlinRunner, EcjJavaRunner,
             D8Dexer, ApkPackager, ApkSignerAdapter, ApkInstaller, Toolchain, ToolchainStore, ...
  ui/        StudioViewModel, StudioApp, CodeEditor, Dialogs
```
