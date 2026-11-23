package com.example.mercadowatch.data

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Formatação de números no padrão brasileiro (vírgula decimal, ponto de milhar). */
object Formatos {

    private val simbolos = DecimalFormatSymbols(Locale.forLanguageTag("pt-BR"))
    private val formatoHora = DateTimeFormatter.ofPattern("HH:mm")

    /** 15.0 -> "15,00%" */
    fun percentual(valor: Double): String = DecimalFormat("0.00", simbolos).format(valor) + "%"

    /**
     * 5.9897 -> "R$ 5,99"; 12345.6 -> "R$ 12.346"; 520123.0 -> "R$ 520 mil".
     * Valores altos (ex.: Bitcoin) são compactados para caber na linha do tile.
     */
    fun reais(valor: Double): String = "R$ " + when {
        valor >= 100_000 -> DecimalFormat("#,##0", simbolos).format(valor / 1000) + " mil"
        valor >= 10_000 -> DecimalFormat("#,##0", simbolos).format(valor)
        else -> DecimalFormat("0.00", simbolos).format(valor)
    }

    /** 230.1 -> "US$ 230,10" (ações americanas na busca livre) */
    fun dolares(valor: Double): String = "US$ " + numero(valor)

    /** Número com 2 casas (outras moedas); valores altos sem casas: 12345.6 -> "12.346" */
    fun numero(valor: Double): String =
        if (valor >= 10_000) DecimalFormat("#,##0", simbolos).format(valor)
        else DecimalFormat("0.00", simbolos).format(valor)

    /** 164783.48 -> "164.783" */
    fun pontos(valor: Double): String = DecimalFormat("#,##0", simbolos).format(valor)

    /** 0.687 -> "+0,69%"; -0.957 -> "-0,96%" */
    fun variacao(valor: Double): String =
        DecimalFormat("+0.00;-0.00", simbolos).format(valor) + "%"

    /** epoch ms -> "13:55" no horário de Brasília */
    fun hora(epochMs: Long): String =
        formatoHora.format(Instant.ofEpochMilli(epochMs).atZone(MercadoRepository.FUSO_BRASILIA))
}
