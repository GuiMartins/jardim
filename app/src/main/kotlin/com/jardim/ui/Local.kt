package com.jardim.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import com.jardim.motor.agenda.Local

fun nomeDoLocal(l: Local): String = when (l) {
    Local.AO_AR_LIVRE -> "Ao ar livre"
    Local.COBERTO -> "Coberta"
    Local.DENTRO_DE_CASA -> "Dentro de casa"
}

private fun explicacao(l: Local): String = when (l) {
    Local.AO_AR_LIVRE -> "Pega sol, vento e chuva"
    Local.COBERTO -> "Varanda, beiral: sente o frio e o vento, mas a chuva não chega"
    Local.DENTRO_DE_CASA -> "Sala, cozinha, janela: o tempo lá fora não chega"
}

/**
 * "Onde ela vai ficar?": decide quais avisos do tempo valem para a planta.
 * Sem nada marcado de início (decisão de 2026-09-27): um padrão errado erraria
 * calado, com geada na orquídea da sala ou nenhum aviso na pimenta do quintal.
 */
@Composable
fun EscolherLocal(escolhido: Local?, aoEscolher: (Local) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.selectableGroup()) {
        Local.entries.forEach { local ->
            ListItem(
                headlineContent = { Text(nomeDoLocal(local)) },
                supportingContent = { Text(explicacao(local)) },
                leadingContent = { RadioButton(selected = local == escolhido, onClick = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.selectable(local == escolhido, role = Role.RadioButton) { aoEscolher(local) },
            )
        }
    }
}
