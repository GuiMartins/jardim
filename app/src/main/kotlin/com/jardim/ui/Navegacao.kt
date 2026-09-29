package com.jardim.ui

import com.jardim.ui.cidade.CidadeTela
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jardim.motor.agenda.Local
import com.jardim.ui.configuracoes.ConfiguracoesTela
import com.jardim.ui.hoje.HojeTela
import com.jardim.ui.novaplanta.CatalogoTela
import com.jardim.ui.novaplanta.PlantarTela
import com.jardim.ui.novaplanta.PrepararTela
import com.jardim.ui.novaplanta.ResumoTela
import com.jardim.ui.planta.PlantaTela

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun JardimNavegacao() {
    val nav = rememberNavController()
    // Com fundo próprio: na profundidade, entre a saída de uma tela e a entrada da
    // outra aparecia o fundo claro da janela, e o tema escuro piscava.
    SharedTransitionLayout(Modifier.background(MaterialTheme.colorScheme.background)) {
        CompositionLocalProvider(LocalTransicaoCompartilhada provides this) {
            NavHost(
                nav,
                startDestination = "hoje",
                enterTransition = { entrada() },
                exitTransition = { saida() },
                popEnterTransition = { entradaAoVoltar() },
                popExitTransition = { saidaAoVoltar() },
            ) {
                tela("hoje") {
                    HojeTela(
                        aoAdicionar = { nav.navigate("catalogo") },
                        aoAbrirPlanejado = { arquivo, id -> nav.navigate("preparar/$arquivo?planejado=$id") },
                        aoConfigurar = { nav.navigate("configuracoes") },
                        aoAbrirPlanta = { id -> nav.navigate("planta/$id") },
                    )
                }
                tela("planta/{id}") { entrada ->
                    // A outra ponta está no cartão da planta, em HojeTela.
                    val chave = chaveDoPlantio(checkNotNull(entrada.arguments?.getString("id")))
                    Box(Modifier.fillMaxSize().container(chave, CardDefaults.elevatedShape)) {
                        PlantaTela(aoVoltar = { nav.popBackStack() })
                    }
                }
                tela("catalogo") {
                    Box(Modifier.fillMaxSize().container(CHAVE_NOVA_PLANTA, FloatingActionButtonDefaults.extendedFabShape)) {
                        CatalogoTela(aoEscolher = { arquivo -> nav.navigate("especie/$arquivo") }, aoVoltar = { nav.popBackStack() })
                    }
                }
                tela("especie/{arquivo}") { entrada ->
                    val arquivo = checkNotNull(entrada.arguments?.getString("arquivo"))
                    ResumoTela(
                        arquivo = arquivo,
                        aoVoltar = { nav.popBackStack() },
                        aoJaComecou = { nav.navigate("plantar/$arquivo") },
                        aoPreparar = { nav.navigate("preparar/$arquivo") },
                    )
                }
                tela("preparar/{arquivo}?planejado={planejado}", listOf(argPlanejado)) { entrada ->
                    val arquivo = checkNotNull(entrada.arguments?.getString("arquivo"))
                    val planejado = entrada.planejado()
                    PrepararTela(
                        arquivo = arquivo,
                        planejadoId = planejado,
                        aoVoltar = { nav.popBackStack() },
                        aoPlantarAgora = { local ->
                            nav.navigate("plantar/$arquivo?planejado=${planejado ?: -1}&local=${local?.name ?: ""}")
                        },
                        aoSair = { nav.popBackStack("hoje", inclusive = false) },
                    )
                }
                tela(
                    "plantar/{arquivo}?planejado={planejado}&local={local}",
                    listOf(argPlanejado, navArgument("local") { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) { entrada ->
                    PlantarTela(
                        arquivo = checkNotNull(entrada.arguments?.getString("arquivo")),
                        planejadoId = entrada.planejado(),
                        localInicial = entrada.arguments?.getString("local")?.let { nome -> Local.entries.firstOrNull { it.name == nome } },
                        aoVoltar = { nav.popBackStack() },
                        aoPlantar = { nav.popBackStack("hoje", inclusive = false) },
                    )
                }
                tela("configuracoes") {
                    ConfiguracoesTela(aoVoltar = { nav.popBackStack() }, aoEscolherCidade = { nav.navigate("cidade") })
                }
                tela("cidade") {
                    CidadeTela(aoVoltar = { nav.popBackStack() })
                }
            }
        }
    }
}

/** O planejamento de onde a tela veio; -1 é "nenhum", porque Long na rota não aceita null. */
private val argPlanejado = navArgument("planejado") { type = NavType.LongType; defaultValue = -1L }

private fun NavBackStackEntry.planejado(): Long? = arguments?.getLong("planejado")?.takeIf { it >= 0 }

/** Um destino do NavHost que deixa a própria animação à mão de [container]. */
private fun NavGraphBuilder.tela(
    rota: String,
    argumentos: List<NamedNavArgument> = emptyList(),
    conteudo: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable(rota, argumentos) { entrada ->
        CompositionLocalProvider(LocalAnimacaoDaTela provides this) { conteudo(entrada) }
    }
}
