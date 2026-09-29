package com.jardim.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jardim.dados.Miniaturas
import kotlin.math.roundToInt

private sealed interface EstadoFoto {
    data object Carregando : EstadoFoto
    data object SemFoto : EstadoFoto
    data class Foto(val imagem: ImageBitmap) : EstadoFoto
}

/**
 * A foto da espécie num quadrado de 56 dp. Enquanto baixa, só o fundo; sem
 * foto (nenhuma boa no catálogo, ou sem internet), o emoji da categoria.
 */
@Composable
fun MiniaturaPlanta(url: String?, categoria: String?, miniaturas: Miniaturas, modifier: Modifier = Modifier) {
    val inicial = remember(url) {
        when {
            url == null -> EstadoFoto.SemFoto
            else -> miniaturas.naMemoria(url)?.let(EstadoFoto::Foto) ?: EstadoFoto.Carregando
        }
    }
    val estado by produceState(inicial, url) {
        if (value == EstadoFoto.Carregando && url != null) {
            value = miniaturas.carregar(url)?.let(EstadoFoto::Foto) ?: EstadoFoto.SemFoto
        }
    }
    // A foto chega depois e aparece aos poucos; a que já estava na memória, na hora.
    val opacidade = remember(url) { Animatable(if (inicial is EstadoFoto.Foto) 1f else 0f) }
    LaunchedEffect(estado) { if (estado is EstadoFoto.Foto) opacidade.animateTo(1f, tween(150)) }
    // A foto é desenhada no próprio quadrado, e o emoji é sempre o mesmo Text (vazio
    // quando há foto): a árvore de layout nunca muda quando a foto chega. Pôr ou tirar
    // um filho aqui derrubava o app dentro do SharedTransitionLayout ("Placement
    // happened before lookahead", Compose 1.7.2).
    Box(
        modifier
            .size(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .drawWithContent {
                (estado as? EstadoFoto.Foto)?.let {
                    drawImage(it.imagem, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), alpha = opacidade.value)
                }
                drawContent()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (estado == EstadoFoto.SemFoto) emojiDaCategoria(categoria) else "", fontSize = 26.sp)
    }
}
