import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Les simulations IA contre IA peuvent être longues.
    maxHeapSize = "1g"
    testLogging { events("passed", "failed"); showStandardStreams = true }
}

// Diagnostic : ./gradlew :core:debugGame --args="seed niveau0 niveau1 [melee]"
tasks.register<JavaExec>("debugGame") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.kratour.core.DebugGame")
}
