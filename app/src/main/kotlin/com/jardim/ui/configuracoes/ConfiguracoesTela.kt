package com.jardim.ui.configuracoes

import com.jardim.motor.tempo.noCentroSul
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jardim.JardimApp
import com.jardim.avisos.Agendador
import com.jardim.avisos.Preferencias
import com.jardim.motor.agenda.ModoAviso
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class ConfiguracoesViewModel(
    private val preferencias: Preferencias,
    private val agendador: Agendador,
) : ViewModel() {

    val horarios = preferencias.horarios.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val cidade = preferencias.cidade.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun mudar(modo: ModoAviso, horario: LocalTime) {
        viewModelScope.launch {
            preferencias.mudar(modo, horario)
            agendador.reagendar(modo)
        }
    }

    fun testar() = agendador.testarAgora()

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as JardimApp
                ConfiguracoesViewModel(app.preferencias, app.agendador)
            }
        }
    }
}

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfiguracoesTela(
    aoVoltar: () -> Unit,
    aoEscolherCidade: () -> Unit,
    aoAbrirDespensa: () -> Unit,
    viewModel: ConfiguracoesViewModel = viewModel(factory = ConfiguracoesViewModel.Factory),
) {
    val horarios by viewModel.horarios.collectAsStateWithLifecycle()
    val cidade by viewModel.cidade.collectAsStateWithLifecycle()
    var editando by remember { mutableStateOf<ModoAviso?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val escopo = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configurações") },
                navigationIcon = {
                    IconButton(onClick = aoVoltar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val atuais = horarios ?: return@Scaffold
        Column(Modifier.padding(padding)) {
            ListItem(
                headlineContent = { Text("Resumo do dia") },
                supportingContent = { Text("Tudo que vence hoje ou está atrasado, em cada planta") },
                trailingContent = { Text(atuais.getValue(ModoAviso.RESUMO).format(hhmm), style = MaterialTheme.typography.titleMedium) },
                modifier = Modifier.clickable { editando = ModoAviso.RESUMO },
            )
            ListItem(
                headlineContent = { Text("Lembrete da noite") },
                supportingContent = { Text("Só as tarefas críticas que ainda não foram feitas") },
                trailingContent = { Text(atuais.getValue(ModoAviso.NOITE).format(hhmm), style = MaterialTheme.typography.titleMedium) },
                modifier = Modifier.clickable { editando = ModoAviso.NOITE },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Testar agora") },
                supportingContent = { Text("Manda o resumo de hoje neste instante") },
                modifier = Modifier.clickable {
                    viewModel.testar()
                    escopo.launch { snackbar.showSnackbar("Resumo enviado; confira a barra de notificações.") }
                },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Cidade da previsão do tempo") },
                supportingContent = {
                    val escolhida = cidade
                    Text(
                        when {
                            escolhida == null -> "Nenhuma: sem avisos de geada, calor, vento e chuva"
                            noCentroSul(escolhida) -> escolhida.descricao
                            else -> "${escolhida.descricao}. Fora do Centro-Sul, para o qual o catálogo foi pensado: o app não avisa \"fora de época\"."
                        },
                    )
                },
                trailingContent = { Text(if (cidade == null) "Escolher" else "Trocar", color = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable(onClick = aoEscolherCidade),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Minha despensa") },
                supportingContent = { Text("Terra, húmus, perlita e o que mais tiver em casa, para o preparo e a lista de compras") },
                modifier = Modifier.clickable(onClick = aoAbrirDespensa),
            )
        }

        editando?.let { modo ->
            EscolherHorario(
                inicial = atuais.getValue(modo),
                aoConfirmar = { viewModel.mudar(modo, it) },
                aoFechar = { editando = null },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EscolherHorario(inicial: LocalTime, aoConfirmar: (LocalTime) -> Unit, aoFechar: () -> Unit) {
    val estado = rememberTimePickerState(inicial.hour, inicial.minute, is24Hour = true)
    BasicAlertDialog(onDismissRequest = aoFechar) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(estado)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = aoFechar) { Text("Cancelar") }
                    TextButton(onClick = {
                        aoConfirmar(LocalTime.of(estado.hour, estado.minute))
                        aoFechar()
                    }) { Text("OK") }
                }
            }
        }
    }
}
