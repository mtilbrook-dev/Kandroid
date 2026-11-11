package dev.tilbrook.kandroid.typedmanifest.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.LintFix
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.detector.api.XmlContext
import com.android.tools.lint.detector.api.XmlScanner
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement
import org.w3c.dom.Element
import java.util.EnumSet

/**
 * Checks `android:exported` against what the activity's intent filters need, for both
 * `@Manifest.Activity` and `<activity>` in AndroidManifest.xml:
 *
 * - [REQUIRES_EXPORTED]: a filter that only works when other apps or the system start the
 *   activity (launcher, browsable links, share targets, ...) but `exported` isn't `true`.
 * - [EXPORTED_WITHOUT_REQUIREMENT]: `exported = true` with no intent filter and no `permission`,
 *   so any app can start it by component name.
 */
class ActivityExportDetector : Detector(), SourceCodeScanner, XmlScanner {

    // ---- @Manifest.Activity ----

    override fun getApplicableUastTypes(): List<Class<out UElement>> = listOf(UClass::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler = object : UElementHandler() {
        override fun visitClass(node: UClass) {
            val activity = AnnotationReader.read(node) ?: return
            val info = activity.info
            val annotationLocation = context.getLocation(activity.annotation as UElement)
            val exportedLocation = activity.exportedValue?.let { context.getLocation(it) }

            val reason = ExportRules.reason(info)
            if (reason != null && info.exported != true) {
                val fix = if (activity.exportedValue != null) {
                    replaceExported(exportedLocation!!, "true")
                } else {
                    LintFix.create().name("Set exported = true")
                        .replace().range(annotationLocation).text("(").with("(exported = true, ").reformat(true)
                        .build()
                }
                context.report(
                    REQUIRES_EXPORTED, activity.annotation, exportedLocation ?: annotationLocation,
                    requiresMessage(simpleName(info.name), reason, "exported = true"), fix,
                )
            } else if (needsNoExport(info)) {
                context.report(
                    EXPORTED_WITHOUT_REQUIREMENT, activity.annotation, exportedLocation ?: annotationLocation,
                    unneededMessage(simpleName(info.name), "exported = false", "permission = \"…\""),
                    exportedLocation?.let { replaceExported(it, "false") },
                )
            }
        }
    }

    private fun replaceExported(location: Location, value: String): LintFix =
        LintFix.create().name("Set exported = $value").replace().range(location).all().with(value).build()

    // ---- AndroidManifest.xml ----

    override fun getApplicableElements(): Collection<String> = listOf("activity")

    override fun visitElement(context: XmlContext, element: Element) {
        // AGP hands lint the overlay generated from @Manifest.Activity too; those entries are
        // already checked (with fixes) on the annotation itself.
        if (context.getContents()?.contains(GENERATED_MARKER) == true) return
        val info = ActivityInfo(
            name = element.getAttributeNS(ANDROID_NS, "name"),
            exported = ManifestActivities.exported(element),
            filters = ManifestActivities.filters(element),
            permission = element.getAttributeNS(ANDROID_NS, "permission").ifEmpty { null },
        )
        val exportedAttr = element.getAttributeNodeNS(ANDROID_NS, "exported")
        val location = if (exportedAttr != null) context.getLocation(exportedAttr) else context.getNameLocation(element)

        val reason = ExportRules.reason(info)
        if (reason != null && info.exported != true) {
            context.report(
                REQUIRES_EXPORTED, element, location,
                requiresMessage(simpleName(info.name), reason, "android:exported=\"true\""),
                LintFix.create().name("Set android:exported=\"true\"").set(ANDROID_NS, "exported", "true").build(),
            )
        } else if (needsNoExport(info)) {
            context.report(
                EXPORTED_WITHOUT_REQUIREMENT, element, location,
                unneededMessage(simpleName(info.name), "android:exported=\"false\"", "android:permission"),
                LintFix.create().name("Set android:exported=\"false\"").set(ANDROID_NS, "exported", "false").build(),
            )
        }
    }

    private fun needsNoExport(info: ActivityInfo) =
        info.exported == true && info.filters.isEmpty() && info.permission == null

    private fun simpleName(name: String) = name.substringAfterLast('.').ifEmpty { name }

    private fun requiresMessage(name: String, reason: String, fix: String) =
        "`$name` must be exported because $reason. Set `$fix`, otherwise other apps and the system can't start it."

    private fun unneededMessage(name: String, fix: String, permission: String) =
        "`$name` is exported but has no intent filter, so any app can start it by class name. " +
            "Set `$fix` unless another app must launch it explicitly; then guard it with `$permission`."

    companion object {
        /** Written at the top of manifests generated by the typed-manifest Gradle plugin. */
        private const val GENERATED_MARKER = "Generated by dev.tilbrook.typed-manifest"

        private val IMPLEMENTATION = Implementation(
            ActivityExportDetector::class.java,
            EnumSet.of(Scope.JAVA_FILE, Scope.MANIFEST),
            Scope.JAVA_FILE_SCOPE,
            Scope.MANIFEST_SCOPE,
        )

        @JvmField
        val REQUIRES_EXPORTED: Issue = Issue.create(
            id = "IntentFilterRequiresExported",
            briefDescription = "Intent filter needs an exported activity",
            explanation = """
                Some intent filters are only ever matched by intents sent from other apps or the \
                system: launcher entries, `BROWSABLE` links, verified App Links (`autoVerify`), share \
                targets (`SEND`), settings and widget-configuration entry points. If the activity \
                isn't exported, those intents can't reach it, so the filter does nothing.

                `@Manifest.Activity` defaults to `exported = false` for safety; set \
                `exported = true` on activities that really are entry points.
                """,
            category = Category.CORRECTNESS,
            priority = 6,
            severity = Severity.WARNING,
            implementation = IMPLEMENTATION,
        )

        @JvmField
        val EXPORTED_WITHOUT_REQUIREMENT: Issue = Issue.create(
            id = "ExportedWithoutRequirement",
            briefDescription = "Activity exported without a reason",
            explanation = """
                An exported activity with no intent filter can still be started by any app that \
                knows its class name. Unless another app (a companion app, an SDK partner) or a \
                system component has to launch it explicitly, keep it unexported.

                If it does need to be reachable, protect it with a `permission` — usually a \
                `signature` level permission — and this check stays quiet. Intents sent from your \
                own app work without exporting as long as they are explicit.
                """,
            category = Category.SECURITY,
            priority = 5,
            severity = Severity.WARNING,
            implementation = IMPLEMENTATION,
        )
    }
}
