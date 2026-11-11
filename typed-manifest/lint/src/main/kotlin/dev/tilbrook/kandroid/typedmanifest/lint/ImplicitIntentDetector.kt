package dev.tilbrook.kandroid.typedmanifest.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.ConstantEvaluator
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Incident
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Project
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.detector.api.targetSdkAtLeast
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UBinaryExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UFile
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UResolvable
import org.jetbrains.uast.UastBinaryOperator
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor
import org.w3c.dom.Document
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Flags implicit intents (`Intent(action)`, `setAction`, `intent.action = …`) whose action matches an
 * intent filter on one of the app's non-exported activities. Since Android 14 (targetSdk 34),
 * implicit intents are only delivered to exported components, so such a call silently stops
 * reaching the activity.
 *
 * Activities come from the merged manifest (which includes entries generated from
 * `@Manifest.Activity`) plus annotations in the file being analysed, so the IDE flags it before a
 * build has refreshed the merged manifest.
 */
class ImplicitIntentDetector : Detector(), SourceCodeScanner {

    private val manifestCache = HashMap<Project, List<ActivityInfo>>()
    private val reported = HashSet<Pair<String, Int>>()

    override fun getApplicableConstructorTypes(): List<String> = listOf(INTENT_CLASS)

    override fun visitConstructor(context: JavaContext, node: UCallExpression, constructor: PsiMethod) {
        val params = constructor.parameterList.parameters
        // Intent(Context, Class) and Intent(String, Uri, Context, Class) are explicit.
        if (params.any { it.type.canonicalText.startsWith("java.lang.Class") }) return
        if (params.firstOrNull()?.type?.canonicalText != "java.lang.String") return
        check(context, node, node.valueArguments.firstOrNull() ?: return)
    }

    override fun getApplicableMethodNames(): List<String> = listOf("setAction")

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        if (method.containingClass?.qualifiedName != INTENT_CLASS) return
        check(context, node, node.valueArguments.firstOrNull() ?: return)
    }

    // Kotlin synthetic property: `intent.action = "…"`
    override fun getApplicableUastTypes(): List<Class<out UElement>> = listOf(UBinaryExpression::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler = object : UElementHandler() {
        override fun visitBinaryExpression(node: UBinaryExpression) {
            if (node.operator != UastBinaryOperator.ASSIGN) return
            val target = (node.leftOperand as? UResolvable)?.resolve() as? PsiMethod ?: return
            if (target.name != "setAction" && target.name != "getAction") return
            if (target.containingClass?.qualifiedName != INTENT_CLASS) return
            check(context, node, node.rightOperand)
        }
    }

    private fun check(context: JavaContext, node: UExpression, actionArg: UExpression) {
        val action = ConstantEvaluator.evaluateString(context, actionArg, false) ?: return
        if (isMadeExplicit(node)) return

        val hidden = activities(context).filter { activity ->
            activity.exported != true && activity.filters.any { action in it.actions }
        }
        if (hidden.isEmpty()) return

        val key = context.file.path to (actionArg.sourcePsi?.textRange?.startOffset ?: -1)
        if (!reported.add(key)) return

        val names = hidden.joinToString { "`${it.name.substringAfterLast('.')}`" }
        val message = "Implicit intent for `$action` matches $names, which isn't exported. From Android 14 implicit " +
            "intents only reach exported activities: make this intent explicit (`setPackage`/`setClass`) " +
            "or set `exported = true` on the activity."
        // Only a problem for apps targeting 34+. Expressed as a constraint so lint's per-module
        // (partial) analysis evaluates it against the consuming app's targetSdk.
        context.report(Incident(ISSUE, node, context.getLocation(node), message), targetSdkAtLeast(34))
    }

    /** True if the enclosing function sets a package or component on an Intent anywhere. */
    private fun isMadeExplicit(node: UElement): Boolean {
        val scope: UElement = node.getParentOfType(UMethod::class.java, true)
            ?: node.getParentOfType(UClass::class.java, true)
            ?: return false
        var explicit = false
        scope.accept(object : AbstractUastVisitor() {
            override fun visitCallExpression(node: UCallExpression): Boolean {
                if (node.methodName in EXPLICIT_SETTERS) {
                    val owner = node.resolve()?.containingClass?.qualifiedName
                    if (owner == null || owner == INTENT_CLASS) explicit = true
                }
                return explicit
            }

            override fun visitBinaryExpression(node: UBinaryExpression): Boolean {
                if (node.operator == UastBinaryOperator.ASSIGN) {
                    val target = (node.leftOperand as? UResolvable)?.resolve() as? PsiMethod
                    if (target != null && target.containingClass?.qualifiedName == INTENT_CLASS &&
                        target.name in setOf("getPackage", "setPackage", "getComponent", "setComponent")
                    ) {
                        explicit = true
                    }
                }
                return explicit
            }
        })
        return explicit
    }

    private fun activities(context: JavaContext): List<ActivityInfo> {
        // context.project, not mainProject: AGP runs lint per module. An app module's merged
        // manifest already includes its libraries' activities.
        val fromManifest = manifestCache.getOrPut(context.project) { manifestActivities(context.project) }
        val fromFile = context.uastFile?.let { annotatedIn(it) }.orEmpty()
        val annotated = fromFile.map { it.name }.toSet()
        return fromManifest.filter { it.name.replace('$', '.') !in annotated } + fromFile
    }

    private fun annotatedIn(file: UFile): List<ActivityInfo> {
        val out = ArrayList<ActivityInfo>()
        fun visit(c: UClass) {
            AnnotationReader.read(c)?.let { out += it.info }
            c.innerClasses.forEach { visit(it) }
        }
        file.classes.forEach { visit(it) }
        return out
    }

    private fun manifestActivities(project: Project): List<ActivityInfo> {
        val documents: List<Document> = project.mergedManifest?.let { listOf(it) }
            ?: project.manifestFiles.mapNotNull { file ->
                runCatching {
                    DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                        .newDocumentBuilder().parse(file)
                }.getOrNull()
            }
        return documents.flatMap { ManifestActivities.read(it) }
    }

    companion object {
        private val EXPLICIT_SETTERS = setOf("setPackage", "setComponent", "setClass", "setClassName", "setSelector")

        @JvmField
        val ISSUE: Issue = Issue.create(
            id = "ImplicitIntentToUnexportedActivity",
            briefDescription = "Implicit intent targets a non-exported activity",
            explanation = """
                Apps targeting Android 14 (API 34) or higher can only deliver implicit intents to \
                exported components. An implicit intent whose action matches one of your own \
                non-exported activities no longer starts it: depending on the call it throws or \
                silently does nothing.

                Prefer making the intent explicit (`Intent(context, Target::class.java)`, \
                `setPackage(context.packageName)` or `setClass`), which keeps the activity private. \
                Only export the activity if other apps really need to start it.
                """,
            category = Category.CORRECTNESS,
            priority = 7,
            severity = Severity.WARNING,
            implementation = Implementation(ImplicitIntentDetector::class.java, Scope.JAVA_FILE_SCOPE),
            moreInfo = "https://developer.android.com/about/versions/14/behavior-changes-14#safer-intents",
        )
    }
}
