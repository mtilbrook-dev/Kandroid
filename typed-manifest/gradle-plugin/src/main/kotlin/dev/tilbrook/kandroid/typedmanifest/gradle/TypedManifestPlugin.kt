package dev.tilbrook.kandroid.typedmanifest.gradle

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.Component
import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/**
 * `dev.tilbrook.typed-manifest`
 *
 * - Generates a per-variant manifest overlay from `@Manifest.*` annotations and hands it to AGP's
 *   manifest merger (`variant.sources.manifests`).
 * - Adds the annotations artifact as a compile-only dependency for each enabled entry kind that is
 *   valid for the module type.
 * - Registers `migrateManifestToAnnotations` to move existing `<activity>` entries onto annotations.
 */
class TypedManifestPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("typedManifest", TypedManifestExtension::class.java)
        val migrate = registerMigrationTask(project)

        var wired = false
        fun wire(block: () -> Unit) {
            if (wired) return
            wired = true
            block()
        }

        project.pluginManager.withPlugin("com.android.application") {
            wire {
                project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
                    .onVariants { registerVariant(project, extension, it, migrate) }
            }
        }
        project.pluginManager.withPlugin("com.android.library") {
            wire {
                project.extensions.getByType(LibraryAndroidComponentsExtension::class.java)
                    .onVariants { registerVariant(project, extension, it, migrate) }
            }
        }
        project.pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
            wire {
                project.extensions.getByType(KotlinMultiplatformAndroidComponentsExtension::class.java)
                    .onVariant { registerVariant(project, extension, it, migrate) }
            }
        }

        project.afterEvaluate {
            val moduleType = detectModuleType(project)
            addAnnotationDependencies(project, extension, moduleType)
        }
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
        val addDependency = extension.addAnnotationDependency
        val factory = project.dependencyFactory
        project.configurations
            .matching { it.name == moduleType.compileOnlyConfiguration }
            .configureEach { configuration ->
                configuration.withDependencies { deps ->
                    if (!addDependency.get()) return@withDependencies
                    kinds.filter { enabled.getValue(it).get() }
                        .map { it.annotationModule }
                        .distinct()
                        .forEach { deps.add(factory.create("$it:${BuildInfo.VERSION}")) }
                }
            }
    }

    private fun registerVariant(
        project: Project,
        extension: TypedManifestExtension,
        variant: Component,
        migrate: TaskProvider<MigrateManifestTask>,
    ) {
        val capitalized = variant.name.replaceFirstChar { it.uppercase() }
        val task = project.tasks.register("generate${capitalized}TypedManifest", GenerateTypedManifestTask::class.java) {
            it.group = "typed manifest"
            it.description = "Generates manifest entries for the ${variant.name} variant from @Manifest annotations."
            variant.sources.kotlin?.let { dirs -> it.sourceDirectories.from(dirs.static) }
            variant.sources.java?.let { dirs -> it.sourceDirectories.from(dirs.static) }
            it.activitiesEnabled.set(extension.activities.enabled)
            it.outputManifest.set(
                project.layout.buildDirectory.file("generated/typed-manifest/${variant.name}/AndroidManifest.xml"),
            )
        }
        variant.sources.manifests.addGeneratedManifestFile(task, GenerateTypedManifestTask::outputManifest)

        // Any variant's namespace is fine for resolving relative names in the main manifest.
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
