package com.jardim.ui.hoje

import androidx.compose.ui.text.style.TextOverflow
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import android.net.Uri
import com.jardim.atualizacao.EstadoInstalacao
import com.jardim.motor.atualizacao.Atualizacao
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jardim.motor.agenda.Pendencia
import com.jardim.motor.agenda.StatusAcao
import com.jardim.motor.modelo.TipoTarefa
import com.jardim.ui.CHAVE_NOVA_PLANTA
import com.jardim.ui.atraso
import com.jardim.ui.chaveDoPlantio
import com.jardim.ui.container
import com.jardim.ui.dataLonga
import com.jardim.ui.icone
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojeTela(
    aoAdicionar: () -> Unit,
    aoAbrirPlanejado: (arquivo: String, planejadoId: Long) -> Unit,
    aoConfigurar: () -> Unit,
    aoAbrirCompras: () -> Unit,
    aoAbrirPlanta: (plantioId: Long) -> Unit,
    viewModel: HojeViewModel = viewModel(factory = HojeViewModel.Factory),
) {
    val cartoes by viewModel.cartoes.collectAsStateWithLifecycle()
    val planejados by viewModel.planejados.collectAsStateWithLifecycle()
    val hoje by viewModel.dataDeHoje.collectAsStateWithLifecycle()
    val tempo by viewModel.tempoDeHoje.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val contexto = LocalContext.current
    var notificacoesLigadas by remember { mutableStateOf(true) }
    val atualizacao by viewModel.atualizacao.collectAsStateWithLifecycle()
    val instalacao by viewModel.instalacao.collectAsStateWithLifecycle()
    val rolagem = rememberLazyListState()
    // A consulta ao GitHub responde com a lista já na tela, e a LazyColumn segura
    // a rolagem no primeiro cartão: sem isso, o aviso nascia escondido acima dele.
    LaunchedEffect(atualizacao != null) {
        if (atualizacao != null && rolagem.firstVisibleItemIndex <= 1) rolagem.scrollToItem(0)
    }

    // No resume: o dia pode ter virado, e a pessoa pode ter voltado das
    // configurações do Android depois de ligar as notificações.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.atualizarDia()
        notificacoesLigadas = viewModel.notificacoesLigadas()
        // Voltando das configurações com a permissão dada, o botão "Atualizar" volta.
        if (instalacao == EstadoInstalacao.PrecisaPermissao && contexto.packageManager.canRequestPackageInstalls()) {
            viewModel.limparErroDaAtualizacao()
        }
    }

    // collectLatest: marcar outra coisa troca a snackbar em vez de enfileirar.
    LaunchedEffect(Unit) {
        viewModel.acoesRegistradas.collectLatest { acao ->
            val resultado = snackbar.showSnackbar(acao.mensagem, "Desfazer", duration = SnackbarDuration.Short)
            if (resultado == SnackbarResult.ActionPerformed) viewModel.desfazer(acao.registroId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Hoje")
                        Text(
                            listOfNotNull(dataLonga(hoje), tempo).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = aoConfigurar) {
                        Icon(Icons.Filled.Settings, contentDescription = "Configurações")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = aoAdicionar,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Nova planta") },
                // Cresce e vira a tela de busca (container transform).
                modifier = Modifier.container(CHAVE_NOVA_PLANTA, FloatingActionButtonDefaults.extendedFabShape),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val lista = cartoes ?: return@Scaffold
        val paraPlantar = planejados ?: return@Scaffold
        if (lista.isEmpty() && paraPlantar.isEmpty()) {
            SemPlantas(aoAdicionar, Modifier.padding(padding))
        } else {
            LazyColumn(
                state = rolagem,
                modifier = Modifier.padding(padding),
                // Espaço embaixo para o último cartão não ficar atrás do botão.
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                atualizacao?.let { nova ->
                    item(key = "atualizacao") {
                        CartaoAtualizacao(
                            nova = nova,
                            instalada = viewModel.versaoInstalada,
                            estado = instalacao,
                            aoAtualizar = { viewModel.atualizar(nova) },
                            aoPermitir = {
                                contexto.startActivity(
                                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${contexto.packageName}")),
                                )
                            },
                        )
                    }
                }
                if (!notificacoesLigadas) {
                    item(key = "notificacoes") {
                        NotificacoesDesligadas(aoAtivar = {
                            contexto.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, contexto.packageName),
                            )
                        })
                    }
                }
                items(lista, key = { it.id }) { cartao ->
                    CartaoPlantioView(
                        cartao = cartao,
                        aoMarcar = { pendencia, status -> viewModel.marcar(cartao.id, pendencia, status) },
                        aoRecomecar = { viewModel.recomecar(cartao.id) },
                        aoEncerrar = { viewModel.encerrar(cartao.id) },
                        aoAbrir = { aoAbrirPlanta(cartao.id) },
                    )
                }
                // Embaixo das plantas: o Hoje é antes de tudo o que fazer hoje.
                if (paraPlantar.isNotEmpty()) {
                    item(key = "para-plantar") {
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Para plantar", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = aoAbrirCompras) { Text("Lista de compras") }
                        }
                    }
                    items(paraPlantar, key = { "planejado-${it.id}" }) { planejado ->
                        CartaoPlanejadoView(planejado, aoAbrir = { aoAbrirPlanejado(planejado.arquivo, planejado.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CartaoPlanejadoView(cartao: CartaoPlanejado, aoAbrir: () -> Unit) {
    OutlinedCard(onClick = aoAbrir, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(cartao.nome, style = MaterialTheme.typography.titleMedium) },
            supportingContent = {
                Column {
                    Text(cartao.preparo)
                    cartao.epoca?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun SemPlantas(aoAdicionar: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🌱", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(16.dp))
        Text("Nenhuma planta ainda", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Escolha uma planta, diga quando começou, e o app avisa o que fazer a cada dia.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = aoAdicionar) { Text("Escolher a primeira") }
    }
}

@Composable
private fun NotificacoesDesligadas(aoAtivar: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Notificações desligadas",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                "Sem elas o app não consegue lembrar da rega; só dá para ver as tarefas abrindo aqui.",
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = aoAtivar) { Text("Ativar") }
        }
    }
}

@Composable
private fun CartaoAtualizacao(
    nova: Atualizacao,
    instalada: String,
    estado: EstadoInstalacao,
    aoAtualizar: () -> Unit,
    aoPermitir: () -> Unit,
) {
    val cor = MaterialTheme.colorScheme.onPrimaryContainer
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text("Versão ${nova.versao} disponível", style = MaterialTheme.typography.titleMedium, color = cor)
            when (estado) {
                EstadoInstalacao.Parado -> Text("Você está na $instalada. O histórico das plantas fica como está.", color = cor)
                EstadoInstalacao.PrecisaPermissao ->
                    Text("O Android pede, uma vez só, que você libere o Jardim para instalar as próprias atualizações.", color = cor)
                is EstadoInstalacao.Baixando -> {
                    Text("Baixando…", color = cor)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { estado.fracao }, modifier = Modifier.fillMaxWidth())
                }
                EstadoInstalacao.Instalando -> Text("Instalando. O app fecha sozinho quando terminar; é só abrir de novo.", color = cor)
                is EstadoInstalacao.Erro -> Text(estado.mensagem, color = cor)
            }
            when (estado) {
                EstadoInstalacao.Parado, is EstadoInstalacao.Erro -> {
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = aoAtualizar) { Text(if (estado is EstadoInstalacao.Erro) "Tentar de novo" else "Atualizar") }
                }
                EstadoInstalacao.PrecisaPermissao -> {
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = aoPermitir) { Text("Liberar") }
                }
                else -> Unit
            }
        }
    }
}

private enum class Confirmacao { RECOMECAR, ENCERRAR }

@Composable
private fun CartaoPlantioView(
    cartao: CartaoPlantio,
    aoMarcar: (Pendencia, StatusAcao) -> Unit,
    aoRecomecar: () -> Unit,
    aoEncerrar: () -> Unit,
    aoAbrir: () -> Unit,
) {
    var confirmando by remember { mutableStateOf<Confirmacao?>(null) }
    val recomecoTexto = if (cartao.deSemente) "Ressemeei hoje" else "Replantei hoje"

    // O cartão cresce e vira a tela da planta (container transform).
    ElevatedCard(Modifier.fillMaxWidth().container(chaveDoPlantio(cartao.id), CardDefaults.elevatedShape)) {
        // O cabeçalho abre a linha do tempo da planta; o menu ao lado tem o próprio toque.
        Row(
            Modifier.clickable(onClick = aoAbrir).padding(start = 16.dp, top = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Sem o weight, um nome longo ocupa a linha toda e a seta some.
                    Text(cartao.apelido, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f, fill = false))
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Ver linha do tempo",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val partes = buildList {
                    if (cartao.apelido != cartao.nomeComum) add(cartao.nomeComum)
                    if (cartao.cicloEncerrado) add("ciclo encerrado") else add("Dia ${cartao.dia}")
                    if (!cartao.cicloEncerrado) cartao.fase?.let(::add)
                }
                Text(
                    partes.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MenuDoPlantio(
                recomecoTexto = recomecoTexto,
                aoRecomecar = { confirmando = Confirmacao.RECOMECAR },
                aoEncerrar = { confirmando = Confirmacao.ENCERRAR },
            )
        }

        when {
            cartao.cicloEncerrado -> Column(Modifier.padding(16.dp)) {
                Text("O ciclo terminou. Encerre o plantio para tirá-lo daqui; o histórico fica guardado.")
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = aoEncerrar) { Text("Encerrar plantio") }
            }
            cartao.pendencias.isEmpty() -> Text(
                "Nada para hoje 🌿",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Column(Modifier.padding(top = 8.dp)) {
                cartao.pendencias.forEachIndexed { i, pendencia ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    key(pendencia.chave) { PendenciaView(pendencia, aoMarcar) }
                }
            }
        }
    }

    when (confirmando) {
        Confirmacao.RECOMECAR -> Confirmar(
            titulo = "$recomecoTexto?",
            texto = "Hoje vira o dia 1 de ${cartao.apelido} e a agenda recomeça do zero. O histórico fica guardado.",
            acao = "Recomeçar",
            aoConfirmar = aoRecomecar,
            aoFechar = { confirmando = null },
        )
        Confirmacao.ENCERRAR -> Confirmar(
            titulo = "Encerrar ${cartao.apelido}?",
            texto = "A planta sai da tela Hoje e para de gerar tarefas. O histórico fica guardado.",
            acao = "Encerrar",
            aoConfirmar = aoEncerrar,
            aoFechar = { confirmando = null },
        )
        null -> Unit
    }
}

@Composable
private fun MenuDoPlantio(recomecoTexto: String, aoRecomecar: () -> Unit, aoEncerrar: () -> Unit) {
    var aberto by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { aberto = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Mais opções") }
        DropdownMenu(expanded = aberto, onDismissRequest = { aberto = false }) {
            DropdownMenuItem(text = { Text(recomecoTexto) }, onClick = { aberto = false; aoRecomecar() })
            DropdownMenuItem(text = { Text("Encerrar plantio") }, onClick = { aberto = false; aoEncerrar() })
        }
    }
}

@Composable
private fun Confirmar(titulo: String, texto: String, acao: String, aoConfirmar: () -> Unit, aoFechar: () -> Unit) {
    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text(titulo) },
        text = { Text(texto) },
        confirmButton = { TextButton(onClick = { aoConfirmar(); aoFechar() }) { Text(acao) } },
        dismissButton = { TextButton(onClick = aoFechar) { Text("Cancelar") } },
    )
}

@Composable
private fun PendenciaView(pendencia: Pendencia, aoMarcar: (Pendencia, StatusAcao) -> Unit) {
    // As instruções do girassol passam de 400 caracteres: ficam recolhidas até o toque.
    var aberta by rememberSaveable { mutableStateOf(false) }
    val atrasada = pendencia.diasDeAtraso > 0

    Column(
        Modifier
            .fillMaxWidth()
            .clickable { aberta = !aberta }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icone(pendencia), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(pendencia.titulo, style = MaterialTheme.typography.titleMedium)
                val situacao = atraso(pendencia.diasDeAtraso) + if (pendencia.critica) " · não deixe passar" else ""
                Text(
                    situacao,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        atrasada && pendencia.critica -> MaterialTheme.colorScheme.error
                        atrasada -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        AnimatedVisibility(aberta) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                pendencia.contexto?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
                }
                Text(pendencia.instrucoes, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            // Rega é condicional ("enfie o dedo; se estiver úmido, espere"):
            // conferir e não precisar também conta como cuidado feito.
            if (pendencia.tipo == TipoTarefa.REGA) {
                OutlinedButton(onClick = { aoMarcar(pendencia, StatusAcao.NAO_PRECISOU) }) { Text("Conferi, não precisou") }
            }
            FilledTonalButton(onClick = { aoMarcar(pendencia, StatusAcao.FEITA) }) { Text("Feito") }
        }
    }
}
