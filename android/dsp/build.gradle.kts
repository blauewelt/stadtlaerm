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
    // Replay fixtures (16 kHz WAVs and their expected outputs); regenerated only on request.
    systemProperty("stadtlaerm.replayDir", file("src/test/resources/replay").absolutePath)
    System.getenv("STADTLAERM_WRITE_FIXTURES")?.let { systemProperty("stadtlaerm.writeFixtures", it) }
    // Optional cross-check with the Python/LiteRT reference (tools/verify_yamnet.py).
    System.getenv("STADTLAERM_PREPROC_DIR")?.let { systemProperty("stadtlaerm.preprocDir", it) }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

// Offline replay of WAV files through the engine (16 kHz files are upsampled to 48 kHz):
//   ./gradlew :dsp:replay --args="[--excess dB] [--window s] [--min-history s] clip.wav …"
// Relative paths are resolved against the directory Gradle was started from.
tasks.register<JavaExec>("replay") {
    group = "verification"
    description = "Replays WAV files through the measurement engine and prints events and minutes as CSV"
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ch.stadtlaerm.dsp.ReplayMain")
    workingDir = gradle.startParameter.currentDir
}

// Batch replay of Labor clips (manifest + clips directory → one CSV; audio stays where it is):
//   ./gradlew :dsp:replayBatch --args="--manifest m.jsonl --clips dir --out v2.csv [--excess 6.5,5,8] [--floor 30,20]"
tasks.register<JavaExec>("replayBatch") {
    group = "verification"
    description = "Replays all clips of a Labor manifest and writes the detector-v2 result per clip as CSV"
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ch.stadtlaerm.dsp.ReplayBatch")
    workingDir = gradle.startParameter.currentDir
    maxHeapSize = "4g"
}

// Whole-night replay of the Labor continuous recording (decoded to 16 kHz WAVs beforehand):
//   ./gradlew :dsp:replayNight --args="--manifest m.jsonl --wavs dir --session S --out /tmp/night [--excess …] [--floor …]"
tasks.register<JavaExec>("replayNight") {
    group = "verification"
    description = "Replays a Labor session's continuous recording through the engine and matches the events to the manifest's clips"
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ch.stadtlaerm.dsp.ReplayNight")
    workingDir = gradle.startParameter.currentDir
    maxHeapSize = "3g"
}
