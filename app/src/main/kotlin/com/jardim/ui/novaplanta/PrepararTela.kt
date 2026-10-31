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
import androidx.compose.runtime.LaunchedEffect
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
import com.jardim.motor.agenda.INSUMOS_DE_DRENAGEM
import com.jardim.motor.agenda.Insumo
import com.jardim.motor.agenda.ItemPreparo
import com.jardim.motor.agenda.Receita
import com.jardim.motor.agenda.faltaNoSubstrato
import com.jardim.motor.agenda.resolvidosPelaDespensa
import com.jardim.motor.agenda.Local
import com.jardim.motor.agenda.foraDeEpoca
import com.jardim.motor.agenda.itensDePreparo
import com.jardim.motor.agenda.proximoInicioDaEpoca
import com.jardim.motor.modelo.FormaInicio
import com.jardim.ui.EscolherLocal
import com.jardim.ui.faltaDoSubstrato
import com.jardim.ui.inicioDe
import com.jardim.ui.nomeDoInsumo
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
    aoAbrirDespensa: () -> Unit,
    viewModel: NovaPlantaViewModel = viewModel(factory = NovaPlantaViewModel.Factory),
) {
    val especie by produceState<Especie?>(null, arquivo) { value = viewModel.carregar(arquivo) }
    val substrato by produceState<List<Receita>?>(null, arquivo) { value = viewModel.substrato(arquivo) }
    val despensa by viewModel.despensa.collectAsStateWithLifecycle()
    val calendarioVale by viewModel.calendarioVale.collectAsStateWithLifecycle()
    val planejado by remember(planejadoId) { viewModel.planejado(planejadoId ?: -1) }.collectAsStateWithLifecycle(null)
    // Com planejamento, espera o banco, para as caixas não piscarem vazias.
    if (planejadoId != null && planejado == null) return
    val tenho = despensa ?: return
    especie?.let {
        PrepararConteudo(it, planejado, substrato, tenho, calendarioVale, viewModel, aoVoltar, aoPlantarAgora, aoSair, aoAbrirDespensa)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrepararConteudo(
    especie: Especie,
    planejado: Planejado?,
    substrato: List<Receita>?,
    tenho: Set<Insumo>,
    calendarioVale: Boolean,
    viewModel: NovaPlantaViewModel,
    aoVoltar: () -> Unit,
    aoPlantarAgora: (local: Local?) -> Unit,
    aoSair: () -> Unit,
    aoAbrirDespensa: () -> Unit,
) {
    val planta = especie.cultivo.planta
    val itens = remember(especie) { itensDePreparo(especie.cultivo) }
    // Sem planejamento, começa desmarcado o que a despensa não resolve: quem
    // abre o preparo ainda não conferiu nada.
    var faltamAntesDeSalvar by rememberSaveable { mutableStateOf(itens.toSet()) }
    var localAntesDeSalvar by rememberSaveable { mutableStateOf<Local?>(null) }
    // Voltando da despensa com um insumo novo, o substrato se marca sozinho;
    // desmarcar na despensa não desmarca aqui (Despensa.mudar faz igual).
    LaunchedEffect(tenho, substrato) {
        faltamAntesDeSalvar = faltamAntesDeSalvar - resolvidosPelaDespensa(substrato, especie.cultivo.requisitosIniciais.camadaDrenagem, tenho)
    }
    // Salva, a planta lê do banco: a despensa pode ter mudado o que falta enquanto
    // a tela estava por baixo, e um estado local velho desfaria isso no próximo toque.
    val faltam = planejado?.faltam ?: faltamAntesDeSalvar
    val local = if (planejado != null) planejado.local else localAntesDeSalvar
    var desistindo by remember { mutableStateOf(false) }
    var salvando by remember { mutableStateOf(false) }
    val hoje = remember { LocalDate.now() }
    val verbo = verboInicio(planta.formaInicio)

    fun mudar(novoFaltam: Set<ItemPreparo> = faltam, novoLocal: Local? = local) {
        if (planejado != null) {
            viewModel.mudarPreparo(planejado.id, novoFaltam, novoLocal)
        } else {
            faltamAntesDeSalvar = novoFaltam
            localAntesDeSalvar = novoLocal
        }
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
                    val naDespensa = naDespensa(item, substrato, tenho)
                    val tem = item !in faltam
                    ListItem(
                        leadingContent = { Checkbox(checked = tem, onCheckedChange = null) },
                        headlineContent = { Text(titulo, style = MaterialTheme.typography.titleSmall) },
                        supportingContent = if (detalhe == null && naDespensa == null) null else {
                            {
                                Column {
                                    detalhe?.let { Text(it) }
                                    naDespensa?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.toggleable(value = tem, role = Role.Checkbox) { marcado ->
                            mudar(novoFaltam = if (marcado) faltam - item else faltam + item)
                        },
                    )
                }
            }
            TextButton(onClick = aoAbrirDespensa) { Text("Minha despensa") }

            Column {
                Text("Onde ela vai ficar?", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Dá para decidir na hora de $verbo. Serve para avisar de geada, calor forte, vento e chuva.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                EscolherLocal(local, aoEscolher = { mudar(novoLocal = it) })
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

/** O que a despensa diz do item: "Na despensa: tem tudo", "Falta na despensa: perlita". */
private fun naDespensa(item: ItemPreparo, substrato: List<Receita>?, tenho: Set<Insumo>): String? = when (item) {
    ItemPreparo.SUBSTRATO -> faltaNoSubstrato(substrato, tenho)?.let { falta ->
        if (falta.isEmpty()) "Na despensa: tem tudo." else "Falta na despensa: ${faltaDoSubstrato(falta)}."
    }
    ItemPreparo.DRENAGEM -> INSUMOS_DE_DRENAGEM.firstOrNull { it in tenho }?.let { "Na despensa: ${nomeDoInsumo(it).lowercase()}." }
    else -> null
}

/** Título e explicação de cada item, tirados do JSON da espécie. */
private fun textoDoItem(item: ItemPreparo, especie: Especie): Pair<String, String?> {
    val planta = especie.cultivo.planta
    val r = especie.cultivo.requisitosIniciais
    return when (item) {
        ItemPreparo.INICIO -> inicioDe(planta.formaInicio, planta.nomeComum) to when (planta.formaInicio) {
            FormaInicio.ESTACA -> "Um galho cortado de uma planta adulta."
            FormaInicio.PLANTA_ADULTA -> "Comprada pronta, para ir para o vaso."
            else -> null
        }
        // O girassol termina a frase com ponto; quase todo o resto do catálogo, não.
        ItemPreparo.VASO -> "Vaso de ${numero(r.volumeMinimoLitros)} L ou mais" to r.tipoVasoOuSolo.trimEnd().let { if (it.endsWith('.')) it else "$it." }
        ItemPreparo.SUBSTRATO -> "Substrato" to r.substratoRecomendado
        ItemPreparo.DRENAGEM -> "Material de drenagem" to "Argila expandida, brita ou pedrisco, para a camada no fundo do vaso."
        ItemPreparo.LUGAR -> "Um lugar com a luz certa" to luzDoDia(r)
    }
}
