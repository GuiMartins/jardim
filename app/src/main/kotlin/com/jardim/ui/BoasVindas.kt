package com.jardim.ui

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jardim.JardimApp
import com.jardim.avisos.Preferencias
import com.jardim.dados.JardimRepositorio
import com.jardim.motor.agenda.Local
import com.jardim.motor.tempo.Cidade
import com.jardim.motor.tempo.noCentroSul
import com.jardim.ui.cidade.EscolherCidade
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Um plantio de antes da pergunta "onde ela vai ficar?". */
data class PlantioSemLocal(val id: Long, val apelido: String)

class BoasVindasViewModel(
    private val preferencias: Preferencias,
    private val repositorio: JardimRepositorio,
) : ViewModel() {

    val cidade: StateFlow<Cidade?> = preferencias.cidade.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Guardados na primeira leitura: a planta respondida continua na lista, com
     * a resposta marcada, em vez de sumir debaixo do dedo.
     */
    val semLocal: StateFlow<List<PlantioSemLocal>?> =
        flow { emit(repositorio.plantiosAtivos.first().filter { it.local == null }.map { PlantioSemLocal(it.id, it.apelido) }) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val locais: StateFlow<Map<Long, Local?>> = repositorio.plantiosAtivos
        .map { plantios -> plantios.associate { it.id to it.local } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun mudarLocal(plantioId: Long, local: Local) {
        viewModelScope.launch { repositorio.mudarLocal(plantioId, local) }
    }

    fun terminar() {
        viewModelScope.launch { preferencias.marcarBoasVindas() }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as JardimApp
                BoasVindasViewModel(app.preferencias, app.repositorio)
            }
        }
    }
}

/**
 * Uma vez só: na primeira abertura, ou na primeira depois da versão que trouxe
 * os avisos do tempo. Explica antes de pedir cada permissão, porque o Android
 * só mostra o pedido duas vezes. Tudo é opcional: "Começar" funciona sem nada.
 */
@Composable
fun BoasVindas(
    aoTerminar: () -> Unit,
    viewModel: BoasVindasViewModel = viewModel(factory = BoasVindasViewModel.Factory),
) {
    val cidade by viewModel.cidade.collectAsStateWithLifecycle()
    val semLocal by viewModel.semLocal.collectAsStateWithLifecycle()
    val locais by viewModel.locais.collectAsStateWithLifecycle()
    var trocandoCidade by rememberSaveable { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Boas-vindas ao Jardim", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Duas coisas antes de começar. Dá para mudar tudo depois, em Configurações.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Lembretes()

                Bloco("📍 Sua cidade", "Para avisar de geada, calor forte, vento e chuva. O app guarda só a cidade: ele não acompanha onde você está.") {
                    val escolhida = cidade
                    if (escolhida != null && !trocandoCidade) {
                        Text("✓ ${escolhida.descricao}", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { trocandoCidade = true }) { Text("Trocar") }
                        if (!noCentroSul(escolhida)) {
                            Text(
                                "Os meses de plantio do catálogo foram pensados para o Centro-Sul, então o app não vai " +
                                    "dizer \"fora de época\" aí. Os avisos do tempo usam a previsão da sua cidade e valem igual.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        EscolherCidade(aoEscolher = { trocandoCidade = false })
                    }
                }

                val plantas = semLocal.orEmpty()
                if (plantas.isNotEmpty()) {
                    Bloco("🪴 Onde ficam suas plantas?", "Os avisos do tempo só valem para quem o tempo alcança.") {
                        plantas.forEach { p ->
                            Text(p.apelido, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                            EscolherLocal(locais[p.id], aoEscolher = { viewModel.mudarLocal(p.id, it) })
                        }
                    }
                }
            }
            Button(
                onClick = {
                    viewModel.terminar()
                    aoTerminar()
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text("Começar") }
        }
    }
}

@Composable
private fun Lembretes() {
    val contexto = LocalContext.current
    fun ligadas() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(contexto, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var permitido by remember { mutableStateOf(ligadas()) }
    var recusado by remember { mutableStateOf(false) }
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permitido = it
        recusado = !it
    }
    Bloco("🔔 Lembretes", "Para avisar da rega, do adubo e da geada na hora certa, mesmo com o app fechado.") {
        when {
            permitido -> Text("✓ Ligados", style = MaterialTheme.typography.titleMedium)
            recusado -> Text("Sem lembretes por enquanto. A tela Hoje mostra onde ligar depois.")
            else -> FilledTonalButton(onClick = { pedir.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Permitir") }
        }
    }
}

@Composable
private fun Bloco(titulo: String, texto: String, conteudo: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(titulo, style = MaterialTheme.typography.titleLarge)
            Text(texto, style = MaterialTheme.typography.bodyMedium)
            conteudo()
        }
    }
}
