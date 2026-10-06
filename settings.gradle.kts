pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central (Maven Central itself rate-limits CI/proxy traffic).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central (Maven Central itself rate-limits CI/proxy traffic).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "stadtlaerm"
include(":dsp", ":app")
