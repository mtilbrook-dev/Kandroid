# Kandroid

A collection of Gradle plugins for Android and Kotlin Multiplatform projects.

| Plugin | ID | Status |
| --- | --- | --- |
| [typed-manifest](#typed-manifest) | `dev.tilbrook.typed-manifest` | 0.1.0, activities only |

Maven group: `dev.tilbrook`.

## Repository layout

```
Kandroid/
├── core/                      # modules shared by more than one plugin (see core/README.md)
├── typed-manifest/            # one directory per plugin
│   ├── annotations/           #   dev.tilbrook:typed-manifest-annotations
│   ├── lint/                  #   dev.tilbrook:typed-manifest-lint (Android Lint checks)
│   └── gradle-plugin/         #   dev.tilbrook:typed-manifest-gradle-plugin
│       └── src/
│           ├── main/          #   plugin + source scanner + generator + migrator
│           ├── test/          #   fast unit tests
│           └── integrationTest/  # TestKit builds of app / library / KMP fixtures
├── gradle/libs.versions.toml  # single version catalog for every plugin
└── .github/workflows/android.yml
```

Each plugin lives in `projectRoot/<plugin-name>/`, split into whatever modules it needs. Code that a
second plugin needs moves into `core/<module>/`.

## typed-manifest

Declare `<activity>` entries on the class instead of in `AndroidManifest.xml`:

```kotlin
plugins {
    id("com.android.application")
    id("dev.tilbrook.typed-manifest") version "0.1.0"
}
```

```kotlin
import dev.tilbrook.kandroid.typedmanifest.Manifest

@Manifest.Activity(
    exported = true,
    theme = "@style/Theme.App",
    launchMode = Manifest.LaunchMode.SINGLE_TOP,
    intentFilters = [
        Manifest.IntentFilter(
            actions = [Manifest.Action.MAIN],
            categories = [Manifest.Category.LAUNCHER],
        ),
    ],
)
class MainActivity : ComponentActivity()

@Manifest.Activity // -> <activity android:name="...DetailActivity" android:exported="false" />
class DetailActivity : ComponentActivity()
```

### What it does

- **Generates a manifest overlay per variant.** `generate<Variant>TypedManifest` scans the variant's
  Kotlin and Java source directories, then hands the result to AGP through
  `variant.sources.manifests`. AGP's normal manifest merger takes it from there.
  `com.android.kotlin.multiplatform.library` ignores those overlays, so there
  `inject<Variant>TypedManifest` transforms the `MERGED_MANIFEST` artifact instead.
- **Generates before `pre<Variant>Build`.** The generate task is wired in lazily
  (`tasks.named { it == "preDebugBuild" }`), so the overlay exists before AGP's pre-build checks.
- **Follows `android.builtInKotlin`.** With AGP's built-in Kotlin (the AGP 9 default) or a Kotlin
  plugin applied, `.kt` and `.java` files are scanned. With `android.builtInKotlin=false` and no
  Kotlin plugin, `.kt` files aren't compiled, so they're ignored.
- **Secure by default.** `android:exported` is always written and defaults to `false`. A
  `MAIN`/`LAUNCHER` activity that isn't exported fails the build with a `File.kt:line` error.
- **Only what you write is emitted.** The Kotlin default values on the annotation are placeholders.
  Unset parameters are not written to the manifest, so platform defaults apply.
- **Adds the annotations as a compile-only dependency.** For each enabled entry kind that is valid
  for the module type, the plugin adds `dev.tilbrook:typed-manifest-annotations` to `compileOnly`
  (`androidMainCompileOnly` for KMP). The annotations have `SOURCE` retention, so nothing ships in your APK/AAR.
- **Kotlin-version friendly.** Neither published artifact depends on `kotlin-stdlib` (it is
  `compileOnly`), and both are compiled with language/API version 2.2. A consumer's Kotlin version
  is never bumped, and Kotlin 2.2+ compilers (including AGP 9's built-in Kotlin) can read them.
- **Covers every `<activity>` attribute**, plus `<intent-filter>` (with `<action>`, `<category>`,
  `<data>`), `<meta-data>` and `<layout>`.

Supported and tested modules:

| Module | Kotlin compiled by |
| --- | --- |
| `com.android.application` / `com.android.library` | AGP built-in Kotlin (default) |
| `com.android.application` / `com.android.library` + `org.jetbrains.kotlin.android` | KGP; needs `android.builtInKotlin=false` and `android.newDsl=false` on AGP 9 |
| `com.android.kotlin.multiplatform.library` | KGP |
| `kotlin("multiplatform")` + `com.android.application` | KGP; AGP 9 only allows this with `android.builtInKotlin=false` and `android.newDsl=false` |

### Lint checks

The plugin adds `dev.tilbrook:typed-manifest-lint` to `lintChecks`, so these run in `./gradlew lint`
and show as highlights (with tooltips and quick fixes) in Android Studio:

| ID | Flags | Fix |
| --- | --- | --- |
| `IntentFilterRequiresExported` | An activity with a filter that only other apps or the system use (LAUNCHER, BROWSABLE, `autoVerify` App Links, SEND share targets, settings, widget configuration, …) that isn't `exported = true` | Set `exported = true` |
| `ImplicitIntentToUnexportedActivity` | `Intent("action")`, `setAction(…)` or `intent.action = …` with no package or component set, whose action matches a non-exported activity. On targetSdk 34+, implicit intents only reach exported activities | Make the intent explicit, or export the activity |
| `ExportedWithoutRequirement` | `exported = true` with no intent filter and no `permission`, so any app can start it by class name | Set `exported = false`, or guard it with a (signature) permission |

The first and third checks run on both `@Manifest.Activity` and `<activity>` in AndroidManifest.xml.
The implicit-intent check reads the module's merged manifest (which includes generated entries)
plus annotations in the current file. It supports lint's per-module (partial) analysis.

An exported activity without an intent filter is sometimes legitimate: a companion app or SDK
partner starting it by component name, or a system `settingsActivity` reference. Protect it with
`permission`, or suppress the check with `@SuppressLint("ExportedWithoutRequirement")` /
`tools:ignore`.

Turn the checks off with `typedManifest { addLintChecks = false }`.

### Why the plugin reads source

Manifest merging runs before Kotlin compilation, and compilation depends on the merged manifest
(via R). A KSP or bytecode-based generator would create a task cycle. Reading source avoids it, with
these limits:

- Arguments must be literals, `Manifest.*` enum entries, `Manifest.Action`/`Manifest.Category`
  constants, `Foo::class` / `Foo.class`, arrays of those, nested `Manifest.*(...)` calls, or string
  literals joined with `+`. Your own `const val` references are reported as errors, not resolved.
- Use named arguments.

### Configuration

```kotlin
typedManifest {
    addAnnotationDependency = true // default
    addLintChecks = true           // default
    activities { enabled = true }  // default
}
```

Entry kinds are modelled by `ManifestEntryKind`, each with the module types it supports and its
annotation artifact. Services, receivers, providers and application-level entries plug in there.

### Migrating an existing manifest

```
./gradlew :app:migrateManifestToAnnotations --dry-run
./gradlew :app:migrateManifestToAnnotations
```

For each `<activity>` in the module's main manifest (`src/main` or `src/androidMain`), the task:

1. finds the class in the module's non-test source roots (Kotlin or Java, nested classes included);
2. inserts an equivalent `@Manifest.Activity(...)` above the class declaration and adds the import
   (or uses the fully-qualified name if `android.Manifest` is already imported);
3. removes the `<activity>` element from the manifest and leaves the rest of the file untouched.

It leaves an activity in the manifest and tells you why when it can't be represented exactly:
`tools:` attributes, resource-valued booleans/ints (`@bool/...`), manifest placeholders, classes
outside the module, or classes already annotated. Activities that had intent filters but no
`android:exported` (pre-API-31 implicit export) migrate as `exported = true`, with a warning.

## Building

Requires JDK 17+ (CI uses Zulu 23) and an Android SDK for the integration tests.

```
./gradlew test             # unit tests
./gradlew integrationTest  # publishes to build/test-repo, builds fixture projects with AGP
./gradlew build            # everything
```

Integration tests are skipped when `ANDROID_HOME` isn't set.

## License

Apache 2.0
