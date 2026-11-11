package dev.tilbrook.kandroid.typedmanifest.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

class TypedManifestIssueRegistry : IssueRegistry() {
    override val issues: List<Issue> = listOf(
        ActivityExportDetector.REQUIRES_EXPORTED,
        ActivityExportDetector.EXPORTED_WITHOUT_REQUIREMENT,
        ImplicitIntentDetector.ISSUE,
    )

    override val api: Int = CURRENT_API

    override val vendor: Vendor = Vendor(
        vendorName = "Kandroid typed-manifest",
        identifier = "dev.tilbrook:typed-manifest-lint",
        feedbackUrl = "https://github.com/mtilbrook-dev/Kandroid/issues",
    )
}
