package dev.tilbrook.kandroid.typedmanifest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File

class TypedManifestPluginIntegrationTest {

    @BeforeEach
    fun requireAndroidSdk() {
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        assumeTrue(sdk != null && File(sdk).isDirectory, "ANDROID_HOME is not set; skipping Android builds")
    }

    private fun FixtureProject.baseManifest() =
        file(kind.manifestPath, "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n    <application />\n</manifest>\n")

    @ParameterizedTest
    @EnumSource(ModuleKind::class)
    fun `annotated activities are merged into the manifest`(kind: ModuleKind) {
        val project = FixtureProject(kind, "generate")
        project.baseManifest()
        val pkg = kind.namespace
        project.file(
            "${kind.kotlinDir}/${kind.packagePath}/MainActivity.kt",
            """
            package $pkg

            import android.app.Activity
            import dev.tilbrook.kandroid.typedmanifest.Manifest

            @Manifest.Activity(
                exported = true,
                launchMode = Manifest.LaunchMode.SINGLE_TOP,
                configChanges = [Manifest.ConfigChange.ORIENTATION, Manifest.ConfigChange.SCREEN_SIZE],
                intentFilters = [
                    Manifest.IntentFilter(
                        actions = [Manifest.Action.MAIN],
                        categories = [Manifest.Category.LAUNCHER],
                    ),
                ],
            )
            class MainActivity : Activity()

            @Manifest.Activity(parentActivity = MainActivity::class)
            class DetailActivity : Activity()
            """.trimIndent(),
        )
        kind.javaDir?.let { javaDir ->
            project.file(
                "$javaDir/${kind.packagePath}/JavaActivity.java",
                """
                package $pkg;

                import dev.tilbrook.kandroid.typedmanifest.Manifest;

                @Manifest.Activity(screenOrientation = Manifest.ScreenOrientation.PORTRAIT)
                public class JavaActivity extends android.app.Activity {}
                """.trimIndent(),
            )
        }

        val build = project.run(kind.assembleTask)
        project.assertGenerateTaskRan(build)

        val activities = project.mergedActivities()
        val main = activities.getValue("$pkg.MainActivity")
        assertEquals("true", main.androidAttr("exported"))
        assertEquals("singleTop", main.androidAttr("launchMode"))
        assertEquals("orientation|screenSize", main.androidAttr("configChanges"))
        assertEquals(1, main.getElementsByTagName("intent-filter").length)

        val detail = activities.getValue("$pkg.DetailActivity")
        assertEquals("false", detail.androidAttr("exported"), "secure default")
        assertEquals("$pkg.MainActivity", detail.androidAttr("parentActivityName"))

        if (kind.javaDir != null) {
            assertEquals("portrait", activities.getValue("$pkg.JavaActivity").androidAttr("screenOrientation"))
        }
    }

    @ParameterizedTest
    @EnumSource(ModuleKind::class)
    fun `migration moves activities onto annotations and the build still produces them`(kind: ModuleKind) {
        val project = FixtureProject(kind, "migrate")
        val pkg = kind.namespace
        project.file(
            kind.manifestPath,
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools">
                <application>
                    <activity
                        android:name=".MainActivity"
                        android:exported="true"
                        android:launchMode="singleTask"
                        android:windowSoftInputMode="stateHidden|adjustResize">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                    <activity android:name="$pkg.SettingsActivity" android:label="Settings" />
                    <activity android:name=".KeptActivity" tools:ignore="Instantiatable" />
                </application>
            </manifest>
            """.trimIndent(),
        )
        project.file(
            "${kind.kotlinDir}/${kind.packagePath}/MainActivity.kt",
            "package $pkg\n\nimport android.app.Activity\n\nclass MainActivity : Activity()\n",
        )
        project.file(
            "${kind.kotlinDir}/${kind.packagePath}/Screens.kt",
            "package $pkg\n\nclass SettingsActivity : android.app.Activity()\n\nclass KeptActivity : android.app.Activity()\n",
        )

        val migration = project.run("migrateManifestToAnnotations")
        assertTrue(migration.output.contains("Migrated 2 activities"), migration.output)
        assertTrue(migration.output.contains(".KeptActivity: unsupported attribute tools:ignore"), migration.output)

        val manifest = project.read(kind.manifestPath)
        assertFalse(manifest.contains("MainActivity"), manifest)
        assertFalse(manifest.contains("SettingsActivity"), manifest)
        assertTrue(manifest.contains(".KeptActivity"), manifest)

        val main = project.read("${kind.kotlinDir}/${kind.packagePath}/MainActivity.kt")
        assertTrue(main.contains("import dev.tilbrook.kandroid.typedmanifest.Manifest"), main)
        assertTrue(main.contains("launchMode = Manifest.LaunchMode.SINGLE_TASK"), main)
        assertTrue(main.contains("Manifest.WindowSoftInputMode.STATE_HIDDEN, Manifest.WindowSoftInputMode.ADJUST_RESIZE"), main)

        project.run(kind.assembleTask)

        val activities = project.mergedActivities()
        val merged = activities.getValue("$pkg.MainActivity")
        assertEquals("true", merged.androidAttr("exported"))
        assertEquals("singleTask", merged.androidAttr("launchMode"))
        assertEquals("stateHidden|adjustResize", merged.androidAttr("windowSoftInputMode"))
        assertEquals("Settings", activities.getValue("$pkg.SettingsActivity").androidAttr("label"))
        assertEquals("false", activities.getValue("$pkg.SettingsActivity").androidAttr("exported"))
        assertTrue(activities.containsKey("$pkg.KeptActivity"))

        // Second run is a no-op for the migrated entries.
        val again = project.run("migrateManifestToAnnotations")
        assertTrue(again.output.contains("Migrated 0 activities"), again.output)
    }

    @Test
    fun `a launcher that is not exported fails the build with a source location`() {
        val project = FixtureProject(ModuleKind.APPLICATION, "launcher-not-exported")
        project.baseManifest()
        project.file(
            "src/main/kotlin/com/example/app/MainActivity.kt",
            """
            package com.example.app

            import dev.tilbrook.kandroid.typedmanifest.Manifest

            @Manifest.Activity(
                intentFilters = [Manifest.IntentFilter(actions = [Manifest.Action.MAIN], categories = [Manifest.Category.LAUNCHER])],
            )
            class MainActivity : android.app.Activity()
            """.trimIndent(),
        )
        val result = project.runAndFail("generateDebugTypedManifest")
        assertTrue(result.output.contains("MainActivity.kt:5: MainActivity has a MAIN/LAUNCHER intent filter but is not exported"), result.output)
    }

    @Test
    fun `annotation dependency is compile only and can be turned off`() {
        val project = FixtureProject(ModuleKind.LIBRARY, "dependency")
        project.baseManifest()
        val coordinate = "dev.tilbrook:typed-manifest-annotations:${Versions.plugin}"

        val compile = project.run("dependencies", "--configuration", "debugCompileClasspath")
        assertTrue(compile.output.contains(coordinate), compile.output)
        val runtime = project.run("dependencies", "--configuration", "debugRuntimeClasspath")
        assertFalse(runtime.output.contains(coordinate), runtime.output)

        project.file(
            "build.gradle.kts",
            ModuleKind.LIBRARY.buildScript + "\n\ntypedManifest {\n    addAnnotationDependency = false\n}\n",
        )
        val off = project.run("dependencies", "--configuration", "debugCompileClasspath", "generateDebugTypedManifest")
        assertFalse(off.output.contains(coordinate), off.output)
        val generated = project.generatedManifest()
        assertTrue(generated.contains("<application />"), generated)
    }
}
