package com.example.mercadowatch.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.example.mercadowatch.data.Ponto

/** Mesmas cores de alta e baixa do tile. */
val CorAlta = Color(0xFF5BD08A)
val CorBaixa = Color(0xFFFF6B6B)

/**
 * Gráfico de linha simples em Canvas (sem bibliotecas).
 *
 * - Os pontos são distribuídos por índice, não por tempo: fins de semana e madrugadas não
 *   viram buracos no gráfico.
 * - Verde se o último valor é maior ou igual ao primeiro, vermelho caso contrário.
 * - [formatar] formata os rótulos de máximo e mínimo exibidos acima e abaixo da linha.
 */
@Composable
fun GraficoLinha(
    pontos: List<Ponto>,
    formatar: (Double) -> String,
    modifier: Modifier = Modifier,
) {
    val valores = pontos.map { it.valor }
    val maximo = valores.max()
    val minimo = valores.min()
    val cor = if (valores.last() >= valores.first()) CorAlta else CorBaixa
    val corRotulo = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(formatar(maximo), fontSize = 10.sp, color = corRotulo)
        Box(Modifier.fillMaxWidth().height(70.dp)) {
            Canvas(Modifier.fillMaxWidth().height(70.dp)) {
                val amplitude = (maximo - minimo).takeIf { it > 0 } ?: 1.0
                val passoX = if (valores.size > 1) size.width / (valores.size - 1) else 0f
                fun y(v: Double) = (size.height * (1 - (v - minimo) / amplitude)).toFloat()

                val linha = Path().apply {
                    valores.forEachIndexed { i, v ->
                        if (i == 0) moveTo(0f, y(v)) else lineTo(i * passoX, y(v))
                    }
                }
                // Área sob a linha com degradê suave
                val area = Path().apply {
                    addPath(linha)
                    lineTo((valores.size - 1) * passoX, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(
                    area,
                    Brush.verticalGradient(listOf(cor.copy(alpha = 0.35f), Color.Transparent)),
                )
                drawPath(
                    linha, cor,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
        Text(formatar(minimo), fontSize = 10.sp, color = corRotulo)
    }
}
