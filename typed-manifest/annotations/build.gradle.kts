import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
    signing
}

val jvmTargetVersion = libs.versions.jvmTarget.get()

kotlin {
    compilerOptions { jvmTarget = JvmTarget.fromTarget(jvmTargetVersion) }
}

java {
    sourceCompatibility = JavaVersion.toVersion(jvmTargetVersion)
    targetCompatibility = JavaVersion.toVersion(jvmTargetVersion)
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "typed-manifest-annotations"
            from(components["java"])
            pom {
                name = "Kandroid typed-manifest annotations"
                description = "@Manifest annotations read by the dev.tilbrook.typed-manifest Gradle plugin."
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
        // Used by the plugin's integration tests.
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
