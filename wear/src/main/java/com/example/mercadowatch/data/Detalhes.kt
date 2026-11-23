package com.example.mercadowatch.data

/** Período do gráfico na tela de detalhes. */
enum class Periodo(val rotulo: String, val diasCorridos: Long) {
    CINCO_DIAS("5D", 7),
    UM_MES("1M", 31),
    SEIS_MESES("6M", 183),
    UM_ANO("1A", 366),
}

/** Um ponto do gráfico: momento (epoch ms) e valor. */
data class Ponto(val epochMs: Long, val valor: Double)

/**
 * Tudo o que a tela de detalhes mostra de um ativo.
 *
 * @param cotacao valor atual (com o mesmo fallback de cache do tile); null se nunca foi obtido
 * @param serie pontos do gráfico no período, em ordem cronológica (vazio se a busca falhou)
 * @param estatisticas pares "rótulo" to "valor" já formatados (ex.: "Máx. do dia" to "R$ 42,35")
 * @param periodos períodos que a fonte do ativo suporta
 * @param erroSerie true se não foi possível buscar o gráfico (ex.: offline)
 */
data class Detalhes(
    val cotacao: Cotacao?,
    val serie: List<Ponto>,
    val estatisticas: List<Pair<String, String>>,
    val periodos: List<Periodo>,
    val erroSerie: Boolean,
)
