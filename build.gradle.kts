plugins {
  id("org.jetbrains.kotlin.jvm") version "2.2.10"
  id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}

dependencies {
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
  implementation("androidx.annotation:annotation:1.9.1")
  implementation("androidx.collection:collection:1.5.0")

  testImplementation(kotlin("test"))
  testImplementation(kotlin("reflect"))
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
  testImplementation("androidx.compose.runtime:runtime-test-utils:1.10.0-SNAPSHOT")
}
