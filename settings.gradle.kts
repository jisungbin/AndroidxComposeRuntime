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
    maven("https://androidx.dev/snapshots/builds/14020949/artifacts/repository")
  }
}

