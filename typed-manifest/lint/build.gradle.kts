import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
    signing
}

val jvmTargetVersion = libs.versions.jvmTarget.get()

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(jvmTargetVersion)
        languageVersion = KotlinVersion.KOTLIN_2_2
        apiVersion = KotlinVersion.KOTLIN_2_2
    }
}

java {
    sourceCompatibility = JavaVersion.toVersion(jvmTargetVersion)
    targetCompatibility = JavaVersion.toVersion(jvmTargetVersion)
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    // Lint supplies its own API, UAST/PSI and Kotlin stdlib at runtime.
    compileOnly(libs.lint.api)
    compileOnly(libs.kotlin.stdlib)
}

tasks.jar {
    manifest { attributes("Lint-Registry-v2" to "dev.tilbrook.kandroid.typedmanifest.lint.TypedManifestIssueRegistry") }
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            // lint-tests is built on JUnit 4.
            useJUnit(libs.versions.junit4)
            dependencies {
                implementation(libs.lint.api)
                implementation(libs.lint.core)
                implementation(libs.lint.tests)
                implementation(libs.kotlin.stdlib)
            }
            targets.configureEach {
                testTask.configure {
                    // Tests compile against the real annotation source.
                    val annotations = layout.projectDirectory.file("../annotations/src/main/kotlin/dev/tilbrook/kandroid/typedmanifest/Manifest.kt").asFile
                    inputs.file(annotations).withPropertyName("annotationsSource").withPathSensitivity(PathSensitivity.NONE)
                    systemProperty("typedManifest.annotationsSource", annotations.absolutePath)
                }
            }
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "typed-manifest-lint"
            from(components["java"])
            pom {
                name = "Kandroid typed-manifest lint checks"
                description = "Android Lint checks for exported activities, intent filters and implicit intents."
                url = "https://github.com/mtilbrook-dev/Kandroid"
                licenses {
                    license {
                        name = "Apache-2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
                scm { url = "https://github.com/mtilbrook-dev/Kandroid" }
            }
        }
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
    if (key != null) {
        useInMemoryPgpKeys(key, providers.gradleProperty("signingPassword").orNull)
        sign(publishing.publications)
    }
}
