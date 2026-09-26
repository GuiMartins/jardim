package com.jardim.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.navigation.NavBackStackEntry

/*
 * Duas transições, no estilo do Material:
 *  - container transform: o cartão da planta (ou o botão "Nova planta") cresce e
 *    vira a tela seguinte; ao voltar, encolhe de volta no lugar. Serve quando há
 *    um elemento tocado que "é" a próxima tela.
 *  - profundidade (eixo Z): a tela nova surge crescendo de leve e a de trás recua
 *    e some. Serve para o resto (busca → plantar, configurações).
 */

/** Rotas abertas a partir de um elemento da tela Hoje, com container transform. */
private val ROTAS_CONTAINER = setOf("planta/{id}", "catalogo")

private const val DURACAO_CONTAINER = 400
private const val DURACAO_Z = 300
private const val SAIDA_Z = 90

private val NavBackStackEntry.comContainer get() = destination.route in ROTAS_CONTAINER

/** Hoje ↔ planta ou busca: a tela de destino inteira está num sharedBounds, que anima sozinho. */
private val AnimatedContentTransitionScope<NavBackStackEntry>.ehContainer
    get() = initialState.destination.route == "hoje" && targetState.comContainer ||
        targetState.destination.route == "hoje" && initialState.comContainer

fun AnimatedContentTransitionScope<NavBackStackEntry>.entrada(): EnterTransition =
    if (ehContainer) {
        // O fade é só para a tela nova existir enquanto o container cresce.
        fadeIn(tween(DURACAO_CONTAINER))
    } else {
        fadeIn(tween(DURACAO_Z - SAIDA_Z, delayMillis = SAIDA_Z, easing = LinearOutSlowInEasing)) +
            scaleIn(tween(DURACAO_Z, easing = FastOutSlowInEasing), initialScale = 0.92f)
    }

fun AnimatedContentTransitionScope<NavBackStackEntry>.saida(): ExitTransition =
    if (ehContainer) {
        // A tela Hoje fica parada embaixo do cartão que cresce.
        ExitTransition.KeepUntilTransitionsFinished
    } else {
        fadeOut(tween(SAIDA_Z, easing = FastOutLinearInEasing)) +
            scaleOut(tween(DURACAO_Z, easing = FastOutSlowInEasing), targetScale = 1.08f)
    }

fun AnimatedContentTransitionScope<NavBackStackEntry>.entradaAoVoltar(): EnterTransition =
    if (ehContainer) {
        EnterTransition.None
    } else {
        fadeIn(tween(DURACAO_Z - SAIDA_Z, delayMillis = SAIDA_Z, easing = LinearOutSlowInEasing)) +
            scaleIn(tween(DURACAO_Z, easing = FastOutSlowInEasing), initialScale = 1.08f)
    }

fun AnimatedContentTransitionScope<NavBackStackEntry>.saidaAoVoltar(): ExitTransition =
    if (ehContainer) {
        fadeOut(tween(DURACAO_CONTAINER))
    } else {
        fadeOut(tween(SAIDA_Z, easing = FastOutLinearInEasing)) +
            scaleOut(tween(DURACAO_Z, easing = FastOutSlowInEasing), targetScale = 0.92f)
    }

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalTransicaoCompartilhada = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** A animação de entrada/saída da tela em que o composable está (um por destino do NavHost). */
val LocalAnimacaoDaTela = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Marca as duas pontas de um container transform: o elemento com a mesma [chave]
 * numa tela e na outra vira um só, que muda de tamanho e posição durante a
 * navegação. [forma] é o recorte enquanto ele anima (o do cartão, arredondado).
 * Fora de um NavHost (preview, teste) não faz nada.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.container(chave: String, forma: Shape): Modifier {
    val compartilhada = LocalTransicaoCompartilhada.current ?: return this
    val tela = LocalAnimacaoDaTela.current ?: return this
    return with(compartilhada) {
        val estado = rememberSharedContentState(chave)
        this@container.sharedBounds(
            sharedContentState = estado,
            animatedVisibilityScope = tela,
            boundsTransform = { _, _ -> tween(DURACAO_CONTAINER, easing = FastOutSlowInEasing) },
            // Remede o conteúdo a cada quadro: o texto se rearruma, em vez de esticar.
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            // Durante uma transição, todo sharedBounds vai para a camada de cima, até
            // os sem par: o botão "Nova planta" e os outros cartões flutuariam sobre
            // a planta que está abrindo.
            renderInOverlayDuringTransition = estado.isMatchFound,
            clipInOverlayDuringTransition = OverlayClip(forma),
        )
    }
}

/** O cartão da planta no Hoje e a tela dela. */
fun chaveDoPlantio(id: Any) = "plantio-$id"

/** O botão "Nova planta" e a tela de busca. */
const val CHAVE_NOVA_PLANTA = "nova-planta"
