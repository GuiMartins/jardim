package com.jardim.ui.novaplanta

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jardim.motor.agenda.foraDeEpoca
import com.jardim.ui.meses
import com.jardim.ui.resumo
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogoTela(
    aoEscolher: (arquivo: String) -> Unit,
    aoVoltar: () -> Unit,
    viewModel: NovaPlantaViewModel = viewModel(factory = NovaPlantaViewModel.Factory),
) {
    val hoje = remember { LocalDate.now() }
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
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(viewModel.especies, key = { it.arquivo }) { especie ->
                val planta = especie.cultivo.planta
                ElevatedCard(onClick = { aoEscolher(especie.arquivo) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(planta.nomeComum, style = MaterialTheme.typography.titleLarge)
                        Text(
                            planta.nomeCientifico,
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(resumo(planta), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (foraDeEpoca(especie.cultivo, hoje)) {
                                "Fora de época agora · melhor em ${meses(planta.mesesPlantio)}"
                            } else {
                                "Boa época para começar"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (foraDeEpoca(especie.cultivo, hoje)) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }
            }
        }
    }
}
