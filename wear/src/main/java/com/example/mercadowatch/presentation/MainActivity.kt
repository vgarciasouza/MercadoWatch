package com.example.mercadowatch.presentation

import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.wearableExtender
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import androidx.compose.runtime.LaunchedEffect
import com.example.mercadowatch.data.Ativo
import com.example.mercadowatch.data.MercadoRepository
import com.example.mercadowatch.data.Preferencias
import com.example.mercadowatch.data.ResultadoBusca
import kotlinx.coroutines.CancellationException
import com.example.mercadowatch.presentation.theme.MercadoWatchTheme
import com.example.mercadowatch.tile.MainTileService

/**
 * Tela de configuração do tile: escolhe qual ativo ocupa cada uma das 3 posições.
 *
 * Fluxo: lista das posições → toque numa posição → lista de ativos → escolhe → volta.
 * Deslizar para a direita volta à tela anterior (SwipeDismissableNavHost).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent { ConfiguracaoApp() }
    }
}

private const val ROTA_POSICOES = "posicoes"
private const val ROTA_ESCOLHER = "escolher"
private const val ARG_POSICAO = "posicao"

@Composable
fun ConfiguracaoApp() {
    val context = LocalContext.current
    val preferencias = remember { Preferencias(context) }
    var ativos by remember { mutableStateOf(preferencias.ativosDoTile()) }
    val navController = rememberSwipeDismissableNavController()

    MercadoWatchTheme {
        AppScaffold {
            SwipeDismissableNavHost(navController = navController, startDestination = ROTA_POSICOES) {
                composable(ROTA_POSICOES) {
                    TelaPosicoes(
                        ativos = ativos,
                        onPosicaoClicada = { posicao -> navController.navigate("$ROTA_ESCOLHER/$posicao") },
                    )
                }
                composable("$ROTA_ESCOLHER/{$ARG_POSICAO}") { entrada ->
                    val posicao = entrada.arguments?.getString(ARG_POSICAO)?.toIntOrNull() ?: 0
                    TelaEscolherAtivo(
                        posicao = posicao,
                        ativos = ativos,
                        onEscolhido = { ativo ->
                            preferencias.definir(posicao, ativo)
                            ativos = preferencias.ativosDoTile()
                            // Redesenha o tile com a nova seleção
                            MainTileService.solicitarAtualizacao(context)
                            navController.popBackStack()
                        },
                    )
                }
            }
        }
    }
}

/** Lista das 3 posições do tile, cada uma mostrando o ativo atual. */
@Composable
fun TelaPosicoes(ativos: List<Ativo>, onPosicaoClicada: (Int) -> Unit) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(contentPadding = contentPadding, state = listState) {
            item {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) { Text("Ativos do tile") }
            }
            ativos.forEachIndexed { i, ativo ->
                item {
                    Button(
                        onClick = { onPosicaoClicada(i) },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        secondaryLabel = { Text("Posição ${i + 1}") },
                        label = {
                            Text(ativo.nome, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Lista do catálogo para escolher o ativo de uma posição. Se o ativo escolhido já estiver
 * em outra posição, as duas trocam de lugar (ver [Preferencias.definir]).
 */
@Composable
fun TelaEscolherAtivo(posicao: Int, ativos: List<Ativo>, onEscolhido: (Ativo) -> Unit) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val atual = ativos[posicao]

    // Termo da busca; sobrevive à rotação/recriação da tela
    var termo by rememberSaveable { mutableStateOf("") }
    val resultados = remember(termo) {
        val catalogo = Ativo.buscar(termo)
        // Sem busca, um ticker personalizado já escolhido aparece no topo (para ficar marcado)
        if (termo.isEmpty() && atual.personalizado) listOf(atual) + catalogo else catalogo
    }

    // Busca livre no Yahoo Finance (qualquer ticker), feita a cada novo termo
    val context = LocalContext.current
    val repositorio = remember { MercadoRepository(context) }
    var online by remember(termo) { mutableStateOf<List<ResultadoBusca>>(emptyList()) }
    var carregando by remember(termo) { mutableStateOf(false) }
    var erroOnline by remember(termo) { mutableStateOf(false) }
    LaunchedEffect(termo) {
        if (termo.isBlank()) return@LaunchedEffect
        carregando = true
        try {
            // Remove o que já aparece pelo catálogo
            online = repositorio.pesquisarTickers(termo).filter { it.ativo !in resultados }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            erroOnline = true
        } finally {
            carregando = false
        }
    }

    // Abre o teclado do sistema (digitar, deslizar ou voz) e recebe o texto de volta
    val teclado = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { resultado ->
        val dados = resultado.data ?: return@rememberLauncherForActivityResult
        RemoteInput.getResultsFromIntent(dados)
            ?.getCharSequence(CHAVE_BUSCA)
            ?.let { termo = it.toString().trim() }
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(contentPadding = contentPadding, state = listState) {
            item {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) { Text("Posição ${posicao + 1}") }
            }
            // Campo de busca: mostra o termo atual; tocar abre o teclado
            item {
                Button(
                    onClick = { teclado.launch(intentBusca(termo)) },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    colors = ButtonDefaults.outlinedButtonColors(),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                    secondaryLabel = if (termo.isNotEmpty()) {
                        {
                            Text(
                                if (carregando) "Buscando…"
                                else "${resultados.size + online.size} resultado(s)"
                            )
                        }
                    } else null,
                    label = {
                        Text(
                            if (termo.isEmpty()) "Buscar ativo…" else "“$termo”",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
            if (termo.isNotEmpty()) {
                item {
                    Button(
                        onClick = { termo = "" },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        label = { Text("Limpar busca") },
                    )
                }
            }
            if (resultados.isEmpty() && online.isEmpty() && !carregando) {
                item {
                    Text(
                        if (erroOnline) "Nada no catálogo e sem conexão para buscar online"
                        else "Nenhum ativo encontrado",
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            resultados.forEach { ativo ->
                val outraPosicao = ativos.indexOf(ativo).takeIf { it >= 0 && it != posicao }
                item {
                    RadioButton(
                        selected = ativo == atual,
                        onSelect = { onEscolhido(ativo) },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        secondaryLabel = outraPosicao?.let {
                            { Text("Troca com posição ${it + 1}") }
                        },
                        label = {
                            Text(ativo.nome, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                    )
                }
            }
            // Resultados da busca livre (Yahoo Finance)
            if (online.isNotEmpty() || carregando) {
                item {
                    ListHeader(
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                    ) { Text(if (carregando) "Buscando online…" else "Outros tickers") }
                }
            }
            online.forEach { resultado ->
                val ativo = resultado.ativo
                val outraPosicao = ativos.indexOf(ativo).takeIf { it >= 0 && it != posicao }
                val detalhe = listOfNotNull(ativo.codigo, resultado.bolsa).joinToString(" · ")
                item {
                    RadioButton(
                        selected = ativo == atual,
                        onSelect = { onEscolhido(ativo) },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        secondaryLabel = {
                            Text(
                                outraPosicao?.let { "Troca com posição ${it + 1}" } ?: detalhe,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        label = {
                            Text(ativo.nome, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                    )
                }
            }
        }
    }
}

private const val CHAVE_BUSCA = "busca"

/** Intent do teclado do Wear OS (RemoteInput) com ação "buscar" e o termo atual como dica. */
private fun intentBusca(termoAtual: String): Intent {
    val entrada = RemoteInput.Builder(CHAVE_BUSCA)
        .setLabel(termoAtual.ifEmpty { "Nome ou ticker (ex.: petr, dólar)" })
        .wearableExtender {
            setEmojisAllowed(false)
            setInputActionType(EditorInfo.IME_ACTION_SEARCH)
        }
        .build()
    return RemoteInputIntentHelper.createActionRemoteInputIntent().also {
        RemoteInputIntentHelper.putRemoteInputsExtra(it, listOf(entrada))
    }
}

@WearPreviewDevices
@Composable
fun PreviewPosicoes() {
    MercadoWatchTheme {
        TelaPosicoes(ativos = Preferencias.PADRAO, onPosicaoClicada = {})
    }
}
