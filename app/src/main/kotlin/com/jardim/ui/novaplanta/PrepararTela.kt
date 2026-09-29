package com.jardim.ui.novaplanta

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jardim.dados.Especie
import com.jardim.dados.Planejado
import com.jardim.motor.agenda.ItemPreparo
import com.jardim.motor.agenda.Local
import com.jardim.motor.agenda.foraDeEpoca
import com.jardim.motor.agenda.itensDePreparo
import com.jardim.motor.agenda.proximoInicioDaEpoca
import com.jardim.motor.modelo.FormaInicio
import com.jardim.ui.EscolherLocal
import com.jardim.ui.luzDoDia
import com.jardim.ui.meses
import com.jardim.ui.mesPorExtenso
import com.jardim.ui.numero
import com.jardim.ui.verboInicio
import java.time.LocalDate

/**
 * "Do que você precisa": o que juntar antes de plantar, com uma caixa por
 * item. Sem [planejadoId], nada fica gravado até "Salvar para depois"
 * (decisão de 2026-09-28: salvar é um gesto explícito, não efeito de marcar
 * uma caixa). Com ele, a planta já está em "Para plantar" e cada toque grava.
 */
@Composable
fun PrepararTela(
    arquivo: String,
    planejadoId: Long?,
    aoVoltar: () -> Unit,
    aoPlantarAgora: (local: Local?) -> Unit,
    aoSair: () -> Unit,
    viewModel: NovaPlantaViewModel = viewModel(factory = NovaPlantaViewModel.Factory),
) {
    val especie by produceState<Especie?>(null, arquivo) { value = viewModel.carregar(arquivo) }
    val calendarioVale by viewModel.calendarioVale.collectAsStateWithLifecycle()
    val planejado by remember(planejadoId) { viewModel.planejado(planejadoId ?: -1) }.collectAsStateWithLifecycle(null)
    // Com planejamento, espera o banco, para as caixas não piscarem vazias.
    if (planejadoId != null && planejado == null) return
    especie?.let {
        PrepararConteudo(it, planejado, calendarioVale, viewModel, aoVoltar, aoPlantarAgora, aoSair)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrepararConteudo(
    especie: Especie,
    planejado: Planejado?,
    calendarioVale: Boolean,
    viewModel: NovaPlantaViewModel,
    aoVoltar: () -> Unit,
    aoPlantarAgora: (local: Local?) -> Unit,
    aoSair: () -> Unit,
) {
    val planta = especie.cultivo.planta
    val itens = remember(especie) { itensDePreparo(especie.cultivo) }
    // Sem planejamento, começa tudo desmarcado: quem abre o preparo ainda não conferiu nada.
    var faltam by rememberSaveable { mutableStateOf(planejado?.faltam ?: itens.toSet()) }
    var local by rememberSaveable { mutableStateOf(planejado?.local) }
    var desistindo by remember { mutableStateOf(false) }
    var salvando by remember { mutableStateOf(false) }
    val hoje = remember { LocalDate.now() }
    val verbo = verboInicio(planta.formaInicio)

    fun mudou() {
        planejado?.let { viewModel.mudarPreparo(it.id, faltam, local) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(planta.nomeComum, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = aoVoltar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
                },
                actions = {
                    if (planejado != null) {
                        IconButton(onClick = { desistindo = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Tirar da lista")
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (planejado == null) {
                        OutlinedButton(
                            onClick = {
                                salvando = true
                                viewModel.planejar(especie, faltam, local, aoSair)
                            },
                            enabled = !salvando,
                            modifier = Modifier.weight(1f),
                        ) { Text("Salvar para depois") }
                    }
                    Button(onClick = { aoPlantarAgora(local) }, enabled = !salvando, modifier = Modifier.weight(1f)) {
                        Text("${verbo.replaceFirstChar { it.uppercase() }} agora")
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
            if (calendarioVale && foraDeEpoca(planta.mesesPlantio, hoje)) {
                val proxima = proximoInicioDaEpoca(planta.mesesPlantio, hoje)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Text(
                        "Fora da época ideal (${meses(planta.mesesPlantio)})." +
                            (
                                proxima?.let {
                                    val quando = "o app avisa em 1º de ${mesPorExtenso(it)}."
                                    if (planejado == null) " Salve para depois e $quando" else " Ela está na lista: $quando"
                                } ?: ""
                            ),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            Column {
                Text("Do que você precisa", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Marque o que já tem. O que ficar sem marca aparece em Para plantar, na tela Hoje.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                itens.forEach { item ->
                    val (titulo, detalhe) = textoDoItem(item, especie)
                    val tem = item !in faltam
                    ListItem(
                        leadingContent = { Checkbox(checked = tem, onCheckedChange = null) },
                        headlineContent = { Text(titulo, style = MaterialTheme.typography.titleSmall) },
                        supportingContent = detalhe?.let { { Text(it) } },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.toggleable(value = tem, role = Role.Checkbox) { marcado ->
                            faltam = if (marcado) faltam - item else faltam + item
                            mudou()
                        },
                    )
                }
            }

            Column {
                Text("Onde ela vai ficar?", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Dá para decidir na hora de $verbo. Serve para avisar de geada, calor forte, vento e chuva.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                EscolherLocal(local, aoEscolher = {
                    local = it
                    mudou()
                })
            }
        }
    }

    if (desistindo && planejado != null) {
        AlertDialog(
            onDismissRequest = { desistindo = false },
            title = { Text("Tirar ${planta.nomeComum} da lista?") },
            text = { Text("Ela sai de Para plantar e não avisa mais da época.") },
            confirmButton = {
                TextButton(onClick = {
                    desistindo = false
                    viewModel.desistir(planejado.id, aoSair)
                }) { Text("Tirar") }
            },
            dismissButton = { TextButton(onClick = { desistindo = false }) { Text("Cancelar") } },
        )
    }
}

/** Título e explicação de cada item, tirados do JSON da espécie. */
private fun textoDoItem(item: ItemPreparo, especie: Especie): Pair<String, String?> {
    val planta = especie.cultivo.planta
    val r = especie.cultivo.requisitosIniciais
    val nome = planta.nomeComum.replaceFirstChar { it.lowercase() }
    return when (item) {
        ItemPreparo.INICIO -> when (planta.formaInicio) {
            FormaInicio.SEMENTE -> "Sementes de $nome" to null
            FormaInicio.MUDA -> "Muda de $nome" to null
            FormaInicio.ESTACA -> "Estaca de $nome" to "Um galho cortado de uma planta adulta."
            FormaInicio.BULBO -> "Bulbo de $nome" to null
            FormaInicio.PLANTA_ADULTA -> "${planta.nomeComum} já formada" to "Comprada pronta, para ir para o vaso."
        }
        // O girassol termina a frase com ponto; quase todo o resto do catálogo, não.
        ItemPreparo.VASO -> "Vaso de ${numero(r.volumeMinimoLitros)} L ou mais" to r.tipoVasoOuSolo.trimEnd().let { if (it.endsWith('.')) it else "$it." }
        ItemPreparo.SUBSTRATO -> "Substrato" to r.substratoRecomendado
        ItemPreparo.DRENAGEM -> "Material de drenagem" to "Argila expandida, brita ou pedrisco, para a camada no fundo do vaso."
        ItemPreparo.LUGAR -> "Um lugar com a luz certa" to luzDoDia(r)
    }
}
