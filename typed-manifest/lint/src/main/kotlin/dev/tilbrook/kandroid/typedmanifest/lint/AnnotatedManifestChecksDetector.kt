package dev.tilbrook.kandroid.typedmanifest.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement

/**
 * Built-in manifest checks, re-implemented on `@Manifest.Activity` so findings point at the
 * annotation instead of the generated manifest. The generated manifest opts out of the built-in
 * versions with `tools:ignore` (see the Gradle plugin's `REPLACED_LINT_CHECKS`).
 *
 * | Here | Replaces |
 * | --- | --- |
 * | [APP_LINK_URL] | `AppLinkUrlError` |
 * | [INSTANTIATABLE] | `Instantiatable` |
 * | [FIXED_ORIENTATION] | `DiscouragedApi` (orientation/resizeability) and `LockedOrientationActivity` |
 */
class AnnotatedManifestChecksDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes(): List<Class<out UElement>> = listOf(UClass::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler = object : UElementHandler() {
        override fun visitClass(node: UClass) {
            val activity = AnnotationReader.read(node) ?: return
            checkAppLinks(context, activity)
            checkInstantiatable(context, node, activity)
            checkOrientation(context, activity)
        }
    }

    // ---- AppLinkUrlError ----

    private fun checkAppLinks(context: JavaContext, activity: AnnotatedActivity) {
        for (filter in activity.info.filters) {
            val where = filter.element ?: activity.annotation.sourcePsi
            fun report(element: PsiElement?, message: String) {
                val target = element ?: where ?: return
                context.report(APP_LINK_URL, activity.annotation, context.getLocation(target), message)
            }

            val view = "android.intent.action.VIEW" in filter.actions
            val browsable = "android.intent.category.BROWSABLE" in filter.categories
            val all = filter.data.flatMap { d -> d.values.map { (k, v) -> Triple(k, v, d.element) } }
            val schemes = all.filter { it.first == "scheme" }.map { it.second }
            val hosts = all.filter { it.first == "host" }.map { it.second }
            val web = schemes.any { it == "http" || it == "https" }

            if (view && browsable && filter.data.isEmpty()) {
                report(where, "Browsable `VIEW` intent filter has no `Manifest.Data`, so it can't match any link. Add `data = [Manifest.Data(scheme = …, host = …)]`.")
            }
            // <data> attributes combine across one filter, so these checks are per filter.
            if (schemes.isEmpty() && all.any { it.first != "mimeType" && it.first != "scheme" }) {
                report(all.first { it.first != "mimeType" }.third, "`host`, `port` and paths are ignored without a `scheme` in the same intent filter.")
            }
            if (all.any { it.first == "port" } && hosts.isEmpty()) {
                report(all.first { it.first == "port" }.third, "`port` is ignored without a `host` in the same intent filter.")
            }
            for ((key, value, element) in all) {
                if (value.startsWith("@") || value.startsWith("\${")) continue // resource or placeholder
                when (key) {
                    "scheme" -> if (value.contains(':') || value.contains('/') || value != value.lowercase()) {
                        report(element, "Scheme `$value` must be lowercase and written without `://`, e.g. `https`.")
                    }
                    "host" -> {
                        if (value != value.lowercase()) report(element, "Host `$value` must be lowercase; intent matching is case-sensitive.")
                        if (value.indexOf('*') > 0 || value.count { it == '*' } > 1) {
                            report(element, "Host wildcard `*` is only allowed as a leading `*.`.")
                        }
                    }
                    "port" -> if (value.toIntOrNull() == null) report(element, "Port `$value` must be a number.")
                    "path", "pathPrefix", "pathPattern", "pathAdvancedPattern" -> if (!value.startsWith("/")) {
                        report(element, "`$key` `$value` must start with `/`.")
                    }
                }
            }
            if (view && web && !browsable) {
                report(where, "`VIEW` filter for web links is missing `Manifest.Category.BROWSABLE`, so browsers can't open it.")
            }
            if (filter.autoVerify && (!web || hosts.isEmpty())) {
                report(where, "`autoVerify = true` needs an `http`/`https` scheme and a host to verify.")
            }
        }
    }

    // ---- Instantiatable ----

    private fun checkInstantiatable(context: JavaContext, node: UClass, activity: AnnotatedActivity) {
        val evaluator = context.evaluator
        val name = node.name ?: return
        val problem = when {
            node.sourcePsi is KtObjectDeclaration -> "is an `object`"
            node.isInterface || node.isEnum || node.isAnnotationType -> "is not a class"
            evaluator.isAbstract(node) -> "is abstract"
            evaluator.isPrivate(node) || evaluator.isProtected(node) -> "is not public"
            node.containingClass != null && !evaluator.isStatic(node) -> "is an inner class"
            !hasNoArgConstructor(context, node) -> "has no public no-argument constructor"
            !evaluator.extendsClass(node.javaPsi, "android.app.Activity", false) -> "doesn't extend `android.app.Activity`"
            else -> return
        }
        context.report(
            INSTANTIATABLE, activity.annotation, context.getLocation(activity.annotation as UElement),
            "`$name` $problem, so Android can't instantiate it as an activity.",
        )
    }

    private fun hasNoArgConstructor(context: JavaContext, node: UClass): Boolean {
        val constructors = node.javaPsi.constructors
        if (constructors.isEmpty()) return true
        if (constructors.any { it.parameterList.isEmpty && context.evaluator.isPublic(it) }) return true
        // Kotlin emits a no-arg JVM constructor when every primary-constructor parameter has a default.
        val primary = (node.sourcePsi as? KtClass)?.primaryConstructor ?: return false
        return primary.valueParameters.all { it.hasDefaultValue() } &&
            primary.visibilityModifier()?.text.let { it == null || it == "public" || it == "internal" }
    }

    // ---- DiscouragedApi / LockedOrientationActivity ----

    private fun checkOrientation(context: JavaContext, activity: AnnotatedActivity) {
        activity.enumName("screenOrientation")?.takeIf { it in FIXED_ORIENTATIONS }?.let {
            context.report(
                FIXED_ORIENTATION, activity.annotation, context.getLocation(activity.arguments.getValue("screenOrientation")),
                "Fixed `screenOrientation` ($it) letterboxes this activity on large screens and foldables, " +
                    "and Android 16 ignores it there for apps targeting API 36.",
            )
        }
        if (activity.boolean("resizeableActivity") == false) {
            context.report(
                FIXED_ORIENTATION, activity.annotation, context.getLocation(activity.arguments.getValue("resizeableActivity")),
                "`resizeableActivity = false` letterboxes this activity on large screens, and Android 16 " +
                    "ignores it there for apps targeting API 36.",
            )
        }
    }

    companion object {
        private val FIXED_ORIENTATIONS = setOf(
            "PORTRAIT", "LANDSCAPE", "REVERSE_PORTRAIT", "REVERSE_LANDSCAPE", "SENSOR_PORTRAIT",
            "SENSOR_LANDSCAPE", "USER_PORTRAIT", "USER_LANDSCAPE", "NO_SENSOR", "LOCKED",
        )

        private val IMPLEMENTATION = Implementation(AnnotatedManifestChecksDetector::class.java, Scope.JAVA_FILE_SCOPE)

        @JvmField
        val APP_LINK_URL: Issue = Issue.create(
            id = "TypedManifestAppLinkUrl",
            briefDescription = "Invalid deep link or App Link in @Manifest.Activity",
            explanation = """
                Checks `Manifest.IntentFilter` / `Manifest.Data` the way the built-in \
                `AppLinkUrlError` check validates `<intent-filter>` XML: browsable `VIEW` filters \
                need data, `<data>` parts need a scheme, schemes and hosts must be lowercase, paths \
                start with `/`, ports are numeric, and `autoVerify` needs http(s) and a host.

                typed-manifest suppresses the built-in check on its generated manifest so the \
                problem is reported here, on the annotation.
                """,
            category = Category.CORRECTNESS,
            priority = 8,
            severity = Severity.ERROR,
            implementation = IMPLEMENTATION,
        )

        @JvmField
        val INSTANTIATABLE: Issue = Issue.create(
            id = "TypedManifestInstantiatable",
            briefDescription = "@Manifest.Activity class can't be instantiated",
            explanation = """
                Android creates activities by reflection, so the class must be a public, \
                non-abstract, non-inner class extending `android.app.Activity` with a public \
                no-argument constructor. Replaces the built-in `Instantiatable` check for \
                annotated activities.
                """,
            category = Category.CORRECTNESS,
            priority = 6,
            severity = Severity.ERROR,
            implementation = IMPLEMENTATION,
        )

        @JvmField
        val FIXED_ORIENTATION: Issue = Issue.create(
            id = "TypedManifestFixedOrientation",
            briefDescription = "Activity locks orientation or resizing",
            explanation = """
                Locking orientation or disabling resizing letterboxes the activity on tablets, \
                foldables and desktop windows. Since Android 16, apps targeting API 36 have these \
                restrictions ignored on large screens anyway. Replaces the built-in \
                `DiscouragedApi` (orientation) and `LockedOrientationActivity` checks for annotated \
                activities.
                """,
            category = Category.CORRECTNESS,
            priority = 4,
            severity = Severity.WARNING,
            implementation = IMPLEMENTATION,
        )
    }
}
