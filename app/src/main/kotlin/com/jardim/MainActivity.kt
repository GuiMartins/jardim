package com.jardim

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jardim.motor.leitura.LeitorCultivo
import com.jardim.motor.leitura.ResultadoLeitura

// Tela provisória da etapa 2: só prova que o APK enxerga o catálogo em assets/
// e o motor. A tela "Hoje" de verdade vem na etapa 3.
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val catalogo = lerCatalogo()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    LazyColumn(contentPadding = PaddingValues(vertical = 16.dp)) {
                        items(catalogo) { (arquivo, resultado) ->
                            when (resultado) {
                                is ResultadoLeitura.Ok -> ListItem(
                                    headlineContent = { Text(resultado.cultivo.planta.nomeComum) },
                                    supportingContent = {
                                        Text("${resultado.cultivo.tarefasAgendadas.size} tarefas · ${resultado.cultivo.planta.nomeCientifico}")
                                    },
                                )
                                is ResultadoLeitura.Invalido -> ListItem(
                                    headlineContent = { Text("$arquivo inválido") },
                                    supportingContent = { Text(resultado.problemas.joinToString("\n")) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun lerCatalogo(): List<Pair<String, ResultadoLeitura>> =
        assets.list("catalogo").orEmpty().sorted().map { arquivo ->
            val texto = assets.open("catalogo/$arquivo").bufferedReader().use { it.readText() }
            arquivo to LeitorCultivo.ler(texto)
        }
}
