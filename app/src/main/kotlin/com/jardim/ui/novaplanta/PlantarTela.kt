package com.jardim.ui.novaplanta

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jardim.dados.Especie
import com.jardim.motor.agenda.Local
import com.jardim.ui.EscolherLocal
import com.jardim.motor.agenda.foraDeEpoca
import com.jardim.motor.modelo.FormaInicio
import com.jardim.ui.comoDataUtc
import com.jardim.ui.dataCompleta
import com.jardim.ui.emMillisUtc
import com.jardim.ui.meses
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantarTela(
    arquivo: String,
    /** O planejamento de onde a planta veio, se veio de um: some ao plantar. */
    planejadoId: Long?,
    /** O lugar já escolhido no preparo, se foi. */
    localInicial: Local?,
    aoVoltar: () -> Unit,
    aoPlantar: () -> Unit,
    viewModel: NovaPlantaViewModel = viewModel(factory = NovaPlantaViewModel.Factory),
) {
    // O JSON inteiro da espécie só é lido agora, fora da main thread.
    val especie by produceState<Especie?>(null, arquivo) { value = viewModel.carregar(arquivo) }
    especie?.let { PlantarConteudo(it, planejadoId, localInicial, aoVoltar, aoPlantar, viewModel) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlantarConteudo(
    especie: Especie,
    planejadoId: Long?,
    localInicial: Local?,
    aoVoltar: () -> Unit,
    aoPlantar: () -> Unit,
    viewModel: NovaPlantaViewModel,
) {
    val planta = especie.cultivo.planta
    val deSemente = planta.formaInicio == FormaInicio.SEMENTE

    var apelido by rememberSaveable { mutableStateOf(planta.nomeComum) }
    var dataPlantio by rememberSaveable { mutableStateOf(LocalDate.now()) }
    var local by rememberSaveable { mutableStateOf(localInicial) }
    var escolhendoData by remember { mutableStateOf(false) }
    var salvando by remember { mutableStateOf(false) }
    val calendarioVale by viewModel.calendarioVale.collectAsStateWithLifecycle()

    val contexto = LocalContext.current
    val plantar = {
        salvando = true
        viewModel.plantar(especie, apelido, dataPlantio, checkNotNull(local), planejadoId, aoPlantar)
    }
    // A permissão de notificação (Android 13+) é pedida aqui, no momento em que
    // ela faz sentido: a planta acabou de ganhar uma agenda. Planta mesmo se a
    // pessoa negar; a tela Hoje mostra o aviso para ligar depois.
    val pedirPermissao = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { plantar() }
    val precisaPedir = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(contexto, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

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
                Button(
                    onClick = {
                        if (precisaPedir) pedirPermissao.launch(Manifest.permission.POST_NOTIFICATIONS) else plantar()
                    },
                    enabled = !salvando && local != null,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                ) {
                    Text(
                        when {
                            local == null -> "Escolha onde ela vai ficar"
                            deSemente -> "Semeei"
                            else -> "Plantei"
                        },
                    )
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
            OutlinedTextField(
                value = apelido,
                onValueChange = { apelido = it },
                label = { Text("Apelido") },
                supportingText = { Text("Para diferenciar plantas da mesma espécie, ex.: ${planta.nomeComum} da varanda") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedCard(onClick = { escolhendoData = true }, modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    overlineContent = { Text(if (deSemente) "Dia 1 · semeadura" else "Dia 1 · plantio") },
                    headlineContent = { Text(dataCompleta(dataPlantio)) },
                    supportingContent = { Text("Toque para mudar, se começou em outro dia") },
                )
            }

            if (calendarioVale && foraDeEpoca(especie.cultivo, dataPlantio)) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Text(
                        "Fora da época ideal (${meses(planta.mesesPlantio)}). Dá para plantar, mas ela pode " +
                            "crescer menos ou pegar frio ou calor fora de hora.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            Column {
                Text("Onde ela vai ficar?", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Para avisar de geada, calor forte, vento e chuva só quando o tempo chega até ela.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                EscolherLocal(local, aoEscolher = { local = it })
            }
        }
    }

    if (escolhendoData) {
        val hoje = remember { LocalDate.now().emMillisUtc() }
        val estado = rememberDatePickerState(
            initialSelectedDateMillis = dataPlantio.emMillisUtc(),
            // Dá para registrar quem começou dias atrás, mas não no futuro.
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= hoje
            },
        )
        DatePickerDialog(
            onDismissRequest = { escolhendoData = false },
            confirmButton = {
                TextButton(onClick = {
                    estado.selectedDateMillis?.let { dataPlantio = it.comoDataUtc() }
                    escolhendoData = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { escolhendoData = false }) { Text("Cancelar") } },
        ) { DatePicker(estado) }
    }
}
