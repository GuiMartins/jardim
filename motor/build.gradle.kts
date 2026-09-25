// Motor da agenda: Kotlin puro, sem Android. Tudo que decide "o que fazer hoje"
// mora aqui, pra os testes rodarem na JVM em segundos, sem emulador.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // api, não implementation: o app recebe PlantaCultivo (classe @Serializable)
    // do motor e precisa enxergar os tipos do kotlinx.serialization.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // O catálogo que vai no APK é validado pelos testes do motor: um JSON
    // quebrado em assets/ derruba o `gradlew test`, não o celular.
    systemProperty("catalogo.dir", rootProject.file("app/src/main/assets/catalogo").absolutePath)
}
