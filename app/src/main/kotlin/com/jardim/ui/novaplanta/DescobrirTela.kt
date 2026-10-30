package com.jardim.ui.novaplanta

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jardim.dados.Miniaturas
import com.jardim.motor.agenda.foraDeEpoca
import com.jardim.motor.catalogo.ItemCatalogo
import com.jardim.motor.catalogo.Sol
import com.jardim.motor.catalogo.Vaso
import com.jardim.ui.MiniaturaPlanta
import com.jardim.ui.categoria
import com.jardim.ui.faixaDeSol
import com.jardim.ui.pedeDoLugar
import com.jardim.ui.resumo
import com.jardim.ui.tamanhoDeVaso
import java.time.LocalDate

/**
 * "Não sei o que plantar": a pessoa diz quanto sol e quanto espaço tem, e a
 * lista mostra o que cabe, da mais popular à menos. Tocar numa planta leva ao
 * mesmo resumo da busca.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DescobrirTela(
    aoEscolher: (arquivo: String) -> Unit,
    aoVoltar: () -> Unit,
    viewModel: DescobrirViewModel = viewModel(factory = DescobrirViewModel.Factory),
) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()
    val hoje = remember { LocalDate.now() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("O que plantar") },
                navigationIcon = {
                    IconButton(onClick = aoVoltar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
                },
            )
        },
    ) { padding ->
        val pronto = estado as? EstadoDescoberta.Pronto ?: return@Scaffold
        val filtro = pronto.filtro
        val mudar = viewModel::mudar

        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item(key = "sol") {
                Pergunta("Quanto sol direto bate no lugar?") {
                    Sol.entries.forEach { sol ->
                        Escolha(faixaDeSol(sol), filtro.sol == sol) { mudar(filtro.copy(sol = sol.takeIf { filtro.sol != it })) }
                    }
                }
            }
            item(key = "vaso") {
                Pergunta("Qual o maior vaso que cabe lá?") {
                    Vaso.entries.forEach { vaso ->
                        Escolha(tamanhoDeVaso(vaso), filtro.vaso == vaso) { mudar(filtro.copy(vaso = vaso.takeIf { filtro.vaso != it })) }
                    }
                }
            }
            item(key = "outros") {
                Pergunta("Mais alguma coisa?") {
                    Escolha("Só as fáceis", filtro.soFaceis) { mudar(filtro.copy(soFaceis = !filtro.soFaceis)) }
                    // Fora do Centro-Sul os meses do catálogo não valem: o filtro some.
                    if (pronto.calendarioVale) {
                        Escolha("Dá para começar agora", filtro.soNaEpoca) { mudar(filtro.copy(soNaEpoca = !filtro.soNaEpoca)) }
                    }
                }
            }
            item(key = "categorias") {
                // Uma linha só, rolando para o lado: são até 16 categorias.
                val contagem = pronto.descoberta.categorias.toMap()
                val mostrar = pronto.descoberta.categorias.map { it.first } + filtro.categorias.filter { it !in contagem }
                Text(
                    "Tipo de planta",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    mostrar.forEach { codigo ->
                        val marcada = codigo in filtro.categorias
                        Escolha("${categoria(codigo)} ${contagem[codigo] ?: 0}", marcada) {
                            mudar(filtro.copy(categorias = if (marcada) filtro.categorias - codigo else filtro.categorias + codigo))
                        }
                    }
                }
            }
            item(key = "total") {
                val total = pronto.descoberta.plantas.size
                Text(
                    when (total) {
                        0 -> "Nenhuma planta cabe nesse lugar. Tente um vaso maior ou outra faixa de sol."
                        1 -> "1 planta"
                        else -> "$total plantas"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(pronto.descoberta.plantas, key = { it.arquivo }) { item ->
                PlantaDescoberta(item, hoje, pronto.calendarioVale, viewModel.miniaturas) { aoEscolher(item.arquivo) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pergunta(titulo: String, escolhas: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(titulo, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { escolhas() }
    }
}

@Composable
private fun Escolha(texto: String, marcada: Boolean, aoTocar: () -> Unit) {
    FilterChip(selected = marcada, onClick = aoTocar, label = { Text(texto) })
}

@Composable
private fun PlantaDescoberta(item: ItemCatalogo, hoje: LocalDate, calendarioVale: Boolean, miniaturas: Miniaturas, onClick: () -> Unit) {
    val cores = MaterialTheme.colorScheme
    val fora = calendarioVale && foraDeEpoca(item.mesesPlantio, hoje)
    ListItem(
        leadingContent = { MiniaturaPlanta(item.foto, item.categoria, miniaturas) },
        headlineContent = { Text(item.nomeComum) },
        supportingContent = {
            Column {
                // O porquê de ela estar na lista.
                Text(pedeDoLugar(item), style = MaterialTheme.typography.bodyMedium)
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
