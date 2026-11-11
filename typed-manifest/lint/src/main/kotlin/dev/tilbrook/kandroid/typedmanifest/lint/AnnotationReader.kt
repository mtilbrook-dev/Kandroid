package dev.tilbrook.kandroid.typedmanifest.lint

import com.android.tools.lint.detector.api.ConstantEvaluator
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCollectionLiteralExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.ValueArgument
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.toUElement

/** A `@Manifest.Activity` read from source, with the PSI needed to build fixes. */
internal class AnnotatedActivity(
    val info: ActivityInfo,
    val annotation: UAnnotation,
    /** The value written for `exported`, if any. */
    val exportedValue: PsiElement?,
)

/**
 * Reads `@Manifest.Activity` from Kotlin or Java PSI. Nested annotation arguments are modelled
 * differently by each language, so this goes below UAST; constants (e.g. `Manifest.Action.SEND`)
 * are still evaluated through lint's evaluators.
 */
internal object AnnotationReader {

    fun read(uClass: UClass): AnnotatedActivity? {
        val annotation = uClass.findAnnotation(ACTIVITY_ANNOTATION) ?: return null
        val name = uClass.qualifiedName ?: return null
        return when (val psi = annotation.sourcePsi) {
            is KtAnnotationEntry -> readKotlin(name, annotation, psi)
            else -> (annotation.javaPsi as? PsiAnnotation)?.let { readJava(name, annotation, it) }
        }
    }

    // ---- Kotlin ----

    private fun readKotlin(name: String, annotation: UAnnotation, entry: KtAnnotationEntry): AnnotatedActivity {
        val args = named(entry.valueArguments)
        val exported = args["exported"]
        val filters = args["intentFilters"]?.let { elements(it) }.orEmpty().mapNotNull { call(it) }.map { filter ->
            val f = named(filter.valueArguments)
            FilterInfo(
                actions = f["actions"]?.let { elements(it) }.orEmpty().mapNotNull { string(it) },
                categories = f["categories"]?.let { elements(it) }.orEmpty().mapNotNull { string(it) },
                autoVerify = f["autoVerify"]?.let { bool(it) } == true,
            )
        }
        val permission = args["permission"]?.let { string(it) }?.ifEmpty { null }
        return AnnotatedActivity(ActivityInfo(name, exported?.let { bool(it) }, filters, permission), annotation, exported)
    }

    private fun named(arguments: List<ValueArgument>): Map<String, KtExpression> =
        arguments.mapNotNull { arg ->
            val n = arg.getArgumentName()?.asName?.asString() ?: return@mapNotNull null
            val e = arg.getArgumentExpression() ?: return@mapNotNull null
            n to e
        }.toMap()

    private val arrayFactories = setOf("arrayOf", "emptyArray")

    private fun elements(e: KtExpression): List<KtExpression> = when {
        e is KtCollectionLiteralExpression -> e.getInnerExpressions()
        e is KtCallExpression && e.calleeExpression?.text in arrayFactories ->
            e.valueArguments.mapNotNull { it.getArgumentExpression() }
        else -> listOf(e)
    }

    /** `IntentFilter(...)` or `Manifest.IntentFilter(...)`. */
    private fun call(e: KtExpression): KtCallExpression? =
        e as? KtCallExpression ?: (e as? KtDotQualifiedExpression)?.selectorExpression as? KtCallExpression

    private fun evaluate(e: KtExpression): Any? = (e.toUElement() as? UExpression)?.let { ConstantEvaluator.evaluate(null, it) }

    private fun string(e: KtExpression): String? = evaluate(e) as? String

    private fun bool(e: KtExpression): Boolean? = evaluate(e) as? Boolean

    // ---- Java ----

    private fun readJava(name: String, annotation: UAnnotation, psi: PsiAnnotation): AnnotatedActivity {
        val exported = psi.findDeclaredAttributeValue("exported")
        val filters = javaItems(psi.findDeclaredAttributeValue("intentFilters")).filterIsInstance<PsiAnnotation>().map { f ->
            FilterInfo(
                actions = javaItems(f.findDeclaredAttributeValue("actions")).mapNotNull { javaConstant(it) as? String },
                categories = javaItems(f.findDeclaredAttributeValue("categories")).mapNotNull { javaConstant(it) as? String },
                autoVerify = javaConstant(f.findDeclaredAttributeValue("autoVerify")) == true,
            )
        }
        val permission = (javaConstant(psi.findDeclaredAttributeValue("permission")) as? String)?.ifEmpty { null }
        return AnnotatedActivity(ActivityInfo(name, javaConstant(exported) as? Boolean, filters, permission), annotation, exported)
    }

    private fun javaItems(value: PsiAnnotationMemberValue?): List<PsiAnnotationMemberValue> = when (value) {
        null -> emptyList()
        is PsiArrayInitializerMemberValue -> value.initializers.toList()
        else -> listOf(value)
    }

    private fun javaConstant(value: PsiAnnotationMemberValue?): Any? {
        val expression = value as? PsiExpression ?: return null
        return JavaPsiFacade.getInstance(expression.project).constantEvaluationHelper.computeConstantExpression(expression)
    }
}
