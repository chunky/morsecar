// Pure-Kotlin Morse engine: text -> timed elements -> PCM samples.
// Kept free of Android dependencies so it can be unit-tested on the JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.register<JavaExec>("renderWav") {
    description = "Renders text to a WAV file. Pass options with --args; see RenderWav.kt."
    group = "application"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.icculus.chunky.morsecar.morse.tools.RenderWavKt")
    workingDir = rootProject.projectDir
}
