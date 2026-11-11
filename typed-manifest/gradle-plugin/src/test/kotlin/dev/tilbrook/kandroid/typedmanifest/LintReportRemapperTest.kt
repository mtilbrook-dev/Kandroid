package dev.tilbrook.kandroid.typedmanifest

import dev.tilbrook.kandroid.typedmanifest.internal.generate.SourceLocation
import dev.tilbrook.kandroid.typedmanifest.internal.json.Json
import dev.tilbrook.kandroid.typedmanifest.internal.remap.LintReportRemapper
import dev.tilbrook.kandroid.typedmanifest.internal.remap.SourceMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class LintReportRemapperTest {

    private val generated = "build/generated/manifests/generateDebugTypedManifest/AndroidManifest.xml"
    private val source = "src/main/kotlin/com/example/app/Activities.kt"
    private val map = SourceMap(
        generated,
        mapOf(
            5 to SourceLocation(source, 12),
            6 to SourceLocation(source, 12),
            8 to SourceLocation(source, 14),
        ),
    )
    private val sourceLines = mapOf(12 to "@Manifest.Activity(", 14 to "    enableOnBackInvokedCallback = true,")
    private fun remapper() = LintReportRemapper(listOf(map)) { path, line -> if (path == source) sourceLines[line] else null }

    @Test
    fun `xml report locations, snippet and fixes`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <issues format="6" by="lint 9.4.1">

                <issue
                    id="UnusedAttribute"
                    severity="Warning"
                    message="Attribute `enableOnBackInvokedCallback` is only used in API level 33 and higher"
                    errorLine1="            android:enableOnBackInvokedCallback=&quot;true&quot;"
                    errorLine2="            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~">
                    <fix-attribute
                        description="Set tools:targetApi"
                        attribute="targetApi"
                        value="33"/>
                    <location
                        file="/work/app/$generated"
                        line="8"
                        column="13"/>
                </issue>

                <issue
                    id="Other"
                    errorLine1="x"
                    errorLine2="^">
                    <location
                        file="/work/app/src/main/AndroidManifest.xml"
                        line="8"
                        column="13"/>
                </issue>

            </issues>
        """.trimIndent()
        val r = remapper()
        val out = r.remapXml(xml)
        assertEquals(1, r.remapped)
        assertTrue(out.contains("file=\"/work/app/$source\"\n            line=\"14\"/>"), out)
        assertTrue(out.contains("errorLine1=\"    enableOnBackInvokedCallback = true,\""), out)
        assertTrue(out.contains("errorLine2=\"    ^\""), out)
        assertFalse(out.contains("fix-attribute"), out)
        // Unrelated issue untouched.
        assertTrue(out.contains("file=\"/work/app/src/main/AndroidManifest.xml\"\n            line=\"8\"\n            column=\"13\"/>"), out)
    }

    @Test
    fun `sarif physical locations and fixes`() {
        val sarif = """
            {
              "version": "2.1.0",
              "runs": [
                {
                  "results": [
                    {
                      "ruleId": "UnusedAttribute",
                      "locations": [
                        {
                          "physicalLocation": {
                            "artifactLocation": { "uriBaseId": "%SRCROOT%", "uri": "app/$generated" },
                            "region": { "startLine": 8, "startColumn": 13, "endLine": 8, "endColumn": 55, "charOffset": 300, "snippet": { "text": "android:enableOnBackInvokedCallback" } },
                            "contextRegion": { "startLine": 6, "endLine": 10 }
                          }
                        }
                      ],
                      "fixes": [
                        { "description": { "text": "Set targetApi" }, "artifactChanges": [ { "artifactLocation": { "uri": "app/$generated" } } ] }
                      ]
                    },
                    {
                      "ruleId": "Untouched",
                      "locations": [ { "physicalLocation": { "artifactLocation": { "uri": "app/src/main/AndroidManifest.xml" }, "region": { "startLine": 3 } } } ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val r = remapper()
        val out = r.remapSarif(sarif)
        assertEquals(1, r.remapped)
        @Suppress("UNCHECKED_CAST")
        val results = ((Json.parse(out) as Map<String, Any?>)["runs"] as List<Map<String, Any?>>)[0]["results"] as List<Map<String, Any?>>
        val physical = Json.write(results[0]["locations"])
        assertTrue(physical.contains("\"uri\": \"app/$source\""), physical)
        assertTrue(physical.contains("\"startLine\": 14"), physical)
        assertFalse(physical.contains("startColumn"), physical)
        assertFalse(physical.contains("contextRegion"), physical)
        assertTrue(physical.contains("enableOnBackInvokedCallback = true,"), physical)
        assertEquals(emptyList<Any>(), results[0]["fixes"])
        assertTrue(Json.write(results[1]).contains("app/src/main/AndroidManifest.xml"))
    }

    @Test
    fun `text report header and snippet`() {
        val text = """
            /work/app/$generated:8: Warning: Attribute enableOnBackInvokedCallback is only used in API level 33 and higher [UnusedAttribute]
                        android:enableOnBackInvokedCallback="true"
                        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
            /work/app/src/main/AndroidManifest.xml:3: Warning: Something else [Other]
                <application>
                ~~~~~~~~~~~~~

            0 errors, 2 warnings
        """.trimIndent()
        val r = remapper()
        val out = r.remapText(text)
        assertEquals(1, r.remapped)
        assertTrue(out.startsWith("/work/app/$source:14: Warning: Attribute enableOnBackInvokedCallback"), out)
        assertTrue(out.contains("\n    enableOnBackInvokedCallback = true,\n    ^\n"), out)
        assertTrue(out.contains("/work/app/src/main/AndroidManifest.xml:3: Warning: Something else"), out)
    }

    @Test
    fun `nearest mapped line is used and source map round trips`() {
        val r = remapper()
        val out = r.remapText("/w/$generated:7: Error: x [Y]")
        assertEquals("/w/$source:12: Error: x [Y]", out)

        val file = Files.createTempFile("map", ".json").toFile()
        file.writeText(map.toJson())
        assertEquals(map, SourceMap.read(file))
    }

    @Test
    fun `windows paths`() {
        val r = remapper()
        val out = r.remapText("C:\\w\\app\\${generated.replace('/', '\\')}:5: Error: x [Y]")
        assertEquals("C:\\w\\app\\${source.replace('/', '\\')}:12: Error: x [Y]", out)
    }

    @Test
    fun `json round trip keeps order, escapes and numbers`() {
        val text = """{"a":1.50,"b":[true,false,null],"c":{"s":"q\"\\\n\u00e9"}}"""
        val parsed = Json.parse(text)
        assertEquals(parsed, Json.parse(Json.write(parsed)))
        assertTrue(Json.write(parsed).contains("1.50"))
    }
}
