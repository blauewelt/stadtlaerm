plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}

// The history chart (night / day / week): a pure-Kotlin drawing model, unit-tested on the JVM,
// the Compose Canvas that draws it and the app theme. Kept in its own library module so that
// Paparazzi can render it without a device: `./gradlew :chart:testDebugUnitTest` writes PNGs to
// $STADTLAERM_SCREENSHOT_DIR (default chart/build/screenshots). With STADTLAERM_REAL_DATA set to a
// directory holding the app's CSV exports (minuten.csv, ereignisse.csv), real data is rendered too.
android {
    namespace = "ch.stadtlaerm.chart"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
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
    }
}

dependencies {
    api(project(":dsp"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    systemProperty("stadtlaerm.screenshotDir", System.getenv("STADTLAERM_SCREENSHOT_DIR") ?: layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
    System.getenv("STADTLAERM_REAL_DATA")?.let { systemProperty("stadtlaerm.realData", it) }
    testLogging { events("passed", "skipped", "failed") }
}
