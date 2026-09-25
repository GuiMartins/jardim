package com.jardim.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jardim.ui.configuracoes.ConfiguracoesTela
import com.jardim.ui.hoje.HojeTela
import com.jardim.ui.novaplanta.CatalogoTela
import com.jardim.ui.novaplanta.PlantarTela

@Composable
fun JardimNavegacao() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "hoje") {
        composable("hoje") {
            HojeTela(aoAdicionar = { nav.navigate("catalogo") }, aoConfigurar = { nav.navigate("configuracoes") })
        }
        composable("catalogo") {
            CatalogoTela(aoEscolher = { arquivo -> nav.navigate("plantar/$arquivo") }, aoVoltar = { nav.popBackStack() })
        }
        composable("plantar/{arquivo}") { entrada ->
            PlantarTela(
                arquivo = checkNotNull(entrada.arguments?.getString("arquivo")),
                aoVoltar = { nav.popBackStack() },
                aoPlantar = { nav.popBackStack("hoje", inclusive = false) },
            )
        }
        composable("configuracoes") {
            ConfiguracoesTela(aoVoltar = { nav.popBackStack() })
        }
    }
}
