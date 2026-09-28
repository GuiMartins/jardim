package com.jardim.ui.planta

import com.jardim.ui.nomeDoLocal
import com.jardim.ui.EscolherLocal
import com.jardim.motor.agenda.Local
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.remember
import androidx.compose.material3.TextButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.AlertDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jardim.motor.agenda.StatusAcao
import com.jardim.motor.modelo.Praga
import com.jardim.ui.dataCurta
import com.jardim.ui.dataHora
import com.jardim.ui.diaDaPrevisao
import com.jardim.ui.icone
import com.jardim.ui.tipoPraga

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantaTela(
    aoVoltar: () -> Unit,
    viewModel: PlantaViewModel = viewModel(factory = PlantaViewModel.Factory),
) {
    val tela by viewModel.tela.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.atualizarDia() }
    LaunchedEffect(tela) { if (tela is TelaPlanta.Sumiu) aoVoltar() }

    val estado = (tela as? TelaPlanta.Pronta)?.estado
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(estado?.apelido.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            estado?.nomeCientifico.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = aoVoltar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
                },
            )
        },
    ) { padding ->
        if (estado == null) return@Scaffold
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item(key = "agora") { Agora(estado) }
            item(key = "local") { OndeFica(estado, aoMudar = viewModel::mudarLocal) }
            proximos(estado)
            fases(estado)
            problemas(estado.problemas)
            historico(estado.historico)
        }
    }
}

@Composable
private fun Secao(titulo: String, subtitulo: String? = null) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 8.dp)) {
        Text(titulo, style = MaterialTheme.typography.titleLarge)
        subtitulo?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Vazio(texto: String) {
    Text(
        texto,
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Onde o vaso fica e os limites de temperatura da espécie. Plantio de antes da
 * pergunta mostra as opções direto, porque sem resposta não há aviso do tempo.
 */
@Composable
private fun OndeFica(estado: EstadoPlanta, aoMudar: (Local) -> Unit) {
    var mudando by remember { mutableStateOf(false) }
    val local = estado.local
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (local == null) {
            Column(Modifier.padding(top = 16.dp)) {
                Text("Onde ela fica?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
                Text(
                    "Responda para o app avisar de geada, calor forte e vento só quando o tempo chega até ela.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                EscolherLocal(null, aoEscolher = aoMudar)
            }
        } else {
            val limites = "Sofre abaixo de ${estado.minimaC} °C e pede cuidado acima de ${estado.maximaC} °C."
            val semCidade = if (!estado.temCidade && local != Local.DENTRO_DE_CASA) {
                " Escolha sua cidade em Configurações para receber os avisos do tempo."
            } else {
                ""
            }
            ListItem(
                overlineContent = { Text("Onde fica") },
                headlineContent = { Text(nomeDoLocal(local)) },
                supportingContent = { Text(limites + semCidade) },
                trailingContent = { TextButton(onClick = { mudando = true }) { Text("Mudar") } },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
    if (mudando && local != null) {
        AlertDialog(
            onDismissRequest = { mudando = false },
            title = { Text("Onde ela fica?") },
            text = {
                EscolherLocal(local, aoEscolher = {
                    aoMudar(it)
                    mudando = false
                })
            },
            confirmButton = { TextButton(onClick = { mudando = false }) { Text("Fechar") } },
        )
    }
}

@Composable
private fun Agora(estado: EstadoPlanta) {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Dia ${estado.dia}", style = MaterialTheme.typography.displaySmall)
                estado.duracao?.let {
                    Text(
                        " de $it",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            estado.duracao?.let { duracao ->
                LinearProgressIndicator(
                    progress = { (estado.dia.toFloat() / duracao).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
            }
            if (estado.cicloEncerrado) {
                Text("O ciclo terminou. Encerre o plantio na tela Hoje quando quiser; o histórico fica guardado.")
            } else {
                estado.faseAtual?.let { fase ->
                    Text(fase.nome, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(fase.descricao, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun LazyListScope.proximos(estado: EstadoPlanta) {
    item(key = "proximos") { Secao("Próximos 7 dias", "Supondo que você faça tudo em dia") }
    if (estado.proximos.isEmpty()) {
        item(key = "proximos-vazio") { Vazio("Nada previsto para a próxima semana.") }
        return
    }
    items(estado.proximos, key = { "dia-${it.data}" }) { dia ->
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(
                "${diaDaPrevisao(dia.data, estado.hoje)} · dia ${dia.dia}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            dia.pendencias.forEach { p ->
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(icone(p), modifier = Modifier.width(32.dp))
                    Text(p.titulo, modifier = Modifier.weight(1f))
                    if (p.critica) {
                        Text("não deixe passar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.fases(estado: EstadoPlanta) {
    item(key = "fases") { Secao("Fases", "Toque numa fase para ver o que esperar dela") }
    itemsIndexed(estado.fases, key = { _, f -> "fase-${f.fase.faseId}" }) { i, item ->
        LinhaDaFase(item, primeira = i == 0, ultima = i == estado.fases.lastIndex)
    }
}

@Composable
private fun LinhaDaFase(item: FaseNaLinha, primeira: Boolean, ultima: Boolean) {
    var aberta by rememberSaveable { mutableStateOf(false) }
    val cores = MaterialTheme.colorScheme
    val linha = cores.outlineVariant
    val atual = item.situacao == Situacao.ATUAL

    // IntrinsicSize.Min deixa a coluna da linha do tempo com a altura do texto,
    // para o traço vertical ligar uma fase à outra.
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable { aberta = !aberta }
            .padding(horizontal = 16.dp),
    ) {
        Box(Modifier.width(20.dp).fillMaxHeight()) {
            if (!primeira) Box(Modifier.align(Alignment.TopCenter).width(2.dp).height(20.dp).background(linha))
            if (!ultima) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 20.dp).width(2.dp).fillMaxHeight().background(linha))
            }
            val bolinha = Modifier.align(Alignment.TopCenter).padding(top = 13.dp).size(14.dp)
            when (item.situacao) {
                Situacao.PASSADA -> Box(bolinha.background(cores.primary, CircleShape))
                Situacao.ATUAL -> Box(bolinha.background(cores.tertiary, CircleShape))
                Situacao.FUTURA -> Box(bolinha.background(cores.surface, CircleShape).border(2.dp, linha, CircleShape))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(
                item.fase.nome,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (atual) FontWeight.Bold else null,
                color = if (item.situacao == Situacao.FUTURA) cores.onSurfaceVariant else cores.onSurface,
            )
            val fimDia = item.fase.diaFim
            val periodo = if (item.fim == null || fimDia == null) {
                "a partir de ${dataCurta(item.inicio)} · dia ${item.fase.diaInicio} em diante"
            } else {
                "${dataCurta(item.inicio)} a ${dataCurta(item.fim)} · dias ${item.fase.diaInicio} a $fimDia"
            }
            Text(periodo, style = MaterialTheme.typography.bodySmall, color = cores.onSurfaceVariant)
            if (atual) Text("Você está aqui", style = MaterialTheme.typography.labelMedium, color = cores.tertiary)
            AnimatedVisibility(aberta) {
                Text(item.fase.descricao, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

private fun LazyListScope.problemas(problemas: List<Praga>) {
    item(key = "problemas") { Secao("Problemas comuns", "Pragas, doenças e distúrbios desta planta em vaso") }
    items(problemas, key = { "praga-${it.nome}" }) { praga -> Problema(praga) }
}

@Composable
private fun Problema(praga: Praga) {
    var aberto by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(
        onClick = { aberto = !aberto },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(praga.nome, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        tipoPraga(praga.tipo),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            AnimatedVisibility(aberto) {
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Sintomas", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(praga.sintomas, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("Prevenção e tratamento", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(praga.prevencaoTratamento, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun LazyListScope.historico(historico: List<ItemHistorico>) {
    item(key = "historico") { Secao("Histórico", "O que já foi feito, do mais recente ao mais antigo") }
    if (historico.isEmpty()) {
        item(key = "historico-vazio") { Vazio("Nada registrado ainda.") }
        return
    }
    items(historico, key = { "registro-${it.realizadoEm}-${it.titulo}" }) { item ->
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(item.icone, modifier = Modifier.width(32.dp))
            Column(Modifier.weight(1f)) {
                Text(item.titulo)
                val como = if (item.status == StatusAcao.FEITA) "Feito" else "Conferido, não precisou"
                Text(
                    "${dataHora(item.realizadoEm)} · $como",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
