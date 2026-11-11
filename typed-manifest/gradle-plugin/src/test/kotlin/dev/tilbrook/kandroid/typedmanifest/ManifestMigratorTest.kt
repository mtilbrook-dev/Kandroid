package dev.tilbrook.kandroid.typedmanifest

import dev.tilbrook.kandroid.typedmanifest.internal.generate.ManifestGenerator
import dev.tilbrook.kandroid.typedmanifest.internal.generate.SourceInput
import dev.tilbrook.kandroid.typedmanifest.internal.migrate.ManifestActivityReader
import dev.tilbrook.kandroid.typedmanifest.internal.migrate.ManifestMigrator
import dev.tilbrook.kandroid.typedmanifest.internal.migrate.MigrationResult
import dev.tilbrook.kandroid.typedmanifest.internal.model.ManifestElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class ManifestMigratorTest {

    private val root: File = Files.createTempDirectory("typed-manifest-migrate").toFile()
    private val src = File(root, "src/main/kotlin")

    private fun write(path: String, text: String): File =
        File(src, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun migrate(manifest: String, namespace: String? = "com.example"): MigrationResult =
        ManifestMigrator(listOf(src), namespace).migrate(manifest)

    /** Generates from the migrated sources and compares with the original manifest's activities. */
    private fun assertRoundTrip(originalManifest: String, result: MigrationResult, namespace: String = "com.example") {
        val sources = result.sourceChanges.map { (f, t) -> SourceInput(f.path, t) }.asSequence()
        val generated = ManifestGenerator.collect(sources).associateBy { it.attr("name")!! }
        val original = ManifestActivityReader.read(originalManifest).activities
            .filter { it.element != null && result.migrated.contains(resolve(it.rawName!!, namespace)) }
            .map { it.element!! }
        assertEquals(original.size, generated.size, "generated: ${generated.keys}")
        for (element in original) {
            val name = resolve(element.attr("name")!!, namespace)
            val expected = normalise(element.copy(attributes = element.attributes.map {
                if (it.first == "name") "name" to name else it
            }))
            assertEquals(expected, normalise(generated.getValue(name)), "round trip of $name")
        }
    }

    private fun resolve(raw: String, ns: String) = if (raw.startsWith(".")) ns + raw else if ('.' !in raw) "$ns.$raw" else raw

    /** Attribute order and an explicit exported="false" don't matter semantically. */
    private fun normalise(e: ManifestElement): ManifestElement {
        val attrs = e.attributes.toMutableList()
        if (e.tag == "activity" && attrs.none { it.first == "exported" }) attrs += "exported" to "false"
        return ManifestElement(e.tag, attrs.sortedBy { it.first }, e.children.map { normalise(it) })
    }

    private val manifest = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:tools="http://schemas.android.com/tools">

            <application android:label="App">
                <!-- <activity android:name=".Commented" /> -->
                <activity
                    android:name=".MainActivity"
                    android:exported="true"
                    android:launchMode="singleTask"
                    android:configChanges="orientation|keyboardHidden"
                    android:theme="@style/Theme.${'$'}Thing">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                    <meta-data android:name="a" android:value="b" />
                </activity>
                <activity android:name="com.example.settings.SettingsActivity" android:maxAspectRatio="2.1" />
                <activity android:name=".JavaActivity" android:exported="false" android:parentActivityName=".MainActivity"/>
                <activity android:name=".Outer${'$'}Inner" />
                <activity android:name=".ToolsActivity" tools:ignore="LockedOrientationActivity" />
                <activity android:name=".Missing" />
                <activity android:name=".ResourceBool" android:noHistory="@bool/no_history" />
                <service android:name=".SomeService" />
            </application>
        </manifest>
    """.trimIndent()

    private fun writeSources() {
        write(
            "com/example/MainActivity.kt",
            """
            package com.example

            import android.app.Activity

            @Suppress("unused")
            class MainActivity : Activity()
            """.trimIndent(),
        )
        // File name doesn't match class name; found by scanning.
        write(
            "com/example/settings/Screens.kt",
            """
            package com.example.settings

            import android.Manifest
            import android.app.Activity

            class SettingsActivity : Activity() {
                val permission = Manifest.permission.CAMERA
            }
            """.trimIndent(),
        )
        write(
            "com/example/JavaActivity.java",
            """
            package com.example;

            public class JavaActivity extends android.app.Activity {
            }
            """.trimIndent(),
        )
        write(
            "com/example/Outer.kt",
            """
            package com.example

            class Outer {
                class Inner : android.app.Activity()
            }
            """.trimIndent(),
        )
        write("com/example/ToolsActivity.kt", "package com.example\n\nclass ToolsActivity\n")
        write("com/example/ResourceBool.kt", "package com.example\n\nclass ResourceBool\n")
    }

    @Test
    fun `migrates supported activities and leaves the rest`() {
        writeSources()
        val result = migrate(manifest)

        assertEquals(
            listOf(
                "com.example.MainActivity",
                "com.example.settings.SettingsActivity",
                "com.example.JavaActivity",
                "com.example.Outer\$Inner",
            ),
            result.migrated,
        )
        val skipped = result.skipped.toMap()
        assertTrue(skipped.getValue(".ToolsActivity").contains("tools:ignore"), skipped.toString())
        assertTrue(skipped.getValue(".Missing").contains("not found"), skipped.toString())
        assertTrue(skipped.getValue(".ResourceBool").contains("not a literal boolean"), skipped.toString())

        val expectedManifest = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools">

                <application android:label="App">
                    <!-- <activity android:name=".Commented" /> -->
                    <activity android:name=".ToolsActivity" tools:ignore="LockedOrientationActivity" />
                    <activity android:name=".Missing" />
                    <activity android:name=".ResourceBool" android:noHistory="@bool/no_history" />
                    <service android:name=".SomeService" />
                </application>
            </manifest>
        """.trimIndent()
        assertEquals(expectedManifest, result.manifestText)

        val main = result.sourceChanges.getValue(File(src, "com/example/MainActivity.kt"))
        assertEquals(
            """
            package com.example

            import android.app.Activity
            import dev.tilbrook.kandroid.typedmanifest.Manifest

            @Suppress("unused")
            @Manifest.Activity(
                exported = true,
                theme = "@style/Theme.\${'$'}Thing",
                launchMode = Manifest.LaunchMode.SINGLE_TASK,
                configChanges = [Manifest.ConfigChange.ORIENTATION, Manifest.ConfigChange.KEYBOARD_HIDDEN],
                intentFilters = [
                    Manifest.IntentFilter(
                        actions = [Manifest.Action.MAIN],
                        categories = [Manifest.Category.LAUNCHER],
                    ),
                ],
                metaData = [
                    Manifest.MetaData(name = "a", value = "b"),
                ],
            )
            class MainActivity : Activity()
            """.trimIndent(),
            main,
        )

        // android.Manifest is imported, so the annotation is fully qualified and no import is added.
        val settings = result.sourceChanges.getValue(File(src, "com/example/settings/Screens.kt"))
        assertTrue(
            settings.contains("@dev.tilbrook.kandroid.typedmanifest.Manifest.Activity(\n    maxAspectRatio = 2.1f,\n)\nclass SettingsActivity"),
            settings,
        )
        assertFalse(settings.contains("import dev.tilbrook"), settings)

        val java = result.sourceChanges.getValue(File(src, "com/example/JavaActivity.java"))
        assertEquals(
            """
            package com.example;

            import dev.tilbrook.kandroid.typedmanifest.Manifest;

            @Manifest.Activity(
                parentActivityName = ".MainActivity"
            )
            public class JavaActivity extends android.app.Activity {
            }
            """.trimIndent(),
            java,
        )

        val outer = result.sourceChanges.getValue(File(src, "com/example/Outer.kt"))
        assertTrue(outer.contains("    @Manifest.Activity\n    class Inner"), outer)

        assertRoundTrip(manifest, result)
    }

    @Test
    fun `implicit export is preserved with a note`() {
        write("com/example/Legacy.kt", "package com.example\n\nclass Legacy\n")
        val legacy = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example">
                <application>
                    <activity android:name="Legacy">
                        <intent-filter>
                            <action android:name="com.example.OPEN" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent()
        val result = migrate(legacy, namespace = null)
        assertEquals(listOf("com.example.Legacy"), result.migrated)
        assertTrue(result.notes.single().contains("exported = true"), result.notes.toString())
        val text = result.sourceChanges.values.single()
        assertTrue(text.contains("exported = true"), text)
        assertTrue(text.contains("actions = [\"com.example.OPEN\"]"), text)
    }

    @Test
    fun `already annotated classes are skipped`() {
        write(
            "com/example/Done.kt",
            "package com.example\nimport dev.tilbrook.kandroid.typedmanifest.Manifest\n@Manifest.Activity\nclass Done\n",
        )
        val result = migrate(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application><activity android:name=".Done" /></application>
            </manifest>
            """.trimIndent(),
        )
        assertTrue(result.migrated.isEmpty())
        assertTrue(result.skipped.single().second.contains("already annotated"))
        assertFalse(result.manifestChanged)
    }
}
