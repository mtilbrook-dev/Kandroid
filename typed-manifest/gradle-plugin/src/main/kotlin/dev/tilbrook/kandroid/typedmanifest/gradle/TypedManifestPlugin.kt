package dev.tilbrook.kandroid.typedmanifest.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.Component
import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskProvider

/**
 * `dev.tilbrook.typed-manifest`
 *
 * - Generates a per-variant manifest overlay from `@Manifest.*` annotations and hands it to AGP's
 *   manifest merger (`variant.sources.manifests`). Generation is wired before `pre<Variant>Build`.
 * - Honours `android.builtInKotlin`: with built-in Kotlin off and no Kotlin plugin applied, `.kt`
 *   files aren't compiled, so they aren't scanned either.
 * - Adds the annotations artifact as a compile-only dependency, and the lint checks to `lintChecks`,
 *   for each enabled entry kind that is valid for the module type.
 * - Registers `migrateManifestToAnnotations` to move existing `<activity>` entries onto annotations.
 */
class TypedManifestPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("typedManifest", TypedManifestExtension::class.java)
        val migrate = registerMigrationTask(project)
        // Resolved in afterEvaluate, once every plugin has been applied; tasks read it lazily.
        val kotlinCompiled = project.objects.property(Boolean::class.java)

        var wired = false
        fun wire(block: () -> Unit) {
            if (wired) return
            wired = true
            block()
        }

        project.pluginManager.withPlugin("com.android.application") {
            wire {
                project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
                    .onVariants { registerVariant(project, extension, it, migrate, kotlinCompiled) }
            }
        }
        project.pluginManager.withPlugin("com.android.library") {
            wire {
                project.extensions.getByType(LibraryAndroidComponentsExtension::class.java)
                    .onVariants { registerVariant(project, extension, it, migrate, kotlinCompiled) }
            }
        }
        project.pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
            wire {
                project.extensions.getByType(KotlinMultiplatformAndroidComponentsExtension::class.java)
                    .onVariants { registerKmpLibraryVariant(project, extension, it, migrate, kotlinCompiled) }
            }
        }

        project.afterEvaluate {
            val moduleType = detectModuleType(project)
            kotlinCompiled.set(isKotlinCompiled(project, moduleType))
            kotlinCompiled.finalizeValue()
            addAnnotationDependencies(project, extension, moduleType)
        }
    }

    /**
     * AGP 9 compiles Kotlin itself unless `android.builtInKotlin=false`; then a Kotlin Gradle plugin
     * (`org.jetbrains.kotlin.android` or multiplatform) has to be applied for `.kt` sources to build.
     */
    private fun isKotlinCompiled(project: Project, moduleType: ModuleType): Boolean {
        if (moduleType.isMultiplatform) return true
        val builtInKotlin = project.providers.gradleProperty("android.builtInKotlin")
            .map { it.trim().toBoolean() }
            .getOrElse(true)
        return builtInKotlin ||
            project.pluginManager.hasPlugin("org.jetbrains.kotlin.android") ||
            project.pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform")
    }

    private fun detectModuleType(project: Project): ModuleType {
        val plugins = project.pluginManager
        val kmp = plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")
        return when {
            plugins.hasPlugin("com.android.kotlin.multiplatform.library") -> ModuleType.KMP_ANDROID_LIBRARY
            plugins.hasPlugin("com.android.application") ->
                if (kmp) ModuleType.KMP_ANDROID_APPLICATION else ModuleType.ANDROID_APPLICATION
            plugins.hasPlugin("com.android.library") ->
                if (kmp) ModuleType.KMP_ANDROID_LEGACY_LIBRARY else ModuleType.ANDROID_LIBRARY
            else -> throw GradleException(
                "dev.tilbrook.typed-manifest requires com.android.application, com.android.library or " +
                    "com.android.kotlin.multiplatform.library in ${project.path}",
            )
        }
    }

    private fun addAnnotationDependencies(project: Project, extension: TypedManifestExtension, moduleType: ModuleType) {
        val kinds = ManifestEntryKind.all.filter { moduleType in it.supportedModuleTypes }
        val enabled = kinds.associateWith { extension.options(it).enabled }
        val factory = project.dependencyFactory

        fun addTo(configurationName: String, toggle: Property<Boolean>, module: (ManifestEntryKind) -> String?) {
            project.configurations
                .matching { it.name == configurationName }
                .configureEach { configuration ->
                    configuration.withDependencies { deps ->
                        if (!toggle.get()) return@withDependencies
                        kinds.filter { enabled.getValue(it).get() }
                            .mapNotNull(module)
                            .distinct()
                            .forEach { deps.add(factory.create("$it:${BuildInfo.VERSION}")) }
                    }
                }
        }

        addTo(moduleType.compileOnlyConfiguration, extension.addAnnotationDependency) { it.annotationModule }
        // AGP's configuration for lint check jars that run on this module.
        addTo("lintChecks", extension.addLintChecks) { it.lintModule }
    }

    private fun registerVariant(
        project: Project,
        extension: TypedManifestExtension,
        variant: Component,
        migrate: TaskProvider<MigrateManifestTask>,
        kotlinCompiled: Property<Boolean>,
    ) {
        val capitalized = variant.name.replaceFirstChar { it.uppercase() }
        val task = project.tasks.register("generate${capitalized}TypedManifest", GenerateTypedManifestTask::class.java) {
            it.group = "typed manifest"
            it.description = "Generates manifest entries for the ${variant.name} variant from @Manifest annotations."
            variant.sources.kotlin?.let { dirs -> it.sourceDirectories.from(dirs.static) }
            variant.sources.java?.let { dirs -> it.sourceDirectories.from(dirs.static) }
            it.activitiesEnabled.set(extension.activities.enabled)
            it.includeKotlinSources.set(kotlinCompiled)
            it.outputManifest.set(
                project.layout.buildDirectory.file("generated/typed-manifest/${variant.name}/AndroidManifest.xml"),
            )
        }
        variant.sources.manifests.addGeneratedManifestFile(task, GenerateTypedManifestTask::outputManifest)

        // Make sure the overlay exists before AGP's pre-build checks look at the variant's manifest
        // sources. Spec-based named() is lazy: it matches the task whenever AGP registers it and only
        // configures it if it is realised.
        val preBuild = "pre${capitalized}Build"
        project.tasks.named { it == preBuild }.configureEach { it.dependsOn(task) }

        // Any variant's namespace is fine for resolving relative names in the main manifest.
        val namespace = variant.namespace
        migrate.configure { if (!it.namespace.isPresent) it.namespace.set(namespace) }
    }

    /**
     * The KMP library plugin's manifest merge doesn't read `sources.manifests`, so transform the
     * merged manifest instead.
     */
    private fun registerKmpLibraryVariant(
        project: Project,
        extension: TypedManifestExtension,
        variant: Component,
        migrate: TaskProvider<MigrateManifestTask>,
        kotlinCompiled: Property<Boolean>,
    ) {
        val capitalized = variant.name.replaceFirstChar { it.uppercase() }
        val task = project.tasks.register("inject${capitalized}TypedManifest", InjectTypedManifestTask::class.java) {
            it.group = "typed manifest"
            it.description = "Adds @Manifest entries to the ${variant.name} merged manifest."
            it.includeKotlinSources.set(kotlinCompiled)
            variant.sources.kotlin?.let { dirs -> it.sourceDirectories.from(dirs.static) }
            variant.sources.java?.let { dirs -> it.sourceDirectories.from(dirs.static) }
            // The KMP variant's Sources don't list the Kotlin source set dirs, so add the
            // conventional Android ones explicitly. (Common code can't subclass Activity.)
            val src = project.layout.projectDirectory.dir("src")
            it.sourceDirectories.from(src.dir("androidMain/kotlin"), src.dir("androidMain/java"))
            it.activitiesEnabled.set(extension.activities.enabled)
        }
        variant.artifacts.use(task)
            .wiredWithFiles(InjectTypedManifestTask::mergedManifest, InjectTypedManifestTask::updatedManifest)
            .toTransform(SingleArtifact.MERGED_MANIFEST)

        val namespace = variant.namespace
        migrate.configure { if (!it.namespace.isPresent) it.namespace.set(namespace) }
    }

    private fun registerMigrationTask(project: Project): TaskProvider<MigrateManifestTask> {
        val projectDir = project.layout.projectDirectory
        return project.tasks.register("migrateManifestToAnnotations", MigrateManifestTask::class.java) { task ->
            task.manifestFile.convention(
                project.provider {
                    listOf("src/main/AndroidManifest.xml", "src/androidMain/AndroidManifest.xml")
                        .map { projectDir.file(it) }
                        .firstOrNull { it.asFile.isFile }
                        ?: projectDir.file("src/main/AndroidManifest.xml")
                },
            )
            task.sourceRoots.from(
                project.provider {
                    projectDir.dir("src").asFile.listFiles().orEmpty()
                        .filter { it.isDirectory && !it.name.contains("test", ignoreCase = true) }
                        .flatMap { listOf(it.resolve("kotlin"), it.resolve("java")) }
                        .filter { it.isDirectory }
                },
            )
        }
    }
}
