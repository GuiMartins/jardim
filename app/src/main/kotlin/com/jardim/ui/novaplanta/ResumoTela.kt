package com.jardim.ui.novaplanta

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jardim.dados.Especie
import com.jardim.motor.agenda.foraDeEpoca
import com.jardim.motor.agenda.proximoInicioDaEpoca
import com.jardim.motor.modelo.FormaInicio
import com.jardim.ui.feitoInicio
import com.jardim.ui.luzDoDia
import com.jardim.ui.meses
import com.jardim.ui.mesPorExtenso
import com.jardim.ui.numero
import com.jardim.ui.resumo
import com.jardim.ui.verboInicio
import java.time.LocalDate

/**
 * A primeira tela depois da busca: o que é a planta e em que pé a pessoa
 * está. "Já semeei" vai direto para os dados do plantio, como antes; "Vou
 * semear" passa pelo preparo, que pode virar um planejamento.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResumoTela(
    arquivo: String,
    aoVoltar: () -> Unit,
    aoJaComecou: () -> Unit,
    aoPreparar: () -> Unit,
    viewModel: NovaPlantaViewModel = viewModel(factory = NovaPlantaViewModel.Factory),
) {
    // O JSON inteiro da espécie só é lido agora, fora da main thread.
    val especie by produceState<Especie?>(null, arquivo) { value = viewModel.carregar(arquivo) }
    val calendarioVale by viewModel.calendarioVale.collectAsStateWithLifecycle()
    especie?.let { ResumoConteudo(it, calendarioVale, aoVoltar, aoJaComecou, aoPreparar) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResumoConteudo(
    especie: Especie,
    calendarioVale: Boolean,
    aoVoltar: () -> Unit,
    aoJaComecou: () -> Unit,
    aoPreparar: () -> Unit,
) {
    val planta = especie.cultivo.planta
    val requisitos = especie.cultivo.requisitosIniciais
    val hoje = remember { LocalDate.now() }
    val verbo = verboInicio(planta.formaInicio)

    Scaffold(
        topBar = {
            TopAppBar(
                // A barra tem altura fixa: um nome em duas linhas sairia cortado ao meio.
                title = { Text(planta.nomeComum, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = aoVoltar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = aoPreparar, modifier = Modifier.weight(1f)) {
                        Text("Vou $verbo")
                    }
                    Button(onClick = aoJaComecou, modifier = Modifier.weight(1f)) {
                        Text("Já ${feitoInicio(planta.formaInicio).lowercase()}")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                planta.nomeCientifico,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(planta.descricao, style = MaterialTheme.typography.bodyLarge)

            val linhas = buildList {
                add("🌱" to "${resumo(planta)} · começa por ${inicio(planta.formaInicio)}")
                add("☀️" to luzDoDia(requisitos))
                add("🪴" to "Vaso de ${numero(requisitos.volumeMinimoLitros)} L ou mais por planta.")
                add("🌡️" to "Sofre abaixo de ${planta.temperaturaMinimaC} °C e pede cuidado acima de ${planta.temperaturaMaximaC} °C.")
                // Os meses do catálogo são do Centro-Sul: fora dele, não dizem nada.
                if (calendarioVale) add("🗓️" to epoca(planta.mesesPlantio, hoje))
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                linhas.forEach { (icone, texto) ->
                    ListItem(
                        leadingContent = { Text(icone, style = MaterialTheme.typography.titleMedium) },
                        headlineContent = { Text(texto) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    )
                }
            }
        }
    }
}

private fun inicio(forma: FormaInicio): String = when (forma) {
    FormaInicio.SEMENTE -> "semente"
    FormaInicio.MUDA -> "muda"
    FormaInicio.ESTACA -> "estaca"
    FormaInicio.BULBO -> "bulbo"
    FormaInicio.PLANTA_ADULTA -> "planta já formada"
}

/** "Época de plantio: set, out... Agora é a época." ou "... A próxima começa em setembro." */
private fun epoca(mesesPlantio: List<Int>, hoje: LocalDate): String {
    if (mesesPlantio.size == 12) return "Pode começar em qualquer mês."
    val quando = if (!foraDeEpoca(mesesPlantio, hoje)) {
        "Agora é a época."
    } else {
        proximoInicioDaEpoca(mesesPlantio, hoje)?.let { "A próxima começa em ${mesPorExtenso(it)}." } ?: ""
    }
    return "Época de plantio: ${meses(mesesPlantio)}. $quando".trim()
}
