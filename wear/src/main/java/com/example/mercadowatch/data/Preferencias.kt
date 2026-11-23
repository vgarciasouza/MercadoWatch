package com.example.mercadowatch.data

import android.content.Context

/**
 * Guarda quais ativos ocupam as 3 posições do tile.
 *
 * Cada posição pode ter um ativo do catálogo ou um ticker qualquer da busca livre.
 * O tile sempre mostra exatamente [POSICOES] ativos, sem repetição. Se uma posição estiver
 * vazia ou com um id desconhecido (ex.: ativo removido do catálogo), usa-se o padrão.
 */
class Preferencias(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NOME, Context.MODE_PRIVATE)

    /** Os 3 ativos atualmente selecionados, na ordem de exibição. */
    fun ativosDoTile(): List<Ativo> {
        val escolhidos = (0 until POSICOES).map { i ->
            Ativo.porId(
                prefs.getString(chave(i), null),
                prefs.getString(chaveNome(i), null),
            ) ?: PADRAO[i]
        }
        // Proteção contra repetição (não deveria acontecer pela tela de configuração)
        return if (escolhidos.distinct().size == POSICOES) escolhidos else PADRAO
    }

    /**
     * Coloca [ativo] na [posicao]. Se o ativo já estiver em outra posição, as duas trocam
     * de lugar — assim nunca há repetição e sempre há 3 ativos na tela.
     */
    fun definir(posicao: Int, ativo: Ativo) {
        val atuais = ativosDoTile().toMutableList()
        val posicaoAnterior = atuais.indexOf(ativo)
        if (posicaoAnterior >= 0) atuais[posicaoAnterior] = atuais[posicao]
        atuais[posicao] = ativo

        prefs.edit().apply {
            atuais.forEachIndexed { i, a ->
                putString(chave(i), a.id)
                // Tickers da busca livre precisam do nome salvo (o id só tem o símbolo)
                putString(chaveNome(i), if (a.personalizado) a.nome else null)
            }
        }.apply()
    }

    private fun chave(posicao: Int) = "posicao_$posicao"
    private fun chaveNome(posicao: Int) = "posicao_${posicao}_nome"

    companion object {
        const val POSICOES = 3
        val PADRAO = listOf(Ativo.SELIC, Ativo.DOLAR, Ativo.IBOVESPA)
        private const val PREFS_NOME = "configuracao"
    }
}
