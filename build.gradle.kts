// Mesmas versões do Trombadário: já estão no cache do Gradle desta máquina e o
// par Kotlin 1.9.24 + compilador Compose 1.5.14 é o da tabela oficial.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}
