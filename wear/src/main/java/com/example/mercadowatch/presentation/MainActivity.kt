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
import androidx.wear.compose.material3.MaterialTheme
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
import com.example.mercadowatch.data.Anbima
import com.example.mercadowatch.data.Ativo
import com.example.mercadowatch.data.Fonte
import com.example.mercadowatch.data.Formatos
import com.example.mercadowatch.data.MercadoRepository
import com.example.mercadowatch.data.Preferencias
import com.example.mercadowatch.data.ResultadoBusca
import kotlinx.coroutines.CancellationException
import com.example.mercadowatch.presentation.theme.MercadoWatchTheme
import com.example.mercadowatch.tile.MainTileService

/**
 * Tela de configuração do tile: monta a lista de ativos exibidos (1 a 6).
 *
 * Fluxo: lista → "+ Adicionar ativo" → escolhe; ou lista → toque num ativo →
 * trocar / mover / remover.
 * Deslizar para a direita volta à tela anterior (SwipeDismissableNavHost).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent { ConfiguracaoApp() }
    }
}

private const val ROTA_LISTA = "lista"
private const val ROTA_ITEM = "item"
private const val ROTA_ESCOLHER = "escolher"
private const val ARG_POSICAO = "posicao"

/** Valor de [ARG_POSICAO] na rota de escolha que significa "adicionar um novo ativo". */
private const val POSICAO_NOVO = -1

@Composable
fun ConfiguracaoApp() {
    val context = LocalContext.current
    val preferencias = remember { Preferencias(context) }
    var ativos by remember { mutableStateOf(preferencias.ativosDoTile()) }
    val navController = rememberSwipeDismissableNavController()

    /** Aplica uma alteração, recarrega a lista e redesenha o tile. */
    fun alterar(acao: Preferencias.() -> Unit) {
        preferencias.acao()
        ativos = preferencias.ativosDoTile()
        MainTileService.solicitarAtualizacao(context)
    }

    fun voltarParaLista() = navController.popBackStack(ROTA_LISTA, inclusive = false)

    MercadoWatchTheme {
        AppScaffold {
            SwipeDismissableNavHost(navController = navController, startDestination = ROTA_LISTA) {
                composable(ROTA_LISTA) {
                    TelaLista(
                        ativos = ativos,
                        onItemClicado = { posicao -> navController.navigate("$ROTA_ITEM/$posicao") },
                        onAdicionar = { navController.navigate("$ROTA_ESCOLHER/$POSICAO_NOVO") },
                    )
                }
                composable("$ROTA_ITEM/{$ARG_POSICAO}") { entrada ->
                    val posicao = entrada.arguments?.getString(ARG_POSICAO)?.toIntOrNull() ?: 0
                    // A lista pode ter mudado (ex.: item removido); evita índice inválido
                    if (posicao !in ativos.indices) return@composable
                    TelaItem(
                        posicao = posicao,
                        ativos = ativos,
                        onTrocar = { navController.navigate("$ROTA_ESCOLHER/$posicao") },
                        onMover = { direcao ->
                            alterar { mover(posicao, direcao) }
                            voltarParaLista()
                        },
                        onRemover = {
                            alterar { remover(posicao) }
                            voltarParaLista()
                        },
                    )
                }
                composable("$ROTA_ESCOLHER/{$ARG_POSICAO}") { entrada ->
                    val posicao = entrada.arguments?.getString(ARG_POSICAO)?.toIntOrNull()
                        ?.takeIf { it in ativos.indices }
                    TelaEscolherAtivo(
                        posicao = posicao,
                        ativos = ativos,
                        onEscolhido = { ativo ->
                            alterar {
                                if (posicao == null) adicionar(ativo) else substituir(posicao, ativo)
                            }
                            voltarParaLista()
                        },
                    )
                }
            }
        }
    }
}

/** Lista dos ativos do tile, na ordem de exibição, e o botão para adicionar mais. */
@Composable
fun TelaLista(ativos: List<Ativo>, onItemClicado: (Int) -> Unit, onAdicionar: () -> Unit) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val cheio = ativos.size >= Preferencias.MAX_ATIVOS

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
                        onClick = { onItemClicado(i) },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        // Ticker só para ativos de bolsa; séries do BCB e pares de moeda não
                        // têm um código que diga algo ao usuário
                        secondaryLabel = {
                            Text(
                                if (ativo.fonte is Fonte.Yahoo) "${i + 1}º · ${ativo.codigo}"
                                else "${i + 1}º",
                                maxLines = 1,
                            )
                        },
                        label = {
                            Text(ativo.nome, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                    )
                }
            }
            item {
                Button(
                    onClick = onAdicionar,
                    enabled = !cheio,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    secondaryLabel = if (cheio) {
                        { Text("Máximo de ${Preferencias.MAX_ATIVOS}") }
                    } else null,
                    label = { Text("+ Adicionar ativo") },
                )
            }
        }
    }
}

/** Ações sobre um ativo da lista: trocar, mover e remover. */
@Composable
fun TelaItem(
    posicao: Int,
    ativos: List<Ativo>,
    onTrocar: () -> Unit,
    onMover: (Int) -> Unit,
    onRemover: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val ativo = ativos[posicao]

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(contentPadding = contentPadding, state = listState) {
            item {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) { Text(ativo.nome, maxLines = 2, textAlign = TextAlign.Center) }
            }
            item {
                val context = LocalContext.current
                Button(
                    onClick = { context.startActivity(DetalhesActivity.intent(context, ativo)) },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    label = { Text("Ver detalhes") },
                )
            }
            item {
                Button(
                    onClick = onTrocar,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = { Text("Trocar ativo") },
                )
            }
            if (posicao > 0) {
                item {
                    Button(
                        onClick = { onMover(-1) },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        label = { Text("↑ Mover para cima") },
                    )
                }
            }
            if (posicao < ativos.lastIndex) {
                item {
                    Button(
                        onClick = { onMover(+1) },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        label = { Text("↓ Mover para baixo") },
                    )
                }
            }
            item {
                val ultimo = ativos.size <= Preferencias.MIN_ATIVOS
                Button(
                    onClick = onRemover,
                    enabled = !ultimo,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    secondaryLabel = if (ultimo) {
                        { Text("O tile precisa de 1 ativo") }
                    } else null,
                    label = { Text("Remover do tile") },
                )
            }
        }
    }
}

/**
 * Lista do catálogo + busca livre para escolher um ativo.
 *
 * @param posicao posição a substituir, ou null para adicionar um ativo novo ao final.
 *   Ao substituir, escolher um ativo que já está em outra posição troca os dois de lugar
 *   (ver [Preferencias.substituir]); ao adicionar, ativos já presentes ficam desabilitados.
 */
@Composable
fun TelaEscolherAtivo(posicao: Int?, ativos: List<Ativo>, onEscolhido: (Ativo) -> Unit) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val atual = posicao?.let { ativos[it] }

    // Termo da busca; sobrevive à rotação/recriação da tela
    var termo by rememberSaveable { mutableStateOf("") }
    val resultados = remember(termo) {
        val catalogo = Ativo.buscar(termo)
        // Sem busca, um ticker personalizado já escolhido aparece no topo (para ficar marcado)
        if (termo.isEmpty() && atual?.personalizado == true) listOf(atual) + catalogo else catalogo
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
        // Títulos públicos (ANBIMA) e tickers (Yahoo) falham de forma independente
        suspend fun <T> tentar(bloco: suspend () -> List<T>): List<T> = try {
            bloco()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            erroOnline = true
            emptyList()
        }
        try {
            val titulos = tentar { repositorio.pesquisarTitulos(termo) }
            val tickers = tentar { repositorio.pesquisarTickers(termo) }
            // Títulos primeiro; remove o que já aparece pelo catálogo
            online = (titulos + tickers).filter { it.ativo !in resultados }.distinctBy { it.ativo }
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
                ) { Text(posicao?.let { "Trocar o ${it + 1}º" } ?: "Adicionar ativo") }
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
                val aviso = avisoDoAtivo(ativo, ativos, posicao)
                item {
                    RadioButton(
                        selected = ativo == atual,
                        onSelect = { onEscolhido(ativo) },
                        enabled = podeEscolher(ativo, ativos, posicao),
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        secondaryLabel = aviso?.let { { Text(it) } },
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
                val aviso = avisoDoAtivo(ativo, ativos, posicao)
                // Títulos: a data exata de vencimento (o nome já traz título e ano)
                val fonte = ativo.fonte
                val detalhe = if (fonte is Fonte.Anbima) {
                    "Venc. " + Formatos.data(Anbima.dataVencimento(fonte.vencimento))
                } else {
                    listOfNotNull(ativo.codigo, resultado.bolsa).joinToString(" · ")
                }
                item {
                    RadioButton(
                        selected = ativo == atual,
                        onSelect = { onEscolhido(ativo) },
                        enabled = podeEscolher(ativo, ativos, posicao),
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        secondaryLabel = {
                            Text(
                                aviso ?: detalhe,
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

/** Ao adicionar, um ativo que já está no tile não pode ser escolhido de novo. */
private fun podeEscolher(ativo: Ativo, ativos: List<Ativo>, posicao: Int?): Boolean =
    posicao != null || ativo !in ativos

/** Texto de apoio quando o ativo já está no tile: troca de posição ou indisponível. */
private fun avisoDoAtivo(ativo: Ativo, ativos: List<Ativo>, posicao: Int?): String? {
    val onde = ativos.indexOf(ativo)
    return when {
        onde < 0 || onde == posicao -> null
        posicao == null -> "Já está no tile"
        else -> "Troca com o ${onde + 1}º"
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
fun PreviewLista() {
    MercadoWatchTheme {
        TelaLista(ativos = Preferencias.PADRAO, onItemClicado = {}, onAdicionar = {})
    }
}
