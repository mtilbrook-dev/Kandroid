package dev.tilbrook.kandroid.typedmanifest.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

class AnnotatedManifestChecksDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = AnnotatedManifestChecksDetector()

    override fun getIssues(): List<Issue> = listOf(
        AnnotatedManifestChecksDetector.APP_LINK_URL,
        AnnotatedManifestChecksDetector.INSTANTIATABLE,
        AnnotatedManifestChecksDetector.FIXED_ORIENTATION,
    )

    private fun check(vararg files: TestFile) =
        lint().files(appManifest(), manifestAnnotations, *files).testModes(TestMode.DEFAULT, TestMode.PARTIAL).run()

    private fun activity(annotation: String, name: String = "LinkActivity") = kotlin(
        """
        package test.pkg

        import android.app.Activity
        import dev.tilbrook.kandroid.typedmanifest.Manifest

        $annotation
        class $name : Activity()
        """,
    ).indented()

    fun testBrowsableViewWithoutData() {
        check(
            activity(
                """
                @Manifest.Activity(
                    exported = true,
                    intentFilters = [Manifest.IntentFilter(actions = [Manifest.Action.VIEW], categories = [Manifest.Category.BROWSABLE])],
                )
                """,
            ),
        )
            .expectErrorCount(1)
            .expectContains("TypedManifestAppLinkUrl")
            .expectContains("has no Manifest.Data")
    }

    fun testInvalidDataParts() {
        check(
            activity(
                """
                @Manifest.Activity(
                    exported = true,
                    intentFilters = [
                        Manifest.IntentFilter(
                            actions = [Manifest.Action.VIEW],
                            categories = [Manifest.Category.DEFAULT, Manifest.Category.BROWSABLE],
                            data = [
                                Manifest.Data(scheme = "HTTPS://", host = "Example.com"),
                                Manifest.Data(pathPrefix = "docs", port = "eighty"),
                            ],
                        ),
                        Manifest.IntentFilter(actions = [Manifest.Action.VIEW], data = [Manifest.Data(host = "a.com")]),
                    ],
                )
                """,
            ),
        )
            .expectContains("Scheme HTTPS:// must be lowercase")
            .expectContains("Host Example.com must be lowercase")
            .expectContains("pathPrefix docs must start with /")
            .expectContains("Port eighty must be a number")
            .expectContains("ignored without a scheme")
            .expectErrorCount(5)
    }

    fun testValidLinksAreClean() {
        check(
            activity(
                """
                @Manifest.Activity(
                    exported = true,
                    intentFilters = [
                        Manifest.IntentFilter(
                            actions = [Manifest.Action.VIEW],
                            categories = [Manifest.Category.DEFAULT, Manifest.Category.BROWSABLE],
                            data = [Manifest.Data(scheme = "https", host = "*.example.com", pathPrefix = "/docs")],
                            autoVerify = true,
                        ),
                    ],
                )
                """,
            ),
        ).expectClean()
    }

    fun testWebViewFilterWithoutBrowsableAndBadAutoVerify() {
        check(
            activity(
                """
                @Manifest.Activity(
                    exported = true,
                    intentFilters = [
                        Manifest.IntentFilter(actions = [Manifest.Action.VIEW], data = [Manifest.Data(scheme = "https", host = "a.com")]),
                        Manifest.IntentFilter(
                            actions = [Manifest.Action.VIEW],
                            categories = [Manifest.Category.BROWSABLE],
                            data = [Manifest.Data(scheme = "myapp", host = "open")],
                            autoVerify = true,
                        ),
                    ],
                )
                """,
            ),
        )
            .expectContains("missing Manifest.Category.BROWSABLE")
            .expectContains("autoVerify = true needs an http/https scheme")
            .expectErrorCount(2)
    }

    fun testInstantiatable() {
        check(
            kotlin(
                """
                package test.pkg

                import android.app.Activity
                import dev.tilbrook.kandroid.typedmanifest.Manifest

                @Manifest.Activity abstract class AbstractActivity : Activity()

                @Manifest.Activity class NeedsArg(val id: String) : Activity()

                @Manifest.Activity class Defaults(val id: String = "x") : Activity()

                @Manifest.Activity class NotAnActivity

                class Outer {
                    @Manifest.Activity inner class InnerActivity : Activity()
                    @Manifest.Activity class NestedActivity : Activity()
                }
                """,
            ).indented(),
        )
            .expectContains("AbstractActivity is abstract")
            .expectContains("NeedsArg has no public no-argument constructor")
            .expectContains("NotAnActivity doesn't extend android.app.Activity")
            .expectContains("InnerActivity is an inner class")
            .expectErrorCount(4)
    }

    fun testFixedOrientation() {
        check(
            kotlin(
                """
                package test.pkg

                import android.app.Activity
                import dev.tilbrook.kandroid.typedmanifest.Manifest

                @Manifest.Activity(screenOrientation = Manifest.ScreenOrientation.PORTRAIT)
                class Portrait : Activity()

                @Manifest.Activity(resizeableActivity = false)
                class Fixed : Activity()

                @Manifest.Activity(screenOrientation = Manifest.ScreenOrientation.UNSPECIFIED, resizeableActivity = true)
                class Flexible : Activity()
                """,
            ).indented(),
            java(
                """
                package test.pkg;

                import dev.tilbrook.kandroid.typedmanifest.Manifest;

                @Manifest.Activity(screenOrientation = Manifest.ScreenOrientation.SENSOR_LANDSCAPE)
                public class JavaLandscape extends android.app.Activity {}
                """,
            ).indented(),
        )
            .expectContains("Fixed screenOrientation (PORTRAIT)")
            .expectContains("Fixed screenOrientation (SENSOR_LANDSCAPE)")
            .expectContains("resizeableActivity = false letterboxes")
            .expectWarningCount(3)
    }
}
