package com.jardim.ui.cidade

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jardim.JardimApp
import com.jardim.motor.tempo.Cidade
import com.jardim.tempo.Previsoes
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface ResultadoCidades {
    data object Nada : ResultadoCidades

    data object Buscando : ResultadoCidades

    data class Achou(val cidades: List<Cidade>) : ResultadoCidades

    data class Erro(val mensagem: String) : ResultadoCidades
}

class CidadeViewModel(private val previsoes: Previsoes) : ViewModel() {

    private val consulta = MutableStateFlow("")

    /** Só busca depois de uma pausa na digitação: a busca é na rede, não no índice local. */
    @OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val cidades: StateFlow<ResultadoCidades> = consulta
        .debounce(400)
        .mapLatest { nome ->
            if (nome.trim().length < 3) {
                ResultadoCidades.Nada
            } else {
                try {
                    ResultadoCidades.Achou(previsoes.buscar(nome))
                } catch (e: IOException) {
                    ResultadoCidades.Erro("Sem internet: não deu para buscar a cidade.")
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResultadoCidades.Nada)

    private val _localizando = MutableStateFlow<ResultadoCidades>(ResultadoCidades.Nada)
    val localizando: StateFlow<ResultadoCidades> = _localizando

    fun buscar(nome: String) {
        consulta.value = nome
    }

    fun localizar(depois: (Cidade) -> Unit) {
        viewModelScope.launch {
            _localizando.value = ResultadoCidades.Buscando
            val cidade = previsoes.cidadeAqui()
            if (cidade == null) {
                _localizando.value = ResultadoCidades.Erro("Não achei sua localização. Confira se ela está ligada, ou digite a cidade.")
            } else {
                _localizando.value = ResultadoCidades.Nada
                escolher(cidade, depois)
            }
        }
    }

    /** Grava no viewModelScope: sair da tela logo depois não perde a escolha. */
    fun escolher(cidade: Cidade, depois: (Cidade) -> Unit) {
        viewModelScope.launch {
            previsoes.escolher(cidade)
        }
        depois(cidade)
    }

    fun localizacaoNegada() {
        _localizando.value = ResultadoCidades.Erro("Sem a permissão de localização. Digite a cidade abaixo.")
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { CidadeViewModel((this[APPLICATION_KEY] as JardimApp).previsoes) }
        }
    }
}

/**
 * "Usar minha localização" (aproximada, uma vez só) ou a busca pelo nome. A
 * previsão só precisa da cidade: o app não acompanha onde a pessoa está.
 */
@Composable
fun EscolherCidade(
    aoEscolher: (Cidade) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CidadeViewModel = viewModel(factory = CidadeViewModel.Factory),
) {
    val cidades by viewModel.cidades.collectAsStateWithLifecycle()
    val localizando by viewModel.localizando.collectAsStateWithLifecycle()
    var texto by rememberSaveable { mutableStateOf("") }
    val pedirLocalizacao = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { concedida ->
        if (concedida) viewModel.localizar(aoEscolher) else viewModel.localizacaoNegada()
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(
            onClick = { pedirLocalizacao.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
            enabled = localizando != ResultadoCidades.Buscando,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (localizando == ResultadoCidades.Buscando) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Procurando…")
            } else {
                Text("📍 Usar minha localização")
            }
        }
        (localizando as? ResultadoCidades.Erro)?.let { Text(it.mensagem, color = MaterialTheme.colorScheme.error) }

        OutlinedTextField(
            value = texto,
            onValueChange = {
                texto = it
                viewModel.buscar(it)
            },
            label = { Text("Ou digite a cidade") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        when (val r = cidades) {
            ResultadoCidades.Nada -> Unit
            ResultadoCidades.Buscando -> Unit
            is ResultadoCidades.Erro -> Text(r.mensagem, color = MaterialTheme.colorScheme.error)
            is ResultadoCidades.Achou -> {
                if (r.cidades.isEmpty()) {
                    Text("Nenhuma cidade com esse nome.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column {
                    r.cidades.forEach { cidade ->
                        ListItem(
                            headlineContent = { Text(cidade.nome) },
                            supportingContent = {
                                Text(listOfNotNull(cidade.estado, cidade.pais?.let(::nomeDoPais)).joinToString(", "))
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { viewModel.escolher(cidade, aoEscolher) },
                        )
                    }
                }
            }
        }
    }
}

private fun nomeDoPais(codigo: String): String =
    java.util.Locale("", codigo).getDisplayCountry(java.util.Locale("pt", "BR")).ifEmpty { codigo }

/** A tela aberta por Configurações para trocar a cidade. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CidadeTela(aoVoltar: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sua cidade") },
                navigationIcon = {
                    IconButton(onClick = aoVoltar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                "A previsão dela avisa de geada, calor forte, vento e chuva nas plantas que ficam fora de casa.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            EscolherCidade(aoEscolher = { aoVoltar() })
        }
    }
}
