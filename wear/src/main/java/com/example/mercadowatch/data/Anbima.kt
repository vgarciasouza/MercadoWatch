package com.example.mercadowatch.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** Uma linha do arquivo de títulos públicos da ANBIMA (um título + vencimento num dia). */
data class TituloAnbima(
    /** "NTN-B", "NTN-F", "LTN", "LFT"… */
    val titulo: String,
    /** Data de vencimento no formato yyyyMMdd, ex.: "20350515" */
    val vencimento: String,
    val dataReferencia: LocalDate,
    val taxaCompra: Double?,
    val taxaVenda: Double?,
    /** Taxa indicativa em % a.a. (a referência de mercado) */
    val taxaIndicativa: Double,
    /** Preço unitário em R$ */
    val pu: Double?,
)

/**
 * Taxas indicativas de títulos públicos no mercado secundário, publicadas diariamente pela
 * ANBIMA em https://www.anbima.com.br/informacoes/merc-sec/arqs/ms{yyMMdd}.txt
 *
 * - Texto separado por "@", em ISO-8859-1, decimal com vírgula (~7 KB por dia).
 * - Só os arquivos dos últimos ~5 dias úteis ficam disponíveis (os mais antigos dão 404).
 * - Publicado no fim do dia útil; fins de semana, feriados e o dia corrente (antes da
 *   publicação) respondem 404 — por isso a busca volta dia a dia até achar um arquivo.
 * - Arquivos já baixados ficam em memória: um dia passado nunca muda.
 */
object Anbima {

    private const val URL_BASE = "https://www.anbima.com.br/informacoes/merc-sec/arqs/ms"
    private const val MAX_DIAS_ATRAS = 10L
    private val FUSO: ZoneId = ZoneId.of("America/Sao_Paulo")
    private val FORMATO_ARQUIVO = DateTimeFormatter.ofPattern("yyMMdd")
    private val FORMATO_VENCIMENTO = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** Arquivos já baixados, por data. */
    private val arquivos = ConcurrentHashMap<LocalDate, List<TituloAnbima>>()

    /** Datas passadas sem arquivo (404): não adianta tentar de novo. */
    private val semArquivo = ConcurrentHashMap.newKeySet<LocalDate>()

    /** Títulos que interessam ao app. */
    val TITULOS = listOf("NTN-B", "NTN-F")

    /**
     * Taxa mais recente de um título e a variação em pontos percentuais em relação ao
     * arquivo anterior (null se não houver anterior com o mesmo título).
     */
    suspend fun taxa(titulo: String, vencimento: String): Pair<TituloAnbima, Double?> {
        val (maisRecente, anterior) = ultimosArquivos(2).let { it.getOrNull(0) to it.getOrNull(1) }
        val atual = maisRecente?.second?.encontrar(titulo, vencimento)
            ?: throw IllegalStateException("$titulo $vencimento não encontrado na ANBIMA")
        val antes = anterior?.second?.encontrar(titulo, vencimento)
        return atual to antes?.let { atual.taxaIndicativa - it.taxaIndicativa }
    }

    /** NTN-B e NTN-F do arquivo mais recente, ordenados por título e vencimento. */
    suspend fun titulosDisponiveis(): List<TituloAnbima> =
        ultimosArquivos(1).firstOrNull()?.second.orEmpty()
            .filter { it.titulo in TITULOS }
            .sortedWith(compareBy({ it.titulo }, { it.vencimento }))

    /**
     * Histórico da taxa indicativa: um ponto por dia útil com arquivo, nos últimos
     * [diasCorridos] dias. Os dias são baixados em paralelo (no máximo 4 de cada vez).
     */
    suspend fun historico(titulo: String, vencimento: String, diasCorridos: Long): List<TituloAnbima> =
        coroutineScope {
            val hoje = LocalDate.now(FUSO)
            val limite = Semaphore(4)
            (0..diasCorridos)
                .map { hoje.minusDays(it) }
                .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
                .map { dia -> async { limite.withPermit { arquivo(dia) } } }
                .awaitAll()
                .mapNotNull { it?.encontrar(titulo, vencimento) }
                .sortedBy { it.dataReferencia }
        }

    /** Converte "20350515" em LocalDate. */
    fun dataVencimento(vencimento: String): LocalDate = LocalDate.parse(vencimento, FORMATO_VENCIMENTO)

    /** Os [quantidade] arquivos mais recentes (data, linhas), do mais novo para o mais velho. */
    private suspend fun ultimosArquivos(quantidade: Int): List<Pair<LocalDate, List<TituloAnbima>>> {
        val hoje = LocalDate.now(FUSO)
        val encontrados = mutableListOf<Pair<LocalDate, List<TituloAnbima>>>()
        var dia = hoje
        while (encontrados.size < quantidade && !dia.isBefore(hoje.minusDays(MAX_DIAS_ATRAS))) {
            if (dia.dayOfWeek != DayOfWeek.SATURDAY && dia.dayOfWeek != DayOfWeek.SUNDAY) {
                arquivo(dia)?.let { encontrados += dia to it }
            }
            dia = dia.minusDays(1)
        }
        if (encontrados.isEmpty()) throw IllegalStateException("Nenhum arquivo da ANBIMA recente")
        return encontrados
    }

    /** Linhas do arquivo de [dia], ou null se não existir (404). Usa o cache em memória. */
    private suspend fun arquivo(dia: LocalDate): List<TituloAnbima>? {
        arquivos[dia]?.let { return it }
        if (dia in semArquivo) return null
        return withContext(Dispatchers.IO) {
            try {
                val texto = Http.get(URL_BASE + dia.format(FORMATO_ARQUIVO) + ".txt", Charsets.ISO_8859_1)
                parse(texto).also { arquivos[dia] = it }
            } catch (e: HttpException) {
                if (e.codigo != 404) throw e
                // O arquivo de hoje ainda pode ser publicado; só memoriza a ausência de dias passados
                if (dia.isBefore(LocalDate.now(FUSO))) semArquivo += dia
                null
            }
        }
    }

    /**
     * Linhas de dados: Titulo@Data Referencia@Codigo SELIC@Data Base@Data Vencimento@
     * Tx. Compra@Tx. Venda@Tx. Indicativas@PU@...
     */
    private fun parse(texto: String): List<TituloAnbima> = texto.lineSequence()
        .map { it.trim().split('@') }
        .filter { it.size >= 9 && it[1].length == 8 && it[1].all(Char::isDigit) }
        .mapNotNull { c ->
            val indicativa = numero(c[7]) ?: return@mapNotNull null
            TituloAnbima(
                titulo = c[0],
                vencimento = c[4],
                dataReferencia = LocalDate.parse(c[1], FORMATO_VENCIMENTO),
                taxaCompra = numero(c[5]),
                taxaVenda = numero(c[6]),
                taxaIndicativa = indicativa,
                pu = numero(c[8]),
            )
        }
        .toList()

    /** "7,9315" -> 7.9315; "--" ou vazio -> null */
    private fun numero(texto: String): Double? = texto.trim().replace(',', '.').toDoubleOrNull()

    private fun List<TituloAnbima>.encontrar(titulo: String, vencimento: String) =
        firstOrNull { it.titulo == titulo && it.vencimento == vencimento }
}
