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
import com.example.mercadowatch.presentation.DetalhesActivity
import com.example.mercadowatch.presentation.MainActivity
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers

/**
 * Tile "Mercado": mostra de 1 a 6 ativos escolhidos pelo usuário (padrão: Selic, dólar e Ibovespa).
 *
 * O layout escala com o tamanho da tela ([escala]): medidas pensadas para uma tela redonda de
 * [LARGURA_REFERENCIA_DP]; em telas menores tudo encolhe para as linhas não serem cortadas
 * pela borda redonda. Com 5 ou 6 ativos os espaçamentos somem, o título sobe e o rodapé desce.
 *
 * - Os ativos vêm de [Preferencias]; os dados, do [MercadoRepository] (com cache offline).
 * - O sistema pede um novo tile a cada [FRESCOR_MS].
 * - Toques: título "MERCADO ›" abre a configuração ([MainActivity]); cada linha abre os
 *   detalhes do ativo ([DetalhesActivity]); o rodapé ("Atualizado 14:53 ↻") atualiza o tile.
 */
class MainTileService : TileService() {

    /** Fator de escala do layout para a tela atual (1 = tela de [LARGURA_REFERENCIA_DP]). */
    private var escala = 1f

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture(Dispatchers.Main.immediate) {
            val larguraTela = requestParams.deviceConfiguration.screenWidthDp
            escala = if (larguraTela > 0) {
                (larguraTela / LARGURA_REFERENCIA_DP).coerceIn(ESCALA_MIN, ESCALA_MAX)
            } else 1f

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
        val conteudo = Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        // Espaçamento por quantidade de linhas: com 5-6 ativos os espaços somem e as áreas de
        // toque do título/rodapé encolhem, empurrando o título para cima e o rodapé para baixo
        val (entreLinhas, margem, alturaPontas) = when {
            ativos.size >= 5 -> Triple(0f, 0f, 24f)
            ativos.size == 4 -> Triple(2f, 2f, 32f)
            else -> Triple(6f, 6f, 32f)
        }

        conteudo
            .addContent(cabecalho(alturaPontas))
            .addContent(espaco(margem))
        ativos.forEachIndexed { i, ativo ->
            if (i > 0 && entreLinhas > 0) conteudo.addContent(espaco(entreLinhas))
            conteudo.addContent(linha(i, ativo, cotacoes[ativo]))
        }
        conteudo
            .addContent(espaco(margem))
            .addContent(rodape(cotacoes.values, alturaPontas))

        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .addContent(conteudo.build())
            .build()
    }

    /** Ação que abre uma activity do app, com extras opcionais. */
    private fun abrir(classe: Class<*>, extras: Map<String, String> = emptyMap()): ActionBuilders.Action {
        val atividade = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName)
            .setClassName(classe.name)
        extras.forEach { (chave, valor) ->
            atividade.addKeyToExtraMapping(
                chave, ActionBuilders.AndroidStringExtra.Builder().setValue(valor).build()
            )
        }
        return ActionBuilders.LaunchAction.Builder().setAndroidActivity(atividade.build()).build()
    }

    private fun clicavel(id: String, acao: ActionBuilders.Action) =
        ModifiersBuilders.Modifiers.Builder()
            .setClickable(ModifiersBuilders.Clickable.Builder().setId(id).setOnClick(acao).build())
            .build()

    /** Título "MERCADO ›" com área de toque própria que abre a configuração. */
    private fun cabecalho(altura: Float): LayoutElement {
        // Box maior que o texto para facilitar o toque
        return Box.Builder()
            .setWidth(medida(120f))
            .setHeight(medida(altura))
            .setModifiers(clicavel(ID_CONFIGURAR, abrir(MainActivity::class.java)))
            .addContent(texto("MERCADO ›", 12f, COR_SECUNDARIA, negrito = true))
            .build()
    }

    /**
     * Uma linha "Rótulo   valor   variação", com colunas de largura fixa para alinhar.
     * Tocar na linha abre os detalhes do ativo.
     */
    private fun linha(indice: Int, ativo: Ativo, cotacao: Cotacao?): LayoutElement {
        val valor = cotacao?.let { ativo.formatar(it) } ?: "--"
        val variacao = cotacao?.variacaoPct
        val corVariacao = when {
            variacao == null -> COR_SECUNDARIA
            variacao >= 0 -> COR_ALTA
            else -> COR_BAIXA
        }
        // Valores longos (ex.: "R$ 520 mil") usam fonte menor para caber na coluna
        val tamanhoValor = if (valor.length > 8) 13f else 15f

        val abrirDetalhes = abrir(
            DetalhesActivity::class.java,
            mapOf(DetalhesActivity.EXTRA_ID to ativo.id, DetalhesActivity.EXTRA_NOME to ativo.nome),
        )
        return Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(clicavel("$ID_DETALHES$indice", abrirDetalhes))
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
                    texto(variacao?.let { ativo.formatarVariacao(it) } ?: "", 11f, corVariacao),
                    LARGURA_VARIACAO,
                    LayoutElementBuilders.HORIZONTAL_ALIGN_END,
                )
            )
            .build()
    }

    /**
     * Rodapé clicável que atualiza o tile: "Atualizado 13:55 ↻", ou "Offline · 13:50 ↻"
     * (hora do dado mais antigo) se algo veio do cache.
     */
    private fun rodape(cotacoes: Collection<Cotacao?>, altura: Float): LayoutElement {
        val obtidas = cotacoes.filterNotNull()
        val texto = when {
            obtidas.isEmpty() -> "Sem dados · toque p/ atualizar"
            obtidas.size < cotacoes.size || obtidas.any { it.doCache } ->
                "Offline · " + Formatos.hora(obtidas.minOf { it.atualizadoEm }) + " ↻"
            else -> "Atualizado " + Formatos.hora(obtidas.maxOf { it.atualizadoEm }) + " ↻"
        }
        // Área de toque maior que o texto; LoadAction pede um novo tile (recarrega os dados)
        return Box.Builder()
            .setWidth(medida(150f))
            .setHeight(medida(altura))
            .setModifiers(clicavel(ID_ATUALIZAR, ActionBuilders.LoadAction.Builder().build()))
            .addContent(texto(texto, 11f, COR_SECUNDARIA))
            .build()
    }

    private fun celula(conteudo: LayoutElement, larguraDp: Float, alinhamento: Int): LayoutElement =
        Box.Builder()
            .setWidth(medida(larguraDp))
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
                .setSize(sp(tamanhoSp * escala))
                .setColor(argb(cor))
                .setWeight(
                    if (negrito) LayoutElementBuilders.FONT_WEIGHT_BOLD
                    else LayoutElementBuilders.FONT_WEIGHT_NORMAL
                )
                .build()
        )
        .build()

    private fun espaco(alturaDp: Float): LayoutElement =
        Spacer.Builder().setHeight(medida(alturaDp)).build()

    /** Medida em dp ajustada à tela atual. */
    private fun medida(valorDp: Float) = dp(valorDp * escala)

    companion object {
        private const val VERSAO_RECURSOS = "1"
        private const val ID_ATUALIZAR = "atualizar"
        private const val ID_CONFIGURAR = "configurar"
        private const val ID_DETALHES = "detalhes_"
        private const val FRESCOR_MS = 30 * 60 * 1000L // 30 minutos

        /** Largura de tela (dp) para a qual as medidas do layout foram pensadas. */
        private const val LARGURA_REFERENCIA_DP = 227f
        private const val ESCALA_MIN = 0.8f
        private const val ESCALA_MAX = 1.1f

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
