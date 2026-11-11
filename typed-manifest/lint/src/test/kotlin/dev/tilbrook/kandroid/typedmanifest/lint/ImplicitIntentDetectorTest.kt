package dev.tilbrook.kandroid.typedmanifest.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

class ImplicitIntentDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = ImplicitIntentDetector()

    override fun getIssues(): List<Issue> = listOf(ImplicitIntentDetector.ISSUE)

    private fun hidden(exported: String = "false") = """
        <activity android:name=".HiddenActivity" android:exported="$exported">
            <intent-filter>
                <action android:name="test.pkg.OPEN" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>
    """

    private fun run(manifest: TestFile, vararg files: TestFile) =
        lint().files(manifest, manifestAnnotations, *files).testModes(TestMode.DEFAULT).run()

    fun testImplicitIntentToHiddenActivityIsFlagged() {
        run(
            appManifest(activities = hidden()),
            kotlin(
                """
                package test.pkg

                import android.content.Context
                import android.content.Intent

                fun open(context: Context) {
                    context.startActivity(Intent("test.pkg.OPEN"))
                }
                """,
            ).indented(),
        )
            .expectWarningCount(1)
            .expectContains("ImplicitIntentToUnexportedActivity")
            .expectContains("HiddenActivity")
    }

    fun testJavaSetActionWithConstantIsFlagged() {
        run(
            appManifest(activities = hidden()),
            java(
                """
                package test.pkg;

                import android.content.Context;
                import android.content.Intent;

                class Opener {
                    static final String OPEN = "test.pkg.OPEN";

                    void open(Context context) {
                        Intent intent = new Intent();
                        intent.setAction(OPEN);
                        context.startActivity(intent);
                    }
                }
                """,
            ).indented(),
        ).expectWarningCount(1)
    }

    fun testSameFileAnnotationAndKotlinPropertyAssignment() {
        // The activity only exists as an annotation in this file (no manifest entry yet).
        run(
            appManifest(),
            kotlin(
                """
                package test.pkg

                import android.content.Context
                import android.content.Intent
                import dev.tilbrook.kandroid.typedmanifest.Manifest

                @Manifest.Activity(intentFilters = [Manifest.IntentFilter(actions = ["test.pkg.OPEN"])])
                class HiddenActivity

                fun open(context: Context) {
                    context.startActivity(Intent().apply { action = "test.pkg.OPEN" })
                }
                """,
            ).indented(),
        )
            .expectWarningCount(1)
            .expectContains("HiddenActivity")
    }

    fun testExplicitExportedOrOldTargetIsClean() {
        val explicit = kotlin(
            """
            package test.pkg

            import android.content.Context
            import android.content.Intent

            fun open(context: Context) {
                context.startActivity(Intent("test.pkg.OPEN").setPackage(context.packageName))
            }
            """,
        ).indented()
        run(appManifest(activities = hidden()), explicit).expectClean()

        val implicit = kotlin(
            """
            package test.pkg

            import android.content.Context
            import android.content.Intent

            fun open(context: Context) {
                context.startActivity(Intent("test.pkg.OPEN"))
            }
            """,
        ).indented()
        run(appManifest(activities = hidden(exported = "true")), implicit).expectClean()
        run(appManifest(targetSdk = 33, activities = hidden()), implicit).expectClean()
    }
}
