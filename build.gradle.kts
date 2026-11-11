plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.publish) apply false
}

val kandroidVersion = libs.versions.kandroid.get()

allprojects {
    group = "dev.tilbrook"
    version = kandroidVersion
}
