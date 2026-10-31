package com.jardim.ui.despensa

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.jardim.dados.Despensa
import com.jardim.dados.ListaDeCompras
import com.jardim.dados.OutraCompra
import com.jardim.motor.agenda.Insumo
import com.jardim.motor.agenda.ItemPreparo
import com.jardim.ui.inicioDe
import com.jardim.ui.nomeDoInsumo
import com.jardim.ui.numero
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ComprasViewModel(private val despensa: Despensa) : ViewModel() {

    val compras: StateFlow<ListaDeCompras?> = despensa.compras.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun comprei(insumo: Insumo) {
        viewModelScope.launch { despensa.mudar(insumo, tem = true) }
    }

    fun comprei(compra: OutraCompra) {
        viewModelScope.launch { despensa.comprei(compra) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { ComprasViewModel((this[APPLICATION_KEY] as JardimApp).despensa) }
        }
    }
}

/**
 * O que falta comprar para as plantas de "Para plantar", menos o que já está na
 * despensa. "Comprei" num insumo põe ele na despensa; nos outros itens, tira o
 * item do que falta naquela planta.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComprasTela(
    aoVoltar: () -> Unit,
    aoAbrirDespensa: () -> Unit,
    viewModel: ComprasViewModel = viewModel(factory = ComprasViewModel.Factory),
) {
    val compras by viewModel.compras.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lista de compras") },
                navigationIcon = {
                    IconButton(onClick = aoVoltar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
                },
                actions = { TextButton(onClick = aoAbrirDespensa) { Text("Despensa") } },
            )
        },
    ) { padding ->
        val lista = compras ?: return@Scaffold
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (lista.vazia) {
                item(key = "vazia") {
                    Text(
                        "Nada para comprar: o que as plantas de Para plantar pedem já está em casa.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            if (lista.outras.isNotEmpty()) {
                item(key = "titulo-outras") { Titulo("Mudas, sementes e vasos") }
                items(lista.outras, key = { "${it.planejadoId}-${it.item}" }) { compra ->
                    ListItem(
                        headlineContent = { Text(tituloDe(compra)) },
                        // "Sementes de manjericão" já diz para quem é.
                        supportingContent = if (compra.item == ItemPreparo.INICIO) null else {
                            { Text("Para ${compra.planta.replaceFirstChar { it.lowercase() }}") }
                        },
                        trailingContent = { OutlinedButton(onClick = { viewModel.comprei(compra) }) { Text("Comprei") } },
                    )
                }
            }
            if (lista.insumos.isNotEmpty()) {
                item(key = "titulo-insumos") { Titulo("Substrato e drenagem") }
                items(lista.insumos, key = { it.insumo.name }) { compra ->
                    ListItem(
                        headlineContent = { Text(nomeDoInsumo(compra.insumo)) },
                        supportingContent = {
                            Column {
                                if (compra.ou.isNotEmpty()) {
                                    Text("ou " + compra.ou.joinToString(" ou ") { nomeDoInsumo(it).lowercase() })
                                }
                                Text(
                                    "Para " + compra.para.joinToString(", ") { it.replaceFirstChar { c -> c.lowercase() } },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        trailingContent = { OutlinedButton(onClick = { viewModel.comprei(compra.insumo) }) { Text("Comprei") } },
                    )
                }
            }
        }
    }
}

@Composable
private fun Titulo(texto: String) {
    Text(
        texto,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

private fun tituloDe(compra: OutraCompra): String = when (compra.item) {
    ItemPreparo.INICIO -> inicioDe(compra.formaInicio, compra.planta)
    ItemPreparo.VASO -> compra.volumeLitros?.let { "Vaso de ${numero(it)} L ou mais" } ?: "Vaso"
    // Só cai aqui quando o insumos.py não entendeu o texto do substrato.
    else -> "Substrato"
}
