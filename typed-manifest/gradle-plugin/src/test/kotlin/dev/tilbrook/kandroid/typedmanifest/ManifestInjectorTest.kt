package dev.tilbrook.kandroid.typedmanifest

import dev.tilbrook.kandroid.typedmanifest.internal.generate.ManifestInjector
import dev.tilbrook.kandroid.typedmanifest.internal.model.ManifestElement
import dev.tilbrook.kandroid.typedmanifest.internal.model.TypedManifestException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManifestInjectorTest {

    private val entry = ManifestElement("activity", listOf("name" to "com.example.A", "exported" to "false"))
    private val header = "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"com.example\">\n"

    @Test
    fun `inserts before closing application tag`() {
        val merged = header + "    <application>\n        <service android:name=\"S\" />\n    </application>\n</manifest>\n"
        assertEquals(
            header + "    <application>\n        <service android:name=\"S\" />\n" +
                "        <activity\n            android:name=\"com.example.A\"\n            android:exported=\"false\" />\n" +
                "    </application>\n</manifest>\n",
            ManifestInjector.inject(merged, listOf(entry)),
        )
    }

    @Test
    fun `expands self closing application and creates missing one`() {
        val selfClosing = ManifestInjector.inject(header + "    <application android:label=\"x\" />\n</manifest>\n", listOf(entry))
        assertTrue(selfClosing.contains("<application android:label=\"x\">\n        <activity"), selfClosing)
        assertTrue(selfClosing.contains("    </application>\n</manifest>"), selfClosing)

        val none = ManifestInjector.inject(header + "    <uses-sdk android:minSdkVersion=\"24\" />\n</manifest>\n", listOf(entry))
        assertTrue(none.contains("    <application>\n        <activity"), none)
    }

    @Test
    fun `refuses duplicates`() {
        val merged = header + "    <application>\n        <activity android:name=\"com.example.A\" />\n    </application>\n</manifest>\n"
        val e = assertThrows(TypedManifestException::class.java) { ManifestInjector.inject(merged, listOf(entry)) }
        assertTrue(e.message!!.contains("declared both"), e.message)
    }
}
