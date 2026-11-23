package com.example.mercadowatch.presentation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.example.mercadowatch.data.Ativo
import com.example.mercadowatch.data.Cotacao
import com.example.mercadowatch.data.Detalhes
import com.example.mercadowatch.data.Formatos
import com.example.mercadowatch.data.MercadoRepository
import com.example.mercadowatch.data.Periodo
import com.example.mercadowatch.presentation.theme.MercadoWatchTheme

/**
 * Detalhes de um ativo: valor atual, gráfico por período e estatísticas.
 *
 * Aberta pelo toque numa linha do tile ou por "Ver detalhes" na configuração. O ativo vem
 * pelos extras [EXTRA_ID] e [EXTRA_NOME] (o nome só é necessário para tickers da busca livre).
 */
class DetalhesActivity : ComponentActivity() {

    /**
     * Ativo exibido. A activity é singleTask: se já estiver aberta e o usuário tocar em outra
     * linha do tile, o Android reaproveita a instância (extras não diferenciam intents) e o
     * novo ativo chega por [onNewIntent].
     */
    private var ativo by mutableStateOf<Ativo?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ativo = ativoDo(intent) ?: run { finish(); return }
        setContent {
            ativo?.let { atual ->
                // key: trocar de ativo reinicia o estado da tela (período, dados)
                key(atual.id) { TelaDetalhes(atual) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ativoDo(intent)?.let { ativo = it }
    }

    private fun ativoDo(intent: Intent): Ativo? =
        Ativo.porId(intent.getStringExtra(EXTRA_ID), intent.getStringExtra(EXTRA_NOME))

    companion object {
        const val EXTRA_ID = "ativo_id"
        const val EXTRA_NOME = "ativo_nome"

        fun intent(context: Context, ativo: Ativo): Intent =
            Intent(context, DetalhesActivity::class.java)
                .putExtra(EXTRA_ID, ativo.id)
                .putExtra(EXTRA_NOME, ativo.nome)
    }
}

@Composable
fun TelaDetalhes(ativo: Ativo) {
    val context = LocalContext.current
    val repositorio = remember { MercadoRepository(context) }
    // 1M por padrão; se a fonte não tiver (títulos ANBIMA), o primeiro período suportado
    var periodo by rememberSaveable {
        mutableStateOf(repositorio.periodosSuportados(ativo).let { if (Periodo.UM_MES in it) Periodo.UM_MES else it.first() })
    }
    // null = carregando
    var detalhes by remember { mutableStateOf<Detalhes?>(null) }

    LaunchedEffect(periodo) {
        detalhes = null
        detalhes = repositorio.detalhes(ativo, periodo)
    }

    MercadoWatchTheme {
        AppScaffold {
            val listState = rememberTransformingLazyColumnState()
            ScreenScaffold(scrollState = listState) { contentPadding ->
                TransformingLazyColumn(contentPadding = contentPadding, state = listState) {
                    item {
                        ListHeader {
                            Text(ativo.nome, maxLines = 2, textAlign = TextAlign.Center)
                        }
                    }
                    item { Cabecalho(ativo, detalhes) }
                    item { AreaGrafico(ativo, detalhes) }
                    item {
                        SeletorPeriodo(
                            periodos = repositorio.periodosSuportados(ativo),
                            selecionado = periodo,
                            onSelecionar = { periodo = it },
                        )
                    }
                    detalhes?.estatisticas?.forEach { (rotulo, valor) ->
                        item { LinhaEstatistica(rotulo, valor) }
                    }
                }
            }
        }
    }
}

/** Valor atual em destaque, variação do dia e aviso de dado offline. */
@Composable
private fun Cabecalho(ativo: Ativo, detalhes: Detalhes?) {
    val cotacao = detalhes?.cotacao
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            cotacao?.let { ativo.formatar(it) } ?: if (detalhes == null) "…" else "--",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        cotacao?.variacaoPct?.let { v ->
            Text(ativo.formatarVariacao(v), color = if (v >= 0) CorAlta else CorBaixa, fontSize = 13.sp)
        }
        if (cotacao?.doCache == true) {
            Text(
                "Offline · " + Formatos.hora(cotacao.atualizadoEm),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Gráfico do período, ou a mensagem de carregando / sem conexão / sem dados. */
@Composable
private fun AreaGrafico(ativo: Ativo, detalhes: Detalhes?) {
    val mensagem = when {
        detalhes == null -> "Carregando…"
        detalhes.erroSerie -> "Sem conexão para o gráfico"
        detalhes.serie.size < 2 -> "Sem dados no período"
        else -> null
    }
    if (mensagem != null) {
        Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
            Text(
                mensagem,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    // Rótulos de máx./mín. no mesmo formato da cotação (moeda, índice ou taxa)
    val base = detalhes!!.cotacao ?: Cotacao(0.0, null, 0)
    GraficoLinha(
        pontos = detalhes.serie,
        formatar = { ativo.formatar(base.copy(valor = it)) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** Fileira de "chips" de período (5D 1M 6M 1A). */
@Composable
private fun SeletorPeriodo(periodos: List<Periodo>, selecionado: Periodo, onSelecionar: (Periodo) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        periodos.forEach { p ->
            val ativo = p == selecionado
            Box(
                Modifier
                    .size(width = 40.dp, height = 32.dp)
                    .clip(CircleShape)
                    .background(
                        if (ativo) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainer
                    )
                    .clickable { onSelecionar(p) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    p.rotulo,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (ativo) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** "Máx. do dia ........ R$ 42,35" */
@Composable
private fun LinhaEstatistica(rotulo: String, valor: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(rotulo, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            valor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
