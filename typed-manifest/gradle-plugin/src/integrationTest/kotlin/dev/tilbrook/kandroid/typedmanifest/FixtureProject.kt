package dev.tilbrook.kandroid.typedmanifest

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

private fun prop(name: String): String =
    System.getProperty("typedManifest.$name") ?: error("Missing system property typedManifest.$name (run via Gradle)")

object Versions {
    val testRepo = prop("testRepo")
    val plugin = prop("version")
    val agp = prop("agpVersion")
    val kotlin = prop("kotlinVersion")
    val compileSdk = prop("compileSdk")
    val minSdk = prop("minSdk")
    val projectsDir = File(prop("projectsDir"))
}

/** The module flavours the plugin supports, each with its own fixture layout. */
enum class ModuleKind(
    val namespace: String,
    /** Where Kotlin sources go. */
    val kotlinDir: String,
    /** Where Java sources go, or null when the module type has no Java source set. */
    val javaDir: String?,
    val manifestPath: String,
    val assembleTask: String,
    val gradleProperties: String,
    val buildScript: String,
) {
    APPLICATION(
        namespace = "com.example.app",
        kotlinDir = "src/main/kotlin",
        javaDir = "src/main/java",
        manifestPath = "src/main/AndroidManifest.xml",
        assembleTask = "assembleDebug",
        gradleProperties = "",
        buildScript = """
            plugins {
                id("com.android.application")
                id("dev.tilbrook.typed-manifest")
            }
            android {
                namespace = "com.example.app"
                compileSdk = ${Versions.compileSdk}
                defaultConfig {
                    applicationId = "com.example.app"
                    minSdk = ${Versions.minSdk}
                    targetSdk = ${Versions.compileSdk}
                    versionCode = 1
                    versionName = "1.0"
                }
            }
        """.trimIndent(),
    ),
    LIBRARY(
        namespace = "com.example.lib",
        kotlinDir = "src/main/kotlin",
        javaDir = "src/main/java",
        manifestPath = "src/main/AndroidManifest.xml",
        assembleTask = "assembleDebug",
        gradleProperties = "",
        buildScript = """
            plugins {
                id("com.android.library")
                id("dev.tilbrook.typed-manifest")
            }
            android {
                namespace = "com.example.lib"
                compileSdk = ${Versions.compileSdk}
                defaultConfig { minSdk = ${Versions.minSdk} }
            }
        """.trimIndent(),
    ),
    KMP_LIBRARY(
        namespace = "com.example.kmplib",
        kotlinDir = "src/androidMain/kotlin",
        javaDir = null,
        manifestPath = "src/androidMain/AndroidManifest.xml",
        assembleTask = "assemble",
        gradleProperties = "",
        buildScript = """
            plugins {
                id("org.jetbrains.kotlin.multiplatform")
                id("com.android.kotlin.multiplatform.library")
                id("dev.tilbrook.typed-manifest")
            }
            kotlin {
                android {
                    namespace = "com.example.kmplib"
                    compileSdk = ${Versions.compileSdk}
                    minSdk = ${Versions.minSdk}
                }
            }
        """.trimIndent(),
    ),

    /**
     * KMP + com.android.application. AGP 9 only supports this with built-in Kotlin and the new DSL
     * turned off; JetBrains recommends a separate Android app module instead, but existing projects
     * still use this shape.
     */
    KMP_APPLICATION(
        namespace = "com.example.kmpapp",
        kotlinDir = "src/androidMain/kotlin",
        javaDir = null,
        manifestPath = "src/androidMain/AndroidManifest.xml",
        assembleTask = "assembleDebug",
        gradleProperties = "android.builtInKotlin=false\nandroid.newDsl=false\n",
        buildScript = """
            plugins {
                id("org.jetbrains.kotlin.multiplatform")
                id("com.android.application")
                id("dev.tilbrook.typed-manifest")
            }
            kotlin {
                androidTarget {
                    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
                }
            }
            android {
                namespace = "com.example.kmpapp"
                compileSdk = ${Versions.compileSdk}
                defaultConfig {
                    applicationId = "com.example.kmpapp"
                    minSdk = ${Versions.minSdk}
                    targetSdk = ${Versions.compileSdk}
                    versionCode = 1
                    versionName = "1.0"
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }
        """.trimIndent(),
    ),
    ;

    val isLibrary get() = this == LIBRARY || this == KMP_LIBRARY
    val packagePath get() = namespace.replace('.', '/')
}

class FixtureProject(val kind: ModuleKind, testName: String) {
    val dir: File = File(Versions.projectsDir, "${kind.name.lowercase()}-$testName").apply {
        deleteRecursively()
        mkdirs()
    }

    init {
        val repo = File(Versions.testRepo).toURI()
        file(
            "settings.gradle.kts",
            """
            pluginManagement {
                repositories {
                    maven { url = uri("$repo") }
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
                plugins {
                    id("com.android.application") version "${Versions.agp}"
                    id("com.android.library") version "${Versions.agp}"
                    id("com.android.kotlin.multiplatform.library") version "${Versions.agp}"
                    id("org.jetbrains.kotlin.multiplatform") version "${Versions.kotlin}"
                    id("dev.tilbrook.typed-manifest") version "${Versions.plugin}"
                }
            }
            dependencyResolutionManagement {
                repositories {
                    maven { url = uri("$repo") }
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "fixture-${kind.name.lowercase().replace('_', '-')}"
            """.trimIndent(),
        )
        file(
            "gradle.properties",
            "org.gradle.jvmargs=-Xmx3g\nandroid.useAndroidX=true\n" + kind.gradleProperties,
        )
        file("build.gradle.kts", kind.buildScript)
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        if (sdk != null) file("local.properties", "sdk.dir=${sdk.replace("\\", "\\\\")}\n")
    }

    fun file(path: String, content: String): File =
        File(dir, path).apply { parentFile.mkdirs(); writeText(content) }

    fun read(path: String): String = File(dir, path).readText()

    private fun runner(vararg args: String) = GradleRunner.create()
        .withProjectDir(dir)
        .withArguments(args.toList() + listOf("--stacktrace", "--configuration-cache"))
        .forwardOutput()

    fun run(vararg args: String): BuildResult = runner(*args).build()

    fun runAndFail(vararg args: String): BuildResult = runner(*args).buildAndFail()

    /** `<activity>` elements of the final merged manifest, keyed by android:name. */
    fun mergedActivities(): Map<String, Element> {
        val xml = if (kind.isLibrary) {
            val aar = File(dir, "build/outputs/aar").walkTopDown().firstOrNull { it.extension == "aar" }
                ?: error("No AAR produced under ${dir}/build/outputs/aar")
            ZipFile(aar).use { zip -> zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes().decodeToString() }
        } else {
            File(dir, "build/intermediates").walkTopDown()
                .filter { it.name == "AndroidManifest.xml" && it.path.contains("merged_manifest") }
                .firstOrNull()?.readText()
                ?: error("No merged manifest under ${dir}/build/intermediates")
        }
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(xml.byteInputStream())
        val nodes = doc.getElementsByTagName("activity")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associateBy { it.androidAttr("name")!! }
    }
}

fun Element.androidAttr(name: String): String? =
    getAttributeNS("http://schemas.android.com/apk/res/android", name).takeIf { it.isNotEmpty() }
