// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// Force JavaPoet version globally across all projects and buildscript
buildscript {
    dependencies {
        // This forces the version in the Gradle build process itself
        classpath("com.squareup:javapoet:1.13.0")
    }
}

subprojects {
    configurations.all {
        resolutionStrategy.force("com.squareup:javapoet:1.13.0")
    }
}
