package com.example.mercadowatch.tile

import android.content.Context
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.example.mercadowatch.data.Ativo
import com.example.mercadowatch.data.Cotacao
import com.example.mercadowatch.data.Formatos
import com.example.mercadowatch.data.MercadoRepository
import com.example.mercadowatch.data.Preferencias
import com.example.mercadowatch.presentation.MainActivity
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers

/**
 * Tile "Mercado": mostra 3 ativos escolhidos pelo usuário (padrão: Selic, dólar e Ibovespa).
 *
 * - Os ativos vêm de [Preferencias]; os dados, do [MercadoRepository] (com cache offline).
 * - O sistema pede um novo tile a cada [FRESCOR_MS]; tocar no tile força a atualização.
 * - Tocar no título "MERCADO" abre a tela de configuração ([MainActivity]).
 */
class MainTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture(Dispatchers.Main.immediate) {
            val ativos = Preferencias(this@MainTileService).ativosDoTile()
            val cotacoes = MercadoRepository(this@MainTileService).buscar(ativos)
            TileBuilders.Tile.Builder()
                .setResourcesVersion(VERSAO_RECURSOS)
                .setFreshnessIntervalMillis(FRESCOR_MS)
                .setTileTimeline(
                    TimelineBuilders.Timeline.fromLayoutElement(layout(ativos, cotacoes))
                )
                .build()
        }

    @Deprecated("Mantido por compatibilidade com hosts de tiles mais antigos")
    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        SuspendToFutureAdapter.launchFuture {
            // O tile não usa imagens; basta devolver a versão dos recursos
            ResourceBuilders.Resources.Builder().setVersion(VERSAO_RECURSOS).build()
        }

    // ---------------------------------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------------------------------

    /** Monta o layout: título, uma linha por ativo e o rodapé com a hora da atualização. */
    private fun layout(ativos: List<Ativo>, cotacoes: Map<Ativo, Cotacao?>): LayoutElement {
        // Tocar em qualquer lugar do tile pede um novo tile (recarrega os dados)
        val atualizar = ModifiersBuilders.Clickable.Builder()
            .setId(ID_ATUALIZAR)
            .setOnClick(ActionBuilders.LoadAction.Builder().build())
            .build()

        val conteudo = Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(cabecalho())
            .addContent(espaco(4f))

        ativos.forEachIndexed { i, ativo ->
            if (i > 0) conteudo.addContent(espaco(6f))
            conteudo.addContent(linha(ativo, cotacoes[ativo]))
        }

        conteudo
            .addContent(espaco(10f))
            .addContent(texto(rodape(cotacoes.values), 11f, COR_SECUNDARIA))

        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(atualizar).build())
            .addContent(conteudo.build())
            .build()
    }

    /** Título "MERCADO ›" com área de toque própria que abre a configuração. */
    private fun cabecalho(): LayoutElement {
        val abrirConfiguracao = ModifiersBuilders.Clickable.Builder()
            .setId(ID_CONFIGURAR)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .build()
                    )
                    .build()
            )
            .build()

        // Box maior que o texto para facilitar o toque
        return Box.Builder()
            .setWidth(dp(120f))
            .setHeight(dp(32f))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder().setClickable(abrirConfiguracao).build()
            )
            .addContent(texto("MERCADO ›", 12f, COR_SECUNDARIA, negrito = true))
            .build()
    }

    /** Uma linha "Rótulo   valor   variação", com colunas de largura fixa para alinhar. */
    private fun linha(ativo: Ativo, cotacao: Cotacao?): LayoutElement {
        val valor = cotacao?.let { ativo.formatar(it) } ?: "--"
        val variacao = cotacao?.variacaoPct
        val corVariacao = when {
            variacao == null -> COR_SECUNDARIA
            variacao >= 0 -> COR_ALTA
            else -> COR_BAIXA
        }
        // Valores longos (ex.: "R$ 520 mil") usam fonte menor para caber na coluna
        val tamanhoValor = if (valor.length > 8) 13f else 15f

        return Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(
                celula(
                    // Rótulos de 6 caracteres (ex.: "HGLG11") usam fonte menor para não cortar
                    texto(ativo.rotulo, if (ativo.rotulo.length > 5) 11f else 13f, COR_SECUNDARIA),
                    LARGURA_ROTULO,
                    LayoutElementBuilders.HORIZONTAL_ALIGN_START,
                )
            )
            .addContent(
                celula(
                    texto(valor, tamanhoValor, COR_PRIMARIA, negrito = true),
                    LARGURA_VALOR,
                    LayoutElementBuilders.HORIZONTAL_ALIGN_END,
                )
            )
            .addContent(
                celula(
                    texto(variacao?.let { Formatos.variacao(it) } ?: "", 11f, corVariacao),
                    LARGURA_VARIACAO,
                    LayoutElementBuilders.HORIZONTAL_ALIGN_END,
                )
            )
            .build()
    }

    /** "Atualizado 13:55", ou "Offline · 13:50" (hora do dado mais antigo) se algo veio do cache. */
    private fun rodape(cotacoes: Collection<Cotacao?>): String {
        val obtidas = cotacoes.filterNotNull()
        if (obtidas.isEmpty()) return "Sem dados · toque p/ atualizar"
        return if (obtidas.size < cotacoes.size || obtidas.any { it.doCache }) {
            "Offline · " + Formatos.hora(obtidas.minOf { it.atualizadoEm })
        } else {
            "Atualizado " + Formatos.hora(obtidas.maxOf { it.atualizadoEm })
        }
    }

    private fun celula(conteudo: LayoutElement, larguraDp: Float, alinhamento: Int): LayoutElement =
        Box.Builder()
            .setWidth(dp(larguraDp))
            .setHorizontalAlignment(alinhamento)
            .addContent(conteudo)
            .build()

    private fun texto(
        valor: String,
        tamanhoSp: Float,
        cor: Int,
        negrito: Boolean = false,
    ): LayoutElement = Text.Builder()
        .setText(valor)
        .setMaxLines(1)
        .setFontStyle(
            FontStyle.Builder()
                .setSize(sp(tamanhoSp))
                .setColor(argb(cor))
                .setWeight(
                    if (negrito) LayoutElementBuilders.FONT_WEIGHT_BOLD
                    else LayoutElementBuilders.FONT_WEIGHT_NORMAL
                )
                .build()
        )
        .build()

    private fun espaco(alturaDp: Float): LayoutElement =
        Spacer.Builder().setHeight(dp(alturaDp)).build()

    companion object {
        private const val VERSAO_RECURSOS = "1"
        private const val ID_ATUALIZAR = "atualizar"
        private const val ID_CONFIGURAR = "configurar"
        private const val FRESCOR_MS = 30 * 60 * 1000L // 30 minutos

        private const val LARGURA_ROTULO = 48f
        private const val LARGURA_VALOR = 78f
        private const val LARGURA_VARIACAO = 52f

        private const val COR_PRIMARIA = 0xFFFFFFFF.toInt()
        private const val COR_SECUNDARIA = 0xFFAAB4C3.toInt()
        private const val COR_ALTA = 0xFF5BD08A.toInt()
        private const val COR_BAIXA = 0xFFFF6B6B.toInt()

        /** Pede ao sistema para redesenhar o tile (ex.: após mudar a configuração). */
        fun solicitarAtualizacao(context: Context) {
            getUpdater(context).requestUpdate(MainTileService::class.java)
        }
    }
}
