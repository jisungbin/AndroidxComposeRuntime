@file:Suppress("UnstableApiUsage")

rootProject.name = "AndroidxComposeRuntime"

pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    google()
  }
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    google()
    maven("https://androidx.dev/snapshots/builds/12033844/artifacts/repository")
  }
}

