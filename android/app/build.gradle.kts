import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Release signing is optional so that anyone can build the public repository.
// Point Gradle at a keystore.properties file kept OUTSIDE the repository, either with
//   ./gradlew assembleRelease -Pstadtlaerm.keystoreProperties=/path/to/keystore.properties
// or with the environment variable STADTLAERM_KEYSTORE_PROPERTIES. The file needs
// storeFile, storePassword, keyAlias and keyPassword. Without it the release APK is unsigned.
val keystorePropertiesPath: String? =
    (findProperty("stadtlaerm.keystoreProperties") as String?)
        ?: System.getenv("STADTLAERM_KEYSTORE_PROPERTIES")
val keystoreProperties: Properties? = keystorePropertiesPath
    ?.takeIf { it.isNotBlank() }
    ?.let { path ->
        val file = file(path)
        require(file.isFile) { "Keystore properties file not found: $path" }
        Properties().apply { file.inputStream().use { load(it) } }
    }

// Build date shown in the app («Build vom …») and used for the offline "this version is n days
// old" reminder. Taken from the build time (Zürich calendar date); SOURCE_DATE_EPOCH, if set,
// overrides it for reproducible builds. Tests may read BuildConfig.BUILD_DATE but must never
// assert its value.
val buildDate: String = (System.getenv("SOURCE_DATE_EPOCH")?.toLongOrNull()
    ?.let { Instant.ofEpochSecond(it).atZone(ZoneId.of("Europe/Zurich")).toLocalDate() }
    ?: LocalDate.now(ZoneId.of("Europe/Zurich"))).toString()

android {
    namespace = "ch.stadtlaerm.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ch.stadtlaerm.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.3.2"
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
    }

    signingConfigs {
        if (keystoreProperties != null) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        // ABI filters are set per build type (AGP merges them with defaultConfig, so they
        // cannot be narrowed there). 32-bit x86 is never shipped.
        debug {
            // Phones (arm64/armv7) and the x86_64 emulator.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        }
        release {
            // Shrinking stays off for now: LiteRT uses reflection/JNI and has not been tested
            // with R8 rules yet.
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            // Phones only: the x86_64 emulator ABI is left out of the release APK to cut size.
            // (Debug builds keep it for the emulator.)
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // The model is memory-mapped by the interpreter, so it must be stored uncompressed.
        noCompress += "tflite"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":dsp"))
    implementation(project(":chart"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.google.ai.edge.litert:litert:1.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    // JVM SQLite to check the Room migration SQL without a device.
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
}

tasks.withType<Test>().configureEach {
    systemProperty("stadtlaerm.generatedDb", layout.buildDirectory.dir("generated/ksp/debug/kotlin/ch/stadtlaerm/app/data").get().asFile.absolutePath)
}
