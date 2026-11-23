# MercadoWatch

Indicadores do mercado brasileiro no pulso: um **tile** e uma **complication** para Wear OS com Selic, dólar, Ibovespa e qualquer ticker que você quiser acompanhar.

<p align="center">
  <img src="docs/img/tile.png" width="220" alt="Tile Mercado com Selic, dólar e Ibovespa">
  <img src="docs/img/complication.png" width="220" alt="Complication da Selic no mostrador">
  <img src="docs/img/configuracao.png" width="220" alt="Tela de configuração dos ativos do tile">
</p>

## Funcionalidades

**Tile "Mercado"**
- Mostra de **1 a 4 ativos**, com valor e variação do dia (verde/vermelho).
- Os ativos e a ordem são escolhidos pelo usuário. O padrão é Selic, dólar e Ibovespa.
- Atualiza sozinho a cada 30 minutos. Tocar no tile atualiza na hora.
- Tocar em **MERCADO ›** abre a tela de configuração.
- Sem internet, mostra os últimos valores salvos e indica `Offline · HH:mm`.

**Complication "Selic meta"**
- Tipo `SHORT_TEXT`, título "Selic" e valor no formato brasileiro (ex.: `13,75%`).
- Atualiza a cada 1 hora, com o mesmo cache offline do tile.

**Configuração no relógio**
- Lista os ativos do tile, na ordem em que aparecem.
- **+ Adicionar ativo** inclui um novo no final, até o máximo de 4. O tile não rola, e mais linhas seriam cortadas pela borda redonda.
- Tocar num ativo abre as opções **Trocar ativo**, **Mover para cima/baixo** e **Remover do tile**. O último ativo não pode ser removido.
- Não há repetição: ao adicionar, os ativos que já estão no tile aparecem desabilitados; ao trocar, escolher um que já está em outra posição **troca os dois de lugar**.
- **Busca livre**: digite ou dite um nome ou ticker (`petr`, `dólar`, `hglg11`, `apple`…). A busca procura no catálogo e no Yahoo Finance, que cobre ações, FIIs, ETFs, índices, cripto e moedas.

<p align="center">
  <img src="docs/img/busca.png" width="220" alt="Resultados da busca livre de tickers">
  <img src="docs/img/tile-offline.png" width="220" alt="Tile exibindo valores do cache em modo avião">
</p>

## Catálogo e fontes de dados

| Fonte | Ativos | Observação |
|---|---|---|
| [Banco Central – SGS](https://dadosabertos.bcb.gov.br/) | Selic meta (432), CDI (4389), IPCA 12 meses (13522) | API oficial, sem chave |
| [AwesomeAPI](https://docs.awesomeapi.com.br/) | Dólar, Euro, Bitcoin (em R$) | Cotação intradiária, sem chave |
| Yahoo Finance | Ibovespa, S&P 500, PETR4, VALE3, ITUB4, BBAS3, WEGE3 e **qualquer ticker** pela busca | Endpoints públicos **não oficiais** |

Nenhuma fonte exige chave de API.

## Requisitos

- Relógio ou emulador **Wear OS 3+** (API 30+). Testado no emulador Wear OS 6 (API 36) e num Galaxy Watch (SM-L715F, API 37).
- Android Studio com AGP 9.x / Kotlin 2.2 / JDK 17+ (o JBR do Android Studio serve).

## Como compilar e instalar

```bash
# Compilar
./gradlew :wear:assembleDebug

# Instalar (emulador ou relógio conectado ao adb)
adb install -r wear/build/outputs/apk/debug/wear-debug.apk
```

**Relógio pelo Wi-Fi**: no relógio, ative *Opções do desenvolvedor → Depuração por Wi-Fi → Parear novo dispositivo*, e então:

```bash
adb pair <IP>:<porta-de-pareamento> <código>
adb connect <IP>:<porta>   # em geral conecta sozinho após o pareamento
```

Depois de instalar:
- **Tile**: toque e segure nos tiles → **+** → **Mercado**.
- **Complication**: toque e segure no mostrador → **Personalizar** → escolha um espaço → **MercadoWatch → Selic meta**.
- **Ativos do tile**: abra o app **MercadoWatch** ou toque em **MERCADO ›** no tile.

## Estrutura do projeto

```
wear/src/main/java/com/example/mercadowatch/
├── complication/MainComplicationService.kt  # complication da Selic (SuspendingComplicationDataSourceService)
├── tile/MainTileService.kt                  # tile "Mercado" (ProtoLayout)
├── presentation/MainActivity.kt             # tela de configuração (Compose for Wear OS, Material 3)
└── data/
    ├── Ativo.kt              # catálogo de ativos, fontes, formato e busca no catálogo
    ├── MercadoRepository.kt  # requisições HTTP, busca de tickers e cache em SharedPreferences
    ├── Preferencias.kt       # lista (1 a 4) e ordem dos ativos do tile
    └── Formatos.kt           # números no padrão brasileiro (13,75% · R$ 5,99 · 164.887)
```

O módulo `mobile/` é o app de celular do template e ainda não é usado.

**Adicionar um ativo ao catálogo**: inclua uma entrada em `Ativo.CATALOGO`, com id, rótulo curto, nome, fonte e formato. O `id` é usado como chave do cache e das preferências, então não deve mudar depois de publicado.

## Notas técnicas

- O endpoint `.../dados/ultimos/1` do SGS responde sempre **HTTP 304 sem corpo**. Por isso o app consulta um intervalo de datas que termina hoje. A série 432 também traz datas futuras (até a próxima reunião do Copom), e limitar `dataFinal` a hoje garante que o valor exibido é a meta em vigor.
- As requisições usam `HttpURLConnection` e `org.json`, sem bibliotecas de rede extras.
- Os endpoints do Yahoo Finance não são oficiais e podem mudar ou passar a bloquear as requisições. Se isso acontecer, o tile continua mostrando o último valor salvo, e a busca online deixa de trazer resultados (a busca no catálogo continua funcionando).
- A busca esconde as variantes da B3 que o Yahoo devolve junto (`PETR3F.SA`, `HGLG11Q.SA`) quando o ticker principal também aparece.

## Aviso

Projeto pessoal, sem relação com o Banco Central, a B3, a AwesomeAPI ou o Yahoo. As cotações podem ter atraso e **não devem ser usadas como base para decisões de investimento**.

## Licença

[MIT](LICENSE)
