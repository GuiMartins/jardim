import groovy.json.JsonGenerator
import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Catálogo extra, de fora do repositório, só para testar volume sem commitar
// nada: ./gradlew assembleDebug -PcatalogoExtra=<pasta que contém catalogo/*.json>
val catalogoExtra: File? = providers.gradleProperty("catalogoExtra").orNull?.let(::file)

android {
    namespace = "com.jardim"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jardim"
        minSdk = 26
        targetSdk = 34
        // Os textos do app estão em values/ (português). Sem o filtro, o APK leva as
        // traduções das bibliotecas (Compose, Material, AndroidX) em 86 idiomas.
        resourceConfigurations += listOf("pt", "pt-rBR")
        versionCode = 2
        versionName = "0.1.1"
    }

    buildTypes {
        release {
            // É o release que vai para o celular: medido no Fold, abre em 124 ms
            // contra 428 ms do debug, e digita a 5-6 ms por quadro contra 18 ms.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Uso pessoal, instalado direto: a chave de debug desta máquina basta, e
            // é a mesma do APK debug, então um instala por cima do outro sem perder
            // o banco. Instalar de outra máquina exige desinstalar antes.
            signingConfig = signingConfigs.getByName("debug")
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
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    if (catalogoExtra != null) {
        sourceSets.getByName("main").assets.srcDir(catalogoExtra)
    }
}

val gerarIndiceCatalogo = tasks.register<GerarIndiceCatalogo>("gerarIndiceCatalogo") {
    plantas.from(fileTree("src/main/assets/catalogo") { include("*.json") })
    catalogoExtra?.let { plantas.from(fileTree(it.resolve("catalogo")) { include("*.json") }) }
    // Da outra frente do projeto (levantamento das plantas populares): se existir,
    // o índice ganha sinônimos, categoria e ranking de popularidade.
    levantamento.from(rootProject.file("dados/plantas-populares.json"))
}

androidComponents {
    onVariants { variante ->
        // O índice vira assets/catalogo/indice.json no APK, ao lado dos JSONs de espécie.
        variante.sources.assets?.addGeneratedSourceDirectory(gerarIndiceCatalogo, GerarIndiceCatalogo::saida)
    }
}

ksp {
    // O esquema de cada versão do banco fica versionado em app/schemas: é o que
    // permite escrever (e testar) a migração quando uma tabela mudar.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":motor"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Pinado em 2.9.1 como no Trombadário: a partir da 2.10 o AAR exige
    // compileSdk 35 + AGP 8.6+.
    implementation("androidx.work:work-runtime:2.9.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Mesma versão do fatura-app, já no cache desta máquina.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

/**
 * Gera o índice do catálogo (motor/.../ItemCatalogo): o que a lista e a busca
 * precisam de cada espécie, num arquivo só. Com 1000 plantas, o app lê uns
 * 200 KB em vez de 1000 JSONs inteiros. Roda a cada build em que um JSON do
 * catálogo (ou o levantamento) mudou; o arquivo gerado não vai para o git.
 */
abstract class GerarIndiceCatalogo : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val plantas: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val levantamento: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val saida: DirectoryProperty

    @TaskAction
    fun gerar() {
        val leitor = JsonSlurper()
        val doLevantamento: Map<String?, Map<*, *>> = levantamento.files.filter { it.exists() }
            .flatMap { arquivo -> ((leitor.parse(arquivo, "UTF-8") as Map<*, *>)["plantas"] as List<*>).map { it as Map<*, *> } }
            .associateBy { it["arquivo_catalogo"] as String? }

        val itens = plantas.files.sortedBy { it.name }.map { arquivo ->
            val planta = (leitor.parse(arquivo, "UTF-8") as Map<*, *>)["planta"] as Map<*, *>
            val extra = doLevantamento[arquivo.name]
            val nome = planta["nome_comum"] as String
            // O nome do levantamento também busca, se for diferente do da espécie.
            val outros = (listOfNotNull(extra?.get("nome_comum") as String?) + (extra?.get("outros_nomes") as List<*>? ?: emptyList<Any>()))
                .map { it.toString() }
                .filter { it != nome }
                .distinct()
            linkedMapOf(
                "arquivo" to arquivo.name,
                "nome_comum" to nome,
                "nome_cientifico" to planta["nome_cientifico"],
                "outros_nomes" to outros,
                "categoria" to extra?.get("categoria"),
                "posicao" to extra?.get("posicao"),
                "dificuldade" to planta["dificuldade"],
                "tipo_ciclo" to planta["tipo_ciclo"],
                "duracao_ciclo_dias" to planta["duracao_ciclo_dias"],
                "meses_plantio" to planta["meses_plantio"],
            )
        }

        val destino = saida.get().file("catalogo/indice.json").asFile
        destino.parentFile.mkdirs()
        destino.writeText(JsonGenerator.Options().disableUnicodeEscaping().build().toJson(itens), Charsets.UTF_8)
        logger.lifecycle("Índice do catálogo: ${itens.size} plantas, ${destino.length() / 1024} KB")
    }
}
