package com.example.mercadowatch.complication

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.example.mercadowatch.data.Ativo
import com.example.mercadowatch.data.Formatos
import com.example.mercadowatch.data.MercadoRepository

/**
 * Complication que mostra a taxa Selic meta (série 432 do SGS/Banco Central).
 *
 * - Atualiza a cada 1 hora (período definido no manifest).
 * - A busca e o cache local ficam no [MercadoRepository]: se a requisição falhar
 *   (ex.: relógio offline), é exibido o último valor obtido.
 */
class MainComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        // Valor de exemplo exibido no seletor de complications
        return criarComplication(15.0)
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val selic = MercadoRepository(this).buscar(Ativo.SELIC)
        return criarComplication(selic?.valor)
    }

    /** Monta a complication SHORT_TEXT com título "Selic" e o valor no formato brasileiro. */
    private fun criarComplication(valor: Double?): ComplicationData {
        val texto = valor?.let { Formatos.percentual(it) } ?: SEM_VALOR
        val descricao = valor?.let { "Selic meta: $texto" } ?: "Selic meta indisponível"

        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(texto).build(),
            contentDescription = PlainComplicationText.Builder(descricao).build()
        )
            .setTitle(PlainComplicationText.Builder("Selic").build())
            .build()
    }

    companion object {
        private const val SEM_VALOR = "--"
    }
}
