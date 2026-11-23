package com.example.mercadowatch.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Uma cotação exibida no relógio.
 *
 * @param valor valor atual (taxa em %, preço em R$ ou índice em pontos)
 * @param variacaoPct variação do dia em %, quando disponível (as séries do BCB não têm)
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

/** Um resultado da busca livre de tickers no Yahoo Finance. */
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
            }
        }
        Cotacao(
            valor = leitura.valor,
            variacaoPct = leitura.variacao,
            atualizadoEm = System.currentTimeMillis(),
            moeda = leitura.moeda,
            indice = leitura.indice,
        ).also { salvar(ativo.id, it) }
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
        val array = JSONArray(httpGet(url))
        if (array.length() == 0) throw IllegalStateException("Série ${fonte.serie} vazia")
        return Leitura(array.getJSONObject(array.length() - 1).getString("valor").toDouble(), null)
    }

    /** Cotação de compra (bid) e variação do dia na AwesomeAPI. */
    private fun buscarAwesome(fonte: Fonte.Awesome): Leitura {
        // Formato: {"USDBRL":{"bid":"5.98","pctChange":"0.68", ...}}
        val json = JSONObject(httpGet("https://economia.awesomeapi.com.br/json/last/${fonte.par}"))
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
        val meta = JSONObject(httpGet(url))
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
        val quotes = JSONObject(httpGet(url)).optJSONArray("quotes") ?: JSONArray()
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

    /** GET simples com HttpURLConnection; lança exceção se o status não for 200. */
    private fun httpGet(url: String): String {
        val conexao = URL(url).openConnection() as HttpURLConnection
        try {
            conexao.requestMethod = "GET"
            conexao.connectTimeout = TIMEOUT_MS
            conexao.readTimeout = TIMEOUT_MS
            conexao.setRequestProperty("Accept", "application/json")
            // O Yahoo recusa requisições sem User-Agent de navegador
            conexao.setRequestProperty("User-Agent", "Mozilla/5.0")

            val codigo = conexao.responseCode
            if (codigo != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP $codigo em $url")
            }
            return conexao.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conexao.disconnect()
        }
    }

    companion object {
        private const val TAG = "MercadoRepository"
        private const val PREFS_NOME = "mercado_cache"
        private const val TIMEOUT_MS = 10_000
        private const val MAX_RESULTADOS = 10
        private val VARIANTE_B3 = Regex("""(.+\d)[FQ]\.SA""")

        /** Tipos do Yahoo aceitos na busca livre (exclui futuros, opções etc.). */
        private val TIPOS_ACEITOS =
            setOf("EQUITY", "ETF", "INDEX", "MUTUALFUND", "CRYPTOCURRENCY", "CURRENCY")

        val FUSO_BRASILIA: ZoneId = ZoneId.of("America/Sao_Paulo")
        private val FORMATO_DATA: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }
}
