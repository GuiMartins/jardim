package com.jardim.ui.novaplanta

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jardim.motor.agenda.foraDeEpoca
import com.jardim.motor.catalogo.ResultadoBusca
import com.jardim.ui.categoria
import com.jardim.ui.resumo
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogoTela(
    aoEscolher: (arquivo: String) -> Unit,
    aoVoltar: () -> Unit,
    viewModel: NovaPlantaViewModel = viewModel(factory = NovaPlantaViewModel.Factory),
) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()
    // O texto mora na tela, não no ViewModel: o campo precisa responder na hora
    // a cada letra, e a busca vai atrás.
    var consulta by rememberSaveable { mutableStateOf("") }
    val foco = remember { FocusRequester() }
    val teclado = LocalSoftwareKeyboardController.current
    val lista = rememberLazyListState()
    val hoje = remember { LocalDate.now() }

    // Teclado só na chegada: voltando da tela Plantar, a pessoa quer ver a lista.
    // O rememberSaveable sobrevive enquanto a busca está na pilha, por baixo.
    var jaFocou by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!jaFocou) {
            foco.requestFocus()
            jaFocou = true
        }
    }
    // Resultado novo começa do topo, senão o melhor resultado fica escondido.
    LaunchedEffect(consulta) { lista.scrollToItem(0) }
    // Rolar a lista é sinal de que a pessoa quer ver, não digitar.
    LaunchedEffect(lista.isScrollInProgress) { if (lista.isScrollInProgress) teclado?.hide() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nova planta") },
                navigationIcon = {
                    IconButton(onClick = aoVoltar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).imePadding()) {
            val pronto = estado as? EstadoBusca.Pronto
            OutlinedTextField(
                value = consulta,
                onValueChange = {
                    consulta = it
                    viewModel.buscar(it)
                },
                placeholder = { Text(pronto?.let { "Buscar entre ${it.total} plantas" } ?: "Buscar planta") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (consulta.isNotEmpty()) {
                        IconButton(onClick = {
                            consulta = ""
                            viewModel.buscar("")
                            foco.requestFocus()
                        }) { Icon(Icons.Filled.Clear, contentDescription = "Limpar") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { teclado?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(foco),
            )

            when {
                pronto == null -> Unit
                pronto.resultados.isEmpty() -> Text(
                    "Nenhuma planta com “${pronto.consulta.trim()}”.",
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(
                    state = lista,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(pronto.resultados, key = { it.item.arquivo }) { resultado ->
                        ItemDoCatalogo(resultado, hoje, onClick = { aoEscolher(resultado.item.arquivo) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemDoCatalogo(resultado: ResultadoBusca, hoje: LocalDate, onClick: () -> Unit) {
    val item = resultado.item
    val cores = MaterialTheme.colorScheme
    val destaque = SpanStyle(fontWeight = FontWeight.Bold, color = cores.primary)
    val fora = foraDeEpoca(item.mesesPlantio, hoje)

    ListItem(
        headlineContent = { Text(realcar(item.nomeComum, resultado.destaquesNome, destaque)) },
        supportingContent = {
            Column {
                // Casou por um sinônimo: mostra qual, senão a planta pareceria fora do lugar.
                resultado.outroNome?.let { outro ->
                    Text(
                        buildAnnotatedString {
                            append("também chamada de ")
                            append(realcar(outro, resultado.destaquesOutroNome, destaque))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    realcar(item.nomeCientifico, resultado.destaquesCientifico, destaque),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    // Há nomes de 100 letras, com sinônimos e cultivares: numa linha só,
                    // o trecho que casou na busca podia ficar escondido no "…".
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val detalhes = listOfNotNull(item.categoria?.let(::categoria), resumo(item.dificuldade, item.duracaoCicloDias))
                Text(
                    detalhes.joinToString(" · ") + if (fora) " · fora de época" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (fora) cores.tertiary else cores.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** O texto com as faixas que casaram na busca em negrito. */
private fun realcar(texto: String, faixas: List<IntRange>, estilo: SpanStyle): AnnotatedString {
    if (faixas.isEmpty()) return AnnotatedString(texto)
    return buildAnnotatedString {
        append(texto)
        faixas.forEach { addStyle(estilo, it.first, it.last + 1) }
    }
}
