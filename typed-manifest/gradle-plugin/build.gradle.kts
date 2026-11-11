import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.publish)
    signing
}

val jvmTargetVersion = libs.versions.jvmTarget.get()

gradlePlugin {
    website = "https://github.com/mtilbrook-dev/Kandroid"
    vcsUrl = "https://github.com/mtilbrook-dev/Kandroid"
    plugins {
        create("typedManifest") {
            id = "dev.tilbrook.typed-manifest"
            displayName = "Kandroid typed manifest"
            description = "Generates AndroidManifest.xml entries from @Manifest annotations, secure by default."
            tags = listOf("android", "manifest", "kotlin", "kmp")
            implementationClass = "dev.tilbrook.kandroid.typedmanifest.gradle.TypedManifestPlugin"
        }
    }
}

// Generated so the plugin can add the matching annotations artifact version.
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val version = project.version.toString()
    val outputDir = layout.buildDirectory.dir("generated/build-info")
    inputs.property("version", version)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("dev/tilbrook/kandroid/typedmanifest/gradle/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package dev.tilbrook.kandroid.typedmanifest.gradle
            |
            |internal object BuildInfo {
            |    const val VERSION = "$version"
            |}
            |""".trimMargin(),
        )
    }
}

kotlin {
    // Emit metadata older compilers can read: AGP 9.x built-in Kotlin and Gradle's embedded
    // Kotlin are behind the version we build with.
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(jvmTargetVersion)
        languageVersion = KotlinVersion.KOTLIN_2_2
        apiVersion = KotlinVersion.KOTLIN_2_2
    }
    sourceSets.named("main") { kotlin.srcDir(generateBuildInfo) }
}

java {
    sourceCompatibility = JavaVersion.toVersion(jvmTargetVersion)
    targetCompatibility = JavaVersion.toVersion(jvmTargetVersion)
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    // Enum/constant tables are read from the annotations module so there is one source of truth.
    implementation(projects.typedManifest.annotations)
    compileOnly(libs.android.gradle.api)
    // Gradle provides kotlin-stdlib at runtime; depending on it would only add version conflicts.
    compileOnly(libs.kotlin.stdlib)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") artifactId = "typed-manifest-gradle-plugin"
    }
    repositories {
        maven {
            name = "testRepo"
            url = uri(rootProject.layout.buildDirectory.dir("test-repo"))
        }
    }
}

signing {
    val key = providers.gradleProperty("signingKey").orNull
    isRequired = key != null
    if (key != null) useInMemoryPgpKeys(key, providers.gradleProperty("signingPassword").orNull)
}

val testRepo = rootProject.layout.buildDirectory.dir("test-repo")

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junit)
            dependencies { implementation(libs.kotlin.stdlib) }
        }

        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation(gradleTestKit())
                implementation(libs.kotlin.stdlib)
            }
            targets.configureEach {
                testTask.configure {
                    description = "Builds Android/KMP fixture projects against the published plugin."
                    shouldRunAfter(tasks.named("test"))
                    dependsOn(
                        "publishAllPublicationsToTestRepoRepository",
                        ":typed-manifest:annotations:publishAllPublicationsToTestRepoRepository",
                    )
                    inputs.dir(testRepo).withPropertyName("testRepo").withPathSensitivity(PathSensitivity.RELATIVE)
                    systemProperty("typedManifest.testRepo", testRepo.get().asFile.absolutePath)
                    systemProperty("typedManifest.version", project.version.toString())
                    systemProperty("typedManifest.agpVersion", libs.versions.gradle.android.get())
                    systemProperty("typedManifest.kotlinVersion", libs.versions.kotlin.get())
                    systemProperty("typedManifest.compileSdk", libs.versions.android.compileSdk.get())
                    systemProperty("typedManifest.minSdk", libs.versions.android.minSdk.get())
                    systemProperty(
                        "typedManifest.projectsDir",
                        layout.buildDirectory.dir("integration-test-projects").get().asFile.absolutePath,
                    )
                    // AGP builds are heavy; keep them sequential.
                    maxParallelForks = 1
                    testLogging {
                        events("passed", "skipped", "failed")
                        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                        showStandardStreams = providers.environmentVariable("CI").isPresent
                    }
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(testing.suites.named("integrationTest"))
}
