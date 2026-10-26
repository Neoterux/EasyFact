// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.animalsniffer) apply false
}

// One `./gradlew check` must run every gate this phase adds, including the unit tests of the
// included build-logic build, which Gradle does not run on its own.
tasks.register("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}
