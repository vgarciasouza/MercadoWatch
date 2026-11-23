package com.example.mercadowatch.data

import java.text.Normalizer

/** De onde vem a cotação de um ativo. */
sealed interface Fonte {
    /** Série do SGS/Banco Central; [janelaDias] = quantos dias para trás consultar. */
    data class Bcb(val serie: Int, val janelaDias: Long) : Fonte

    /** Par de moedas da AwesomeAPI, ex.: "USD-BRL". */
    data class Awesome(val par: String) : Fonte

    /** Símbolo do Yahoo Finance, ex.: "^BVSP" ou "PETR4.SA". */
    data class Yahoo(val simbolo: String) : Fonte
}

/** Como o valor é exibido no relógio. */
enum class Formato {
    PERCENTUAL,
    REAIS,
    PONTOS,

    /** Decidido pela cotação: índice em pontos, senão pela moeda (R$, US$ ou número puro). */
    AUTOMATICO,
}

/**
 * Um ativo que pode ocupar uma posição do tile: ou do [CATALOGO] fixo, ou um ticker qualquer
 * do Yahoo Finance encontrado pela busca livre (ver [doYahoo]).
 *
 * Igualdade é pelo [id], que é gravado nas preferências e usado como chave do cache.
 * Os ids do catálogo não devem ser alterados depois de publicados.
 *
 * @param rotulo nome curto exibido no tile (até ~6 caracteres)
 * @param nome nome completo exibido na tela de configuração
 */
class Ativo(
    val id: String,
    val rotulo: String,
    val nome: String,
    val fonte: Fonte,
    val formato: Formato,
) {
    /** true se veio da busca livre (não faz parte do catálogo). */
    val personalizado: Boolean get() = id.startsWith(PREFIXO_YAHOO)

    /** Valor formatado no padrão brasileiro, conforme o [formato] do ativo. */
    fun formatar(cotacao: Cotacao): String = when (formato) {
        Formato.PERCENTUAL -> Formatos.percentual(cotacao.valor)
        Formato.REAIS -> Formatos.reais(cotacao.valor)
        Formato.PONTOS -> Formatos.pontos(cotacao.valor)
        Formato.AUTOMATICO -> when {
            cotacao.indice -> Formatos.pontos(cotacao.valor)
            cotacao.moeda == "BRL" -> Formatos.reais(cotacao.valor)
            cotacao.moeda == "USD" -> Formatos.dolares(cotacao.valor)
            else -> Formatos.numero(cotacao.valor)
        }
    }

    /** Código na fonte (série, par ou ticker), usado também na busca. */
    val codigo: String
        get() = when (fonte) {
            is Fonte.Bcb -> fonte.serie.toString()
            is Fonte.Awesome -> fonte.par
            is Fonte.Yahoo -> fonte.simbolo
        }

    /**
     * true se o [termo] aparece no nome, rótulo ou código do ativo.
     * Ignora maiúsculas e acentos: "dolar" encontra "Dólar", "pet" encontra "PETR4".
     */
    fun corresponde(termo: String): Boolean {
        val t = normalizar(termo)
        if (t.isEmpty()) return true
        return listOf(nome, rotulo, id, codigo).any { normalizar(it).contains(t) }
    }

    override fun equals(other: Any?): Boolean = other is Ativo && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "Ativo($id)"

    companion object {
        private const val PREFIXO_YAHOO = "yahoo:"

        // Banco Central (SGS). A Selic usa janela curta porque a série tem datas futuras.
        val SELIC = Ativo("selic", "Selic", "Selic meta", Fonte.Bcb(432, 10), Formato.PERCENTUAL)
        val CDI = Ativo("cdi", "CDI", "CDI (anualizado)", Fonte.Bcb(4389, 10), Formato.PERCENTUAL)
        val IPCA = Ativo("ipca12m", "IPCA", "IPCA 12 meses", Fonte.Bcb(13522, 120), Formato.PERCENTUAL)

        // AwesomeAPI
        val DOLAR = Ativo("dolar", "Dólar", "Dólar comercial", Fonte.Awesome("USD-BRL"), Formato.REAIS)
        val EURO = Ativo("euro", "Euro", "Euro", Fonte.Awesome("EUR-BRL"), Formato.REAIS)
        val BITCOIN = Ativo("bitcoin", "BTC", "Bitcoin (R$)", Fonte.Awesome("BTC-BRL"), Formato.REAIS)

        // Yahoo Finance
        val IBOVESPA = Ativo("ibovespa", "Ibov", "Ibovespa", Fonte.Yahoo("^BVSP"), Formato.PONTOS)
        val SP500 = Ativo("sp500", "S&P", "S&P 500", Fonte.Yahoo("^GSPC"), Formato.PONTOS)
        val PETR4 = Ativo("petr4", "PETR4", "Petrobras PN", Fonte.Yahoo("PETR4.SA"), Formato.REAIS)
        val VALE3 = Ativo("vale3", "VALE3", "Vale ON", Fonte.Yahoo("VALE3.SA"), Formato.REAIS)
        val ITUB4 = Ativo("itub4", "ITUB4", "Itaú Unibanco PN", Fonte.Yahoo("ITUB4.SA"), Formato.REAIS)
        val BBAS3 = Ativo("bbas3", "BBAS3", "Banco do Brasil ON", Fonte.Yahoo("BBAS3.SA"), Formato.REAIS)
        val WEGE3 = Ativo("wege3", "WEGE3", "WEG ON", Fonte.Yahoo("WEGE3.SA"), Formato.REAIS)

        /** Catálogo fixo, na ordem exibida na tela de configuração. */
        val CATALOGO: List<Ativo> = listOf(
            SELIC, CDI, IPCA, DOLAR, EURO, BITCOIN,
            IBOVESPA, SP500, PETR4, VALE3, ITUB4, BBAS3, WEGE3,
        )

        /** Mantido para compatibilidade com o código que usava o antigo enum. */
        val entries: List<Ativo> get() = CATALOGO

        /**
         * Ativo de um ticker qualquer do Yahoo Finance (busca livre).
         * Se o ticker já estiver no catálogo, devolve a entrada do catálogo.
         */
        fun doYahoo(simbolo: String, nome: String? = null): Ativo {
            CATALOGO.firstOrNull { (it.fonte as? Fonte.Yahoo)?.simbolo == simbolo }?.let { return it }
            return Ativo(
                id = PREFIXO_YAHOO + simbolo,
                rotulo = rotuloDoSimbolo(simbolo),
                nome = nome?.takeIf { it.isNotBlank() } ?: simbolo,
                fonte = Fonte.Yahoo(simbolo),
                formato = Formato.AUTOMATICO,
            )
        }

        /**
         * Recupera um ativo pelo [id] gravado. Para tickers da busca livre, [nome] é o nome
         * salvo junto (o id só carrega o símbolo).
         */
        fun porId(id: String?, nome: String? = null): Ativo? {
            if (id == null) return null
            if (id.startsWith(PREFIXO_YAHOO)) return doYahoo(id.removePrefix(PREFIXO_YAHOO), nome)
            return CATALOGO.firstOrNull { it.id == id }
        }

        /** Filtra o catálogo pelo termo digitado (vazio = todos). */
        fun buscar(termo: String): List<Ativo> = CATALOGO.filter { it.corresponde(termo) }

        /** "PETR3.SA" -> "PETR3"; "^GSPC" -> "GSPC"; "EURUSD=X" -> "EURUSD"; máx. 6 caracteres. */
        private fun rotuloDoSimbolo(simbolo: String): String =
            simbolo.removePrefix("^").substringBefore('.').substringBefore('=').take(6)

        private fun normalizar(texto: String): String =
            Normalizer.normalize(texto.trim(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase()
    }
}
