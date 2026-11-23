package com.example.mercadowatch.data

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/** Erro HTTP com o código de status (ex.: 404 quando a ANBIMA não publicou o arquivo do dia). */
class HttpException(val codigo: Int, url: String) : Exception("HTTP $codigo em $url")

/** Requisições HTTP simples com HttpURLConnection (sem bibliotecas de rede). */
internal object Http {

    private const val TIMEOUT_MS = 10_000

    /**
     * GET que devolve o corpo como texto; lança [HttpException] se o status não for 200.
     * Bloqueante: chamar a partir de Dispatchers.IO.
     */
    fun get(url: String, charset: Charset = Charsets.UTF_8): String {
        val conexao = URL(url).openConnection() as HttpURLConnection
        try {
            conexao.requestMethod = "GET"
            conexao.connectTimeout = TIMEOUT_MS
            conexao.readTimeout = TIMEOUT_MS
            conexao.setRequestProperty("Accept", "application/json, text/plain, */*")
            // O Yahoo recusa requisições sem User-Agent de navegador
            conexao.setRequestProperty("User-Agent", "Mozilla/5.0")

            val codigo = conexao.responseCode
            if (codigo != HttpURLConnection.HTTP_OK) throw HttpException(codigo, url)
            return conexao.inputStream.bufferedReader(charset).use { it.readText() }
        } finally {
            conexao.disconnect()
        }
    }
}
