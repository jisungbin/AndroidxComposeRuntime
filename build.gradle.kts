plugins {
  id("org.jetbrains.kotlin.jvm") version "2.0.0"
  id("org.jetbrains.kotlin.plugin.compose") version "2.0.0"
}

dependencies {
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0-RC")
  implementation("androidx.annotation:annotation:1.8.0")
  implementation("androidx.collection:collection:1.4.0")
  testImplementation(kotlin("test"))
  testImplementation(kotlin("reflect"))
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0-RC")
  testImplementation("androidx.compose.runtime:runtime-test-utils:1.8.0-SNAPSHOT")
}
