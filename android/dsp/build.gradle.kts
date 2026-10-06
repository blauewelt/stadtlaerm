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
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    // The category/label tests validate the very asset files that ship in the APK.
    systemProperty("stadtlaerm.assets", rootProject.file("app/src/main/assets").absolutePath)
    // Optional cross-check with the Python/LiteRT reference (tools/verify_yamnet.py).
    System.getenv("STADTLAERM_PREPROC_DIR")?.let { systemProperty("stadtlaerm.preprocDir", it) }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
