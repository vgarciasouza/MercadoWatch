package com.example.mercadowatch.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Guarda a lista de ativos exibida no tile, na ordem de exibição.
 *
 * - A lista tem entre [MIN_ATIVOS] e [MAX_ATIVOS] itens, sem repetição.
 *   O máximo existe porque o tile não rola: com mais de 6 linhas, as de cima e de baixo
 *   seriam cortadas pela borda redonda.
 * - Cada item pode ser do catálogo ou um ticker qualquer da busca livre.
 * - Gravada como JSON: [{"id":"selic"}, {"id":"yahoo:HGLG11.SA","nome":"FII HGLG PAXCI"}, ...]
 *   (o nome só é necessário para tickers da busca livre, cujo id carrega apenas o símbolo).
 */
class Preferencias(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NOME, Context.MODE_PRIVATE)

    /** Os ativos atualmente no tile, na ordem de exibição. */
    fun ativosDoTile(): List<Ativo> {
        val json = prefs.getString(CHAVE_LISTA, null) ?: return migrarPosicoesFixas() ?: PADRAO
        val lista = try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val item = array.getJSONObject(i)
                Ativo.porId(item.optString("id"), item.optString("nome").ifEmpty { null })
            }
        } catch (e: Exception) {
            emptyList()
        }
        // Ativos removidos do catálogo somem; se não sobrar nada, volta ao padrão
        return lista.distinct().take(MAX_ATIVOS).ifEmpty { PADRAO }
    }

    /** true se ainda cabe mais um ativo no tile. */
    fun podeAdicionar(): Boolean = ativosDoTile().size < MAX_ATIVOS

    /** Adiciona [ativo] ao final da lista. Não faz nada se já estiver nela ou se estiver cheia. */
    fun adicionar(ativo: Ativo) {
        val atuais = ativosDoTile()
        if (ativo in atuais || atuais.size >= MAX_ATIVOS) return
        salvar(atuais + ativo)
    }

    /** Remove o ativo da [posicao]. Não remove o último (o tile precisa de pelo menos um). */
    fun remover(posicao: Int) {
        val atuais = ativosDoTile()
        if (atuais.size <= MIN_ATIVOS || posicao !in atuais.indices) return
        salvar(atuais.filterIndexed { i, _ -> i != posicao })
    }

    /**
     * Coloca [ativo] na [posicao]. Se o ativo já estiver em outra posição, as duas trocam
     * de lugar — assim nunca há repetição.
     */
    fun substituir(posicao: Int, ativo: Ativo) {
        val atuais = ativosDoTile().toMutableList()
        if (posicao !in atuais.indices) return
        val posicaoAnterior = atuais.indexOf(ativo)
        if (posicaoAnterior >= 0) atuais[posicaoAnterior] = atuais[posicao]
        atuais[posicao] = ativo
        salvar(atuais)
    }

    /** Move o ativo da [posicao] uma casa para cima (-1) ou para baixo (+1). */
    fun mover(posicao: Int, direcao: Int) {
        val atuais = ativosDoTile().toMutableList()
        val destino = posicao + direcao
        if (posicao !in atuais.indices || destino !in atuais.indices) return
        atuais[posicao] = atuais[destino].also { atuais[destino] = atuais[posicao] }
        salvar(atuais)
    }

    private fun salvar(ativos: List<Ativo>) {
        val array = JSONArray()
        ativos.forEach { a ->
            array.put(JSONObject().apply {
                put("id", a.id)
                if (a.personalizado) put("nome", a.nome)
            })
        }
        prefs.edit().putString(CHAVE_LISTA, array.toString()).apply()
    }

    /**
     * Converte o formato antigo (3 posições fixas "posicao_0..2") para a lista.
     * Devolve null se não houver configuração antiga.
     */
    private fun migrarPosicoesFixas(): List<Ativo>? {
        if (!prefs.contains("posicao_0")) return null
        val antigos = (0 until 3).mapNotNull { i ->
            Ativo.porId(prefs.getString("posicao_$i", null), prefs.getString("posicao_${i}_nome", null))
        }.distinct().ifEmpty { PADRAO }

        salvar(antigos)
        prefs.edit().apply {
            (0 until 3).forEach { i -> remove("posicao_$i"); remove("posicao_${i}_nome") }
        }.apply()
        return antigos
    }

    companion object {
        const val MIN_ATIVOS = 1
        const val MAX_ATIVOS = 6
        val PADRAO = listOf(Ativo.SELIC, Ativo.DOLAR, Ativo.IBOVESPA)
        private const val PREFS_NOME = "configuracao"
        private const val CHAVE_LISTA = "ativos"
    }
}
