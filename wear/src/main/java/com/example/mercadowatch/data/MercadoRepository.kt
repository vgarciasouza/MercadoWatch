package com.example.mercadowatch.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Uma cotação exibida no relógio.
 *
 * @param valor valor atual (taxa em %, preço em R$ ou índice em pontos)
 * @param variacaoPct variação do dia em %, quando disponível (as séries do BCB não têm).
 *   Para títulos públicos (ANBIMA) é a variação da taxa em pontos percentuais.
 * @param atualizadoEm momento (epoch ms) em que o valor foi obtido da API
 * @param doCache true se a busca falhou e o valor veio do cache local
 * @param moeda moeda da cotação (ex.: "BRL", "USD"), quando a fonte informa
 * @param indice true se o ativo é um índice (exibido em pontos)
 */
data class Cotacao(
    val valor: Double,
    val variacaoPct: Double?,
    val atualizadoEm: Long,
    val doCache: Boolean = false,
    val moeda: String? = null,
    val indice: Boolean = false,
)

/** Um resultado da busca livre (ticker do Yahoo Finance ou título público da ANBIMA). */
data class ResultadoBusca(val ativo: Ativo, val bolsa: String?, val tipo: String?)

/** Leitura bruta de uma fonte, antes de virar [Cotacao]. */
private data class Leitura(
    val valor: Double,
    val variacao: Double?,
    val moeda: String? = null,
    val indice: Boolean = false,
)

/**
 * Busca cotações nas APIs públicas e mantém o último valor de cada ativo em SharedPreferences.
 * Se uma busca falhar (ex.: relógio offline), devolve o valor em cache daquele ativo.
 *
 * Fontes (ver [Fonte]):
 * - Banco Central: API SGS (Selic meta, CDI, IPCA 12 meses)
 * - AwesomeAPI: moedas e Bitcoin em R$ (cotação intradiária)
 * - Yahoo Finance: endpoints públicos de gráficos e de busca (qualquer ticker) — não oficiais
 * - ANBIMA: taxas indicativas de títulos públicos (NTN-B, NTN-F), ver [Anbima]
 */
class MercadoRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NOME, Context.MODE_PRIVATE)

    /** Busca vários ativos em paralelo; cada um é null se nunca foi obtido (sem rede e sem cache). */
    suspend fun buscar(ativos: List<Ativo>): Map<Ativo, Cotacao?> = coroutineScope {
        ativos.distinct()
            .map { ativo -> async { ativo to buscar(ativo) } }
            .awaitAll()
            .toMap()
    }

    /** Busca um ativo; em caso de falha devolve o último valor gravado. */
    suspend fun buscar(ativo: Ativo): Cotacao? = try {
        val leitura = withContext(Dispatchers.IO) {
            when (val fonte = ativo.fonte) {
                is Fonte.Bcb -> buscarBcb(fonte)
                is Fonte.Awesome -> buscarAwesome(fonte)
                is Fonte.Yahoo -> buscarYahoo(fonte)
                is Fonte.Anbima -> buscarAnbima(fonte)
            }
        }
        Cotacao(
            valor = leitura.valor,
            variacaoPct = leitura.variacao,
            atualizadoEm = System.currentTimeMillis(),
            moeda = leitura.moeda,
            indice = leitura.indice,
        ).also { salvar(ativo.id, it) }
    } catch (e: CancellationException) {
        // Cancelamento (ex.: a tela foi fechada) não é falha de rede: deve se propagar
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Falha ao buscar '${ativo.id}'; usando cache", e)
        ler(ativo.id)
    }

    /** Último valor da série SGS até hoje. */
    private fun buscarBcb(fonte: Fonte.Bcb): Leitura {
        // Obs.: o endpoint ".../dados/ultimos/1" responde sempre HTTP 304 sem corpo (comportamento
        // do servidor do BCB), por isso consultamos um intervalo de datas terminando hoje.
        // Algumas séries (ex.: 432, Selic meta) trazem datas futuras; limitar dataFinal a hoje
        // garante que o último item é o valor vigente.
        val hoje = LocalDate.now(FUSO_BRASILIA)
        val url = "https://api.bcb.gov.br/dados/serie/bcdata.sgs.${fonte.serie}/dados?formato=json" +
            "&dataInicial=" + hoje.minusDays(fonte.janelaDias).format(FORMATO_DATA) +
            "&dataFinal=" + hoje.format(FORMATO_DATA)

        // Formato: [{"data":"dd/MM/yyyy","valor":"15.00"}, ...] em ordem cronológica
        val array = JSONArray(Http.get(url))
        if (array.length() == 0) throw IllegalStateException("Série ${fonte.serie} vazia")
        return Leitura(array.getJSONObject(array.length() - 1).getString("valor").toDouble(), null)
    }

    /** Cotação de compra (bid) e variação do dia na AwesomeAPI. */
    private fun buscarAwesome(fonte: Fonte.Awesome): Leitura {
        // Formato: {"USDBRL":{"bid":"5.98","pctChange":"0.68", ...}}
        val json = JSONObject(Http.get("https://economia.awesomeapi.com.br/json/last/${fonte.par}"))
            .getJSONObject(fonte.par.replace("-", ""))
        return Leitura(
            valor = json.getString("bid").toDouble(),
            variacao = json.optString("pctChange").toDoubleOrNull(),
            moeda = json.optString("codein").ifEmpty { null },
        )
    }

    /** Preço atual e variação do dia no Yahoo Finance. */
    private fun buscarYahoo(fonte: Fonte.Yahoo): Leitura {
        val simbolo = URLEncoder.encode(fonte.simbolo, "UTF-8")
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$simbolo?range=1d&interval=1d"

        // Formato: {"chart":{"result":[{"meta":{"regularMarketPrice":..., "chartPreviousClose":...}}]}}
        val meta = JSONObject(Http.get(url))
            .getJSONObject("chart").getJSONArray("result")
            .getJSONObject(0).getJSONObject("meta")
        val preco = meta.getDouble("regularMarketPrice")
        // Usa a variação informada; se não vier, calcula pelo fechamento anterior
        val variacao = if (meta.has("regularMarketChangePercent")) {
            meta.getDouble("regularMarketChangePercent")
        } else {
            meta.optDouble("chartPreviousClose").takeIf { !it.isNaN() && it > 0 }
                ?.let { (preco / it - 1) * 100 }
        }
        return Leitura(
            valor = preco,
            variacao = variacao,
            moeda = meta.optString("currency").ifEmpty { null },
            indice = meta.optString("instrumentType") == "INDEX",
        )
    }

    /** Taxa indicativa mais recente e variação (em pontos percentuais) de um título público. */
    private suspend fun buscarAnbima(fonte: Fonte.Anbima): Leitura {
        val (titulo, variacao) = Anbima.taxa(fonte.titulo, fonte.vencimento)
        return Leitura(valor = titulo.taxaIndicativa, variacao = variacao)
    }

    /**
     * Busca de títulos públicos: se o termo menciona NTN/Tesouro, devolve os vencimentos de
     * NTN-B e NTN-F do arquivo mais recente da ANBIMA. "ntnb", "ntn-f 2045", "tesouro 35"…
     * filtram por título e ano. Termos sem relação com títulos devolvem lista vazia.
     */
    suspend fun pesquisarTitulos(termo: String): List<ResultadoBusca> {
        val t = Normalizer.normalize(termo, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "").lowercase().replace(Regex("[^a-z0-9]"), "")
        if (!t.contains("ntn") && !t.contains("tesouro")) return emptyList()

        val tipo = when {
            t.contains("ntnb") -> "NTN-B"
            t.contains("ntnf") -> "NTN-F"
            else -> null
        }
        val ano = Regex("""\d{2,4}""").find(t)?.value

        return Anbima.titulosDisponiveis()
            .filter { tipo == null || it.titulo == tipo }
            .filter { ano == null || it.vencimento.take(4).endsWith(ano) }
            .map { ResultadoBusca(Ativo.doAnbima(it.titulo, it.vencimento), bolsa = "ANBIMA", tipo = null) }
    }

    /**
     * Busca livre de tickers no Yahoo Finance (ações, FIIs, ETFs, índices, cripto, moedas).
     * Lança exceção se a requisição falhar — a tela mostra o erro.
     */
    suspend fun pesquisarTickers(termo: String): List<ResultadoBusca> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(termo.trim(), "UTF-8")
        val url = "https://query2.finance.yahoo.com/v1/finance/search" +
            "?q=$q&quotesCount=$MAX_RESULTADOS&newsCount=0&listsCount=0"

        // Formato: {"quotes":[{"symbol":"PETR3.SA","shortname":"PETROBRAS ON","quoteType":"EQUITY",
        //                      "exchange":"SAO", ...}, ...]}
        val quotes = JSONObject(Http.get(url)).optJSONArray("quotes") ?: JSONArray()
        (0 until quotes.length())
            .map { quotes.getJSONObject(it) }
            .filter { it.optString("quoteType") in TIPOS_ACEITOS && it.has("symbol") }
            .map { q ->
                // Nomes do Yahoo vêm com espaços repetidos, ex.: "PETROBRAS   ON      N2"
                val nome = (q.optString("shortname").ifEmpty { q.optString("longname") })
                    .replace(Regex("\\s+"), " ").trim()
                ResultadoBusca(
                    ativo = Ativo.doYahoo(q.getString("symbol"), nome),
                    bolsa = q.optString("exchDisp").ifEmpty { q.optString("exchange") }.ifEmpty { null },
                    tipo = q.optString("typeDisp").ifEmpty { null },
                )
            }
            .distinctBy { it.ativo }
            .let(::semVariantesB3)
    }

    /**
     * Remove as variantes de mercado fracionário/outras listagens da B3 que o Yahoo devolve
     * junto (ex.: "PETR3F.SA", "HGLG11Q.SA") quando o ticker principal ("PETR3.SA") também veio.
     */
    private fun semVariantesB3(resultados: List<ResultadoBusca>): List<ResultadoBusca> {
        val simbolos = resultados.map { it.ativo.codigo }.toSet()
        return resultados.filterNot { r ->
            val m = VARIANTE_B3.matchEntire(r.ativo.codigo)
            m != null && "${m.groupValues[1]}.SA" in simbolos
        }
    }

    // -------------------------------------------------------------------------------------------
    // Detalhes (gráfico + estatísticas)
    // -------------------------------------------------------------------------------------------

    /**
     * Cotação atual (com fallback de cache), série do gráfico no [periodo] e estatísticas.
     * O gráfico e as estatísticas só existem online; se falharem, [Detalhes.erroSerie] = true.
     */
    suspend fun detalhes(ativo: Ativo, periodo: Periodo): Detalhes = coroutineScope {
        val cotacao = async { buscar(ativo) }
        val periodos = periodosSuportados(ativo)
        val (serie, estatisticas) = try {
            when (val fonte = ativo.fonte) {
                is Fonte.Yahoo -> detalhesYahoo(ativo, fonte, periodo)
                is Fonte.Awesome -> detalhesAwesome(ativo, fonte, periodo)
                is Fonte.Bcb -> detalhesBcb(ativo, fonte, periodo)
                is Fonte.Anbima -> detalhesAnbima(fonte, periodo)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao buscar detalhes de '${ativo.id}'", e)
            return@coroutineScope Detalhes(cotacao.await(), emptyList(), emptyList(), periodos, true)
        }
        Detalhes(cotacao.await(), serie, estatisticas, periodos, false)
    }

    /**
     * Títulos da ANBIMA: o endereço público só mantém os arquivos dos últimos ~5 dias úteis
     * (os mais antigos respondem 404), então só há o período de 5 dias.
     */
    fun periodosSuportados(ativo: Ativo): List<Periodo> =
        if (ativo.fonte is Fonte.Anbima) listOf(Periodo.CINCO_DIAS)
        else Periodo.entries

    private suspend fun detalhesYahoo(
        ativo: Ativo,
        fonte: Fonte.Yahoo,
        periodo: Periodo,
    ): Pair<List<Ponto>, List<Pair<String, String>>> = withContext(Dispatchers.IO) {
        val (range, intervalo) = when (periodo) {
            Periodo.CINCO_DIAS -> "5d" to "30m"
            Periodo.UM_MES -> "1mo" to "1d"
            Periodo.SEIS_MESES -> "6mo" to "1d"
            Periodo.UM_ANO -> "1y" to "1d"
        }
        val simbolo = URLEncoder.encode(fonte.simbolo, "UTF-8")
        val resultado = JSONObject(
            Http.get("https://query1.finance.yahoo.com/v8/finance/chart/$simbolo?range=$range&interval=$intervalo")
        ).getJSONObject("chart").getJSONArray("result").getJSONObject(0)

        // Formato: {"timestamp":[...], "indicators":{"quote":[{"close":[..., null, ...]}]}, "meta":{...}}
        val tempos = resultado.optJSONArray("timestamp") ?: JSONArray()
        val fechamentos = resultado.getJSONObject("indicators").getJSONArray("quote")
            .getJSONObject(0).getJSONArray("close")
        val serie = (0 until minOf(tempos.length(), fechamentos.length()))
            .filterNot { fechamentos.isNull(it) }
            .map { Ponto(tempos.getLong(it) * 1000, fechamentos.getDouble(it)) }

        // Estatísticas do "meta"; preços formatados como a cotação (moeda/índice do próprio ativo)
        val meta = resultado.getJSONObject("meta")
        val base = Cotacao(
            valor = 0.0, variacaoPct = null, atualizadoEm = 0,
            moeda = meta.optString("currency").ifEmpty { null },
            indice = meta.optString("instrumentType") == "INDEX",
        )
        fun preco(chave: String) = meta.optDouble(chave).takeIf { !it.isNaN() }
            ?.let { ativo.formatar(base.copy(valor = it)) }

        val estatisticas = listOfNotNull(
            preco("regularMarketDayHigh")?.let { "Máx. do dia" to it },
            preco("regularMarketDayLow")?.let { "Mín. do dia" to it },
            preco("fiftyTwoWeekHigh")?.let { "Máx. 52 sem." to it },
            preco("fiftyTwoWeekLow")?.let { "Mín. 52 sem." to it },
            meta.optDouble("regularMarketVolume").takeIf { !it.isNaN() && it > 0 }
                ?.let { "Volume" to Formatos.volume(it) },
            meta.optString("fullExchangeName").ifEmpty { null }?.let { "Bolsa" to it },
        )
        serie to estatisticas
    }

    private suspend fun detalhesAwesome(
        ativo: Ativo,
        fonte: Fonte.Awesome,
        periodo: Periodo,
    ): Pair<List<Ponto>, List<Pair<String, String>>> = withContext(Dispatchers.IO) {
        // Cotações de fechamento diárias, da mais recente para a mais antiga
        val dias = when (periodo) {
            Periodo.CINCO_DIAS -> 5
            Periodo.UM_MES -> 22
            Periodo.SEIS_MESES -> 130
            Periodo.UM_ANO -> 260
        }
        val array = JSONArray(Http.get("https://economia.awesomeapi.com.br/json/daily/${fonte.par}/$dias"))
        val serie = (0 until array.length())
            .map { array.getJSONObject(it) }
            .mapNotNull { o ->
                val bid = o.optString("bid").toDoubleOrNull() ?: return@mapNotNull null
                Ponto(o.optLong("timestamp") * 1000, bid)
            }
            .sortedBy { it.epochMs }

        // O primeiro item é o dia atual: máxima, mínima, compra e venda
        val hoje = array.optJSONObject(0)
        fun reais(chave: String) = hoje?.optString(chave)?.toDoubleOrNull()?.let { Formatos.reais(it) }
        val estatisticas = listOfNotNull(
            reais("high")?.let { "Máx. do dia" to it },
            reais("low")?.let { "Mín. do dia" to it },
            reais("bid")?.let { "Compra" to it },
            reais("ask")?.let { "Venda" to it },
        )
        serie to estatisticas
    }

    private suspend fun detalhesBcb(
        ativo: Ativo,
        fonte: Fonte.Bcb,
        periodo: Periodo,
    ): Pair<List<Ponto>, List<Pair<String, String>>> = withContext(Dispatchers.IO) {
        // Séries mensais (ex.: IPCA) precisam de uma janela mínima para ter algum ponto
        val janela = maxOf(periodo.diasCorridos, fonte.janelaDias)
        val hoje = LocalDate.now(FUSO_BRASILIA)
        val url = "https://api.bcb.gov.br/dados/serie/bcdata.sgs.${fonte.serie}/dados?formato=json" +
            "&dataInicial=" + hoje.minusDays(janela).format(FORMATO_DATA) +
            "&dataFinal=" + hoje.format(FORMATO_DATA)

        val array = JSONArray(Http.get(url))
        val serie = (0 until array.length()).map { array.getJSONObject(it) }.map {
            val data = LocalDate.parse(it.getString("data"), FORMATO_DATA)
            Ponto(data.atStartOfDay(FUSO_BRASILIA).toInstant().toEpochMilli(), it.getString("valor").toDouble())
        }
        val estatisticas = listOfNotNull(
            serie.lastOrNull()?.let { "Último dado" to Formatos.data(it.epochMs) },
            serie.takeIf { it.size >= 2 }?.let { s ->
                "Máx. no período" to ativo.formatar(Cotacao(s.maxOf { it.valor }, null, 0))
            },
            serie.takeIf { it.size >= 2 }?.let { s ->
                "Mín. no período" to ativo.formatar(Cotacao(s.minOf { it.valor }, null, 0))
            },
            "Série SGS" to fonte.serie.toString(),
        )
        serie to estatisticas
    }

    private suspend fun detalhesAnbima(
        fonte: Fonte.Anbima,
        periodo: Periodo,
    ): Pair<List<Ponto>, List<Pair<String, String>>> {
        val historico = Anbima.historico(fonte.titulo, fonte.vencimento, periodo.diasCorridos)
            .let { if (periodo == Periodo.CINCO_DIAS) it.takeLast(5) else it }
        if (historico.isEmpty()) throw IllegalStateException("Sem histórico da ANBIMA")

        val serie = historico.map {
            Ponto(it.dataReferencia.atStartOfDay(FUSO_BRASILIA).toInstant().toEpochMilli(), it.taxaIndicativa)
        }
        val ultimo = historico.last()
        val estatisticas = listOfNotNull(
            "Vencimento" to Formatos.data(Anbima.dataVencimento(fonte.vencimento)),
            ultimo.taxaCompra?.let { "Taxa de compra" to Formatos.percentual(it) },
            ultimo.taxaVenda?.let { "Taxa de venda" to Formatos.percentual(it) },
            ultimo.pu?.let { "PU" to "R$ " + Formatos.numero(it) },
            "Data de referência" to Formatos.data(ultimo.dataReferencia),
            "Fonte" to "ANBIMA (taxa indicativa)",
        )
        return serie to estatisticas
    }

    private fun salvar(chave: String, c: Cotacao) {
        prefs.edit()
            .putString("$chave.valor", c.valor.toString())
            .putString("$chave.variacao", c.variacaoPct?.toString())
            .putLong("$chave.em", c.atualizadoEm)
            .putString("$chave.moeda", c.moeda)
            .putBoolean("$chave.indice", c.indice)
            .apply()
    }

    private fun ler(chave: String): Cotacao? {
        val valor = prefs.getString("$chave.valor", null)?.toDoubleOrNull() ?: return null
        return Cotacao(
            valor = valor,
            variacaoPct = prefs.getString("$chave.variacao", null)?.toDoubleOrNull(),
            atualizadoEm = prefs.getLong("$chave.em", 0L),
            doCache = true,
            moeda = prefs.getString("$chave.moeda", null),
            indice = prefs.getBoolean("$chave.indice", false),
        )
    }

    companion object {
        private const val TAG = "MercadoRepository"
        private const val PREFS_NOME = "mercado_cache"
        private const val MAX_RESULTADOS = 10
        private val VARIANTE_B3 = Regex("""(.+\d)[FQ]\.SA""")

        /** Tipos do Yahoo aceitos na busca livre (exclui futuros, opções etc.). */
        private val TIPOS_ACEITOS =
            setOf("EQUITY", "ETF", "INDEX", "MUTUALFUND", "CRYPTOCURRENCY", "CURRENCY")

        val FUSO_BRASILIA: ZoneId = ZoneId.of("America/Sao_Paulo")
        private val FORMATO_DATA: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }
}
