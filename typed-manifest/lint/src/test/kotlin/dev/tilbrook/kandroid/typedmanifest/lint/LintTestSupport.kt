package dev.tilbrook.kandroid.typedmanifest.lint

import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestFiles.manifest
import java.io.File

/** The real `@Manifest` annotation source, so tests track the published API. */
internal val manifestAnnotations: TestFile by lazy {
    val path = System.getProperty("typedManifest.annotationsSource")
        ?: error("typedManifest.annotationsSource not set; run tests through Gradle")
    kotlin(File(path).readText()).indented()
}

internal fun appManifest(targetSdk: Int = 34, activities: String = ""): TestFile = manifest(
    """
    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="test.pkg">
        <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="$targetSdk" />
        <application>
    $activities
        </application>
    </manifest>
    """,
).indented()

