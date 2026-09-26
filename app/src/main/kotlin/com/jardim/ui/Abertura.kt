package com.jardim.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Terra = Color(0xFF6B5139)
private val Semente = Color(0xFFB8905C)

/** Folha que abre passando um pouco do tamanho e volta, como quem desdobra. */
private val broto = keyframes {
    durationMillis = 300
    1.12f at 200 using FastOutSlowInEasing
    1f at 300
}

/**
 * A abertura do app, por cima da tela Hoje (que já carrega por baixo): uma
 * semente cai na terra, o caule sobe, duas folhas abrem e aparece o nome. Uns
 * 1,5 s; um toque em qualquer lugar pula. O desenho é em unidades de 1,8 dp,
 * com a origem no ponto em que o caule sai da terra.
 */
@Composable
fun Abertura(aoTerminar: () -> Unit) {
    val terminar by rememberUpdatedState(aoTerminar)
    val solo = remember { Animatable(0f) }
    val queda = remember { Animatable(0f) }
    val semente = remember { Animatable(1f) }
    val caule = remember { Animatable(0f) }
    val folhaEsquerda = remember { Animatable(0f) }
    val folhaDireita = remember { Animatable(0f) }
    val nome = remember { Animatable(0f) }
    val saida = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch { solo.animateTo(1f, tween(200)) }
        queda.animateTo(1f, tween(350, easing = CubicBezierEasing(0.5f, 0f, 1f, 1f)))
        launch { semente.animateTo(0f, tween(120)) }
        caule.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
        launch { folhaEsquerda.animateTo(1f, broto) }
        delay(100)
        folhaDireita.animateTo(1f, broto)
        nome.animateTo(1f, tween(250, easing = LinearOutSlowInEasing))
        delay(150)
        saida.animateTo(1f, tween(300, easing = FastOutLinearInEasing))
        terminar()
    }

    val cores = MaterialTheme.colorScheme
    val folha = cores.primary
    val folhaClara = lerp(cores.primary, cores.primaryContainer, 0.35f)
    val medidor = rememberTextMeasurer()
    val estiloNome = MaterialTheme.typography.displaySmall.merge(TextStyle(color = cores.primary))

    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - saida.value }
            .background(cores.background)
            .pointerInput(Unit) { detectTapGestures { terminar() } },
    ) {
        val u = 1.8.dp.toPx()
        translate(size.width / 2, size.height / 2 + 40.dp.toPx() - 30.dp.toPx() * saida.value) {
            drawLine(Terra.copy(alpha = solo.value), Offset(-50 * u, 2 * u), Offset(50 * u, 2 * u), 3 * u, StrokeCap.Round)

            if (semente.value > 0f) {
                val y = -160 * u + (155 * u) * queda.value
                drawOval(Semente.copy(alpha = semente.value), Offset(-7 * u, y - 5 * u), Size(14 * u, 10 * u))
            }

            if (caule.value > 0f) desenharCaule(caule.value, u, folha)
            scale(folhaEsquerda.value, pivot = Offset(0f, -47 * u)) {
                drawPath(formaDaFolha(u, esquerda = true), folha)
            }
            scale(folhaDireita.value, pivot = Offset(0f, -54 * u)) {
                drawPath(formaDaFolha(u, esquerda = false), folhaClara)
            }

            if (nome.value > 0f) {
                val texto = medidor.measure("Jardim", estiloNome)
                drawText(
                    texto,
                    topLeft = Offset(-texto.size.width / 2f, 26 * u + 8 * u * (1f - nome.value)),
                    alpha = nome.value,
                )
            }
        }
    }
}

/** O caule sobe da terra: desenha só a fração [progresso] do comprimento. */
private fun DrawScope.desenharCaule(progresso: Float, u: Float, cor: Color) {
    val inteiro = Path().apply {
        moveTo(0f, 0f)
        cubicTo(0f, -22 * u, -2 * u, -37 * u, 0f, -56 * u)
    }
    val medida = PathMeasure().apply { setPath(inteiro, false) }
    val parte = Path()
    medida.getSegment(0f, medida.length * progresso, parte, true)
    drawPath(parte, cor, style = Stroke(4 * u, cap = StrokeCap.Round))
}

private fun formaDaFolha(u: Float, esquerda: Boolean) = Path().apply {
    if (esquerda) {
        moveTo(0f, -47 * u)
        cubicTo(-18 * u, -48 * u, -30 * u, -60 * u, -32 * u, -76 * u)
        cubicTo(-15 * u, -76 * u, -2 * u, -64 * u, 0f, -47 * u)
    } else {
        moveTo(0f, -54 * u)
        cubicTo(16 * u, -56 * u, 30 * u, -70 * u, 32 * u, -88 * u)
        cubicTo(14 * u, -87 * u, 1 * u, -74 * u, 0f, -54 * u)
    }
    close()
}
