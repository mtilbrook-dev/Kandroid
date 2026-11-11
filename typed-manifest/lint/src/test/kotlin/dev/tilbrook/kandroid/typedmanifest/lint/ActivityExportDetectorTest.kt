package dev.tilbrook.kandroid.typedmanifest.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

class ActivityExportDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = ActivityExportDetector()

    override fun getIssues(): List<Issue> = listOf(
        ActivityExportDetector.REQUIRES_EXPORTED,
        ActivityExportDetector.EXPORTED_WITHOUT_REQUIREMENT,
    )

    private fun check(vararg files: com.android.tools.lint.checks.infrastructure.TestFile) =
        lint().files(appManifest(), manifestAnnotations, *files).testModes(TestMode.DEFAULT, TestMode.PARTIAL).run()

    fun testBrowsableFilterWithoutExportedIsFlagged() {
        check(
            kotlin(
                """
                package test.pkg

                import dev.tilbrook.kandroid.typedmanifest.Manifest

                @Manifest.Activity(
                    intentFilters = [
                        Manifest.IntentFilter(
                            actions = [Manifest.Action.VIEW],
                            categories = [Manifest.Category.DEFAULT, Manifest.Category.BROWSABLE],
                        ),
                    ],
                )
                class DeepLinkActivity
                """,
            ).indented(),
        )
            .expectWarningCount(1)
            .expectContains("IntentFilterRequiresExported")
            .expectContains("BROWSABLE")
    }

    fun testLauncherExplicitlyNotExportedIsFlaggedOnTheValue() {
        check(
            kotlin(
                """
                package test.pkg

                import dev.tilbrook.kandroid.typedmanifest.Manifest

                @Manifest.Activity(
                    exported = false,
                    intentFilters = [Manifest.IntentFilter(actions = [Manifest.Action.MAIN], categories = [Manifest.Category.LAUNCHER])],
                )
                class MainActivity
                """,
            ).indented(),
        )
            .expectWarningCount(1)
            .expectContains("MainActivity must be exported because it is a launcher entry point")
    }

    fun testJavaAutoVerifyIsFlagged() {
        check(
            java(
                """
                package test.pkg;

                import dev.tilbrook.kandroid.typedmanifest.Manifest;

                @Manifest.Activity(
                    intentFilters = @Manifest.IntentFilter(
                        actions = Manifest.Action.VIEW,
                        categories = {Manifest.Category.DEFAULT},
                        autoVerify = true
                    )
                )
                public class AppLinkActivity {}
                """,
            ).indented(),
        )
            .expectWarningCount(1)
            .expectContains("verified App Links")
    }

    fun testExportedShareTargetAndInternalFiltersAreClean() {
        check(
            kotlin(
                """
                package test.pkg

                import dev.tilbrook.kandroid.typedmanifest.Manifest

                @Manifest.Activity(
                    exported = true,
                    intentFilters = [Manifest.IntentFilter(actions = [Manifest.Action.SEND], categories = [Manifest.Category.DEFAULT])],
                )
                class ShareActivity

                @Manifest.Activity(intentFilters = [Manifest.IntentFilter(actions = ["test.pkg.INTERNAL"])])
                class InternalActivity

                @Manifest.Activity
                class PlainActivity
                """,
            ).indented(),
        ).expectClean()
    }

    fun testExportedWithoutFilterOrPermissionIsFlagged() {
        check(
            kotlin(
                """
                package test.pkg

                import dev.tilbrook.kandroid.typedmanifest.Manifest

                @Manifest.Activity(exported = true)
                class OpenActivity

                @Manifest.Activity(exported = true, permission = "test.pkg.permission.PARTNER")
                class PartnerActivity
                """,
            ).indented(),
        )
            .expectWarningCount(1)
            .expectContains("ExportedWithoutRequirement")
            .expectContains("OpenActivity is exported but has no intent filter")
    }

    fun testManifestXml() {
        lint().files(
            appManifest(
                activities = """
                    <activity android:name=".Browsable" android:exported="false">
                        <intent-filter>
                            <action android:name="android.intent.action.VIEW" />
                            <category android:name="android.intent.category.BROWSABLE" />
                        </intent-filter>
                    </activity>
                    <activity android:name=".Open" android:exported="true" />
                    <activity android:name=".Partner" android:exported="true" android:permission="test.pkg.P" />
                    <activity android:name=".Launcher" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                """,
            ),
        ).testModes(TestMode.DEFAULT, TestMode.PARTIAL).run()
            .expectWarningCount(2)
            .expectContains("IntentFilterRequiresExported")
            .expectContains("ExportedWithoutRequirement")
            .expectContains("Browsable must be exported")
            .expectContains("Open is exported but has no intent filter")
    }
}
