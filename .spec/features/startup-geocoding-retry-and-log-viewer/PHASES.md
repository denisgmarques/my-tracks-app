# Phases: startup-geocoding-retry-and-log-viewer

Gerado por /plan a partir de PLAN.md — view executável para `./ralph.sh .spec/features/startup-geocoding-retry-and-log-viewer/PHASES.md`.

## Phase 1: Data query + shared geocode logic + logger constants

Antes de implementar, leia:
1. `.spec/features/startup-geocoding-retry-and-log-viewer/SPEC.md` — requisitos RIGID que esta fase cobre
2. `.spec/features/startup-geocoding-retry-and-log-viewer/PLAN.md` — decomposição completa, dependências e riscos

- [ ] T01 — New `TrackingSessionDao` query for retry-eligible sessions
      Arquivos: `app/src/main/java/com/mytracksapp/data/local/dao/TrackingSessionDao.kt`
      Mudança: Adicionar `suspend fun getFinishedSessionsWithoutLocationNameSince(status: SessionStatus, sinceTimestamp: Long): List<TrackingSessionEntity>` com `@Query("SELECT * FROM tracking_sessions WHERE status = :status AND locationName IS NULL AND startTimestamp >= :sinceTimestamp")`. Nenhuma mudança de entidade/schema.
      Cobre: RF-01, RF-04, RF-05, RNF-02
      Acceptance criteria: A query retorna apenas sessões `FINISHED` com `locationName IS NULL` e `startTimestamp >= sinceTimestamp`; `AppDatabase.kt` continua na versão 2, sem migração nova.
      Testes: `app/src/androidTest/java/com/mytracksapp/data/local/TrackingSessionDaoTest.kt` — casos: elegível dentro de 24h é retornada; `startTimestamp = now-25h` não é retornada; `locationName` não-nulo não é retornada; sessão `ACTIVE` não é retornada.

- [ ] T02 — Extract shared `GeocodeAndPersist` (RF-03's single implementation)
      Arquivos: `app/src/main/java/com/mytracksapp/domain/geocoding/GeocodeAndPersist.kt`
      Mudança: Nova classe Android-free, construtor `(reverseGeocoder: ReverseGeocoder, trackingSessionDao: TrackingSessionDao, logger: Logger = FileLogger)`, expõe `suspend fun attempt(sessionId: String, latitude: Double, longitude: Double): Boolean` implementando "geocode + null/blank-como-falha + persist-apenas-no-sucesso", logando falhas via `logger` (WARN para exceção do geocoder/resultado vazio, ERROR para falha de persistência), nunca lançando exceção ao chamador.
      Cobre: RF-03, RF-06
      Acceptance criteria: Existe exatamente uma implementação de produção do comportamento "geocode attempt + null/blank-as-failure + persist-on-success"; `attempt` nunca propaga exceção; falhas geram uma entrada de log.
      Testes: `app/src/test/java/com/mytracksapp/domain/geocoding/GeocodeAndPersistTest.kt` — sucesso persiste uma vez; exceção do geocoder não persiste e não propaga, gera log; resultado null/blank não persiste; exceção de `updateLocationName` é logada e engolida.

- [ ] T07 — Widen `FileLogger`'s file-name constants to `internal`
      Arquivos: `app/src/main/java/com/mytracksapp/logging/FileLogger.kt`
      Mudança: Alterar `LOG_DIR_NAME`, `LOG_FILE_NAME`, `BACKUP_FILE_NAME` de `private const val` para `internal const val` (mesma visibilidade de `MAX_LOG_FILE_SIZE_BYTES`). Nenhuma mudança de comportamento em `init`/`log`/`rotate`.
      Cobre: RF-07, RF-08
      Acceptance criteria: As três constantes são acessíveis de outro arquivo no mesmo módulo (`internal`); `FileLoggerTest.kt` continua passando sem alteração.
      Testes: `app/src/test/java/com/mytracksapp/logging/FileLoggerTest.kt` — reexecutar como regressão (nenhum caso novo necessário).

## Phase 2: First-point delegation, retry orchestrator, log reader

Antes de implementar, leia:
1. `.spec/features/startup-geocoding-retry-and-log-viewer/SPEC.md` — requisitos RIGID que esta fase cobre
2. `.spec/features/startup-geocoding-retry-and-log-viewer/PLAN.md` — decomposição completa, dependências e riscos

- [ ] T03 — Refactor `FirstPointGeocodingCoordinator` to delegate to `GeocodeAndPersist`
      Arquivos: `app/src/main/java/com/mytracksapp/domain/geocoding/FirstPointGeocodingCoordinator.kt`
      Mudança: Trocar os parâmetros do construtor (`reverseGeocoder`/`trackingSessionDao`/`logger`) por um único `geocodeAndPersist: GeocodeAndPersist`; `onFirstPointRecorded` passa a ser `coroutineScope.launch { geocodeAndPersist.attempt(sessionId, latitude, longitude) }`.
      Cobre: RF-03
      Acceptance criteria: `FirstPointGeocodingCoordinator` não contém mais nenhuma lógica de geocode/persist própria — delega inteiramente a `GeocodeAndPersist`; `onFirstPointRecorded` continua retornando antes do `attempt` suspenso completar (RNF-01).
      Testes: `app/src/test/java/com/mytracksapp/domain/geocoding/FirstPointGeocodingCoordinatorTest.kt` — atualizar para construir com um `GeocodeAndPersist` real sobre fakes; manter apenas as asserções de contrato externo (uma chamada de `updateLocationName` no sucesso; retorno imediato de `onFirstPointRecorded`).

- [ ] T05 — New `GeocodingRetryOnStartup` orchestrator
      Arquivos: `app/src/main/java/com/mytracksapp/domain/geocoding/GeocodingRetryOnStartup.kt`
      Mudança: Nova classe Android-free, construtor `(trackingSessionDao, gpsPointDao, geocodeAndPersist, logger: Logger = FileLogger, now: () -> Long = System::currentTimeMillis)`, expõe `suspend fun retry(): Int` que usa a query de T01 (`now() - 86_400_000L`), busca o primeiro ponto GPS por sessão elegível (`getPointsForSession(id).first()`, menor timestamp), chama `geocodeAndPersist.attempt(...)`, isolando falhas por sessão (catch-and-continue, mesmo padrão de `OrphanedSessionRecovery.recover()`), e retorna a contagem de persistências bem-sucedidas.
      Cobre: RF-01, RF-02, RF-04, RF-05, RF-06
      Acceptance criteria: Sessão elegível (FINISHED, nome nulo, dentro de 24h) é geocodificada usando o ponto de menor timestamp; sessão com `startTimestamp = now-25h` nunca é geocodificada; sessão com `locationName` não-nulo permanece inalterada, sem chamada a `updateLocationName`; uma falha em uma sessão não impede o processamento das demais e é logada, sem exceção propagada.
      Testes: `app/src/test/java/com/mytracksapp/domain/geocoding/GeocodingRetryOnStartupTest.kt` — casos RF-01/RF-02/RF-04/RF-05/RF-06 descritos acima, com `now: () -> Long` injetado para o limite de 24h.

- [ ] T08 — New `LogFileReader`
      Arquivos: `app/src/main/java/com/mytracksapp/logging/LogFileReader.kt`
      Mudança: Nova classe sem dependências de construtor, `suspend fun read(logsDir: File): String = withContext(Dispatchers.IO) { ... }`, concatenando `FileLogger.BACKUP_FILE_NAME` (se existir) seguido de `FileLogger.LOG_FILE_NAME` (se existir); retorna `""` se nenhum existir.
      Cobre: RF-07, RF-08, RNF-03
      Acceptance criteria: A leitura ocorre inteiramente dentro de `withContext(Dispatchers.IO)`; com ambos os arquivos presentes, o texto resultante começa com o conteúdo do backup e termina com o do arquivo ativo, sem omissões; com nenhum arquivo presente, retorna string vazia.
      Testes: `app/src/test/java/com/mytracksapp/logging/LogFileReaderTest.kt` — ambos presentes com conteúdo distinto conhecido; apenas `app.log` presente; nenhum presente.

## Phase 3: Startup wiring, service wiring, log ViewModel

Antes de implementar, leia:
1. `.spec/features/startup-geocoding-retry-and-log-viewer/SPEC.md` — requisitos RIGID que esta fase cobre
2. `.spec/features/startup-geocoding-retry-and-log-viewer/PLAN.md` — decomposição completa, dependências e riscos

- [ ] T04 — Update `LocationForegroundService` wiring for the new `GeocodeAndPersist`/`FirstPointGeocodingCoordinator` shape
      Arquivos: `app/src/main/java/com/mytracksapp/service/LocationForegroundService.kt`
      Mudança: Em `onStartCommand` (bloco atual em `LocationForegroundService.kt:186-191`), construir `val geocodeAndPersist = GeocodeAndPersist(reverseGeocoder, trackingSessionDao)` e passá-lo para `FirstPointGeocodingCoordinator(geocodeAndPersist, serviceScope)`, conforme a nova assinatura de T03.
      Cobre: RF-03
      Acceptance criteria: `LocationForegroundService` compila e constrói `FirstPointGeocodingCoordinator` via `GeocodeAndPersist`, sem nenhuma outra mudança de comportamento no fluxo de coleta de pontos.
      Testes: Nenhum teste novo dedicado — cobertura via os testes unitários de T02/T03 e revisão de código deste ponto de wiring.

- [ ] T06 — Wire `GeocodingRetryOnStartup` into the app's cold-start path
      Arquivos: `app/src/main/java/com/mytracksapp/MainActivity.kt`, `app/src/main/java/com/mytracksapp/ui/navigation/AppNavigation.kt`
      Mudança: Em `MainActivity.onCreate`, construir `AndroidReverseGeocoder`, `GeocodeAndPersist` e `GeocodingRetryOnStartup`, passando este último como novo parâmetro `geocodingRetryOnStartup` para `MyTracksApp`. Em `AppNavigation.kt`, adicionar um `LaunchedEffect(Unit) { geocodingRetryOnStartup.retry() }` irmão do `LaunchedEffect(Unit)` existente de `orphanedSessionRecovery.recover()`, sem surfacing de UI para o resultado.
      Cobre: RF-01, RNF-01
      Acceptance criteria: `MyTracksApp` aceita o novo parâmetro `geocodingRetryOnStartup`; a primeira composição (tela de Histórico) renderiza imediatamente mesmo que `retry()` nunca complete; toda chamada direta existente a `MyTracksApp(...)` (produção e testes) é atualizada para o novo parâmetro.
      Testes: `app/src/androidTest/java/com/mytracksapp/ui/navigation/GeocodingRetryOnStartupWiringTest.kt` — um `GeocodingRetryOnStartup` que nunca completa não bloqueia a primeira composição; um caso com sessão elegível seeded comprova que `retry()` é de fato invocado a partir deste gatilho.

- [ ] T09 — New `LogViewerViewModel`
      Arquivos: `app/src/main/java/com/mytracksapp/ui/logs/LogViewerViewModel.kt`
      Mudança: `data class LogViewerUiState(val isLoading: Boolean = true, val content: String = "")`; `class LogViewerViewModel(private val logsDir: File, private val logFileReader: LogFileReader = LogFileReader())` que, em `init`, chama `logFileReader.read(logsDir)` dentro de `runCatching`, publicando o resultado em um `StateFlow<LogViewerUiState>`.
      Cobre: RF-07
      Acceptance criteria: `uiState.content` reflete o texto combinado retornado por `LogFileReader.read` após o carregamento; uma falha de leitura não propaga exceção e resulta em `isLoading = false` com conteúdo vazio.
      Testes: `app/src/test/java/com/mytracksapp/ui/logs/LogViewerViewModelTest.kt` — arquivos com conteúdo refletem em `uiState`; ausência de arquivos resulta em conteúdo vazio sem exceção.

## Phase 4: Log-viewer screen + factory

Antes de implementar, leia:
1. `.spec/features/startup-geocoding-retry-and-log-viewer/SPEC.md` — requisitos RIGID que esta fase cobre
2. `.spec/features/startup-geocoding-retry-and-log-viewer/PLAN.md` — decomposição completa, dependências e riscos

- [ ] T10 — New `LogViewerScreen`
      Arquivos: `app/src/main/java/com/mytracksapp/ui/logs/LogViewerScreen.kt`
      Mudança: `object LogViewerScreenTestTags { SCREEN, LOG_CONTENT, EMPTY_MESSAGE }`; `@Composable fun LogViewerScreen(viewModel: LogViewerViewModel, modifier: Modifier = Modifier)` renderiza a mensagem de estado vazio ("Nenhum log registrado ainda") quando `!isLoading && content.isBlank()`, senão renderiza o conteúdo em `Text(fontFamily = FontFamily.Monospace, fontSize = 12.sp)` dentro de `Modifier.fillMaxWidth().verticalScroll(rememberScrollState())` (+ `horizontalScroll`); nenhum controle de editar/excluir/compartilhar é adicionado.
      Cobre: UI-02, UI-03, UI-04
      Acceptance criteria: Com conteúdo não-vazio, o nó `LOG_CONTENT` é exibido com modificador full-width + scroll vertical, fonte monospace menor que 15sp, e `EMPTY_MESSAGE` está ausente; com conteúdo vazio/ausente, `EMPTY_MESSAGE` é exibido, `LOG_CONTENT` ausente, sem crash; nenhum nó de compartilhar/editar/excluir existe na árvore.
      Testes: `app/src/androidTest/java/com/mytracksapp/ui/logs/LogViewerScreenTest.kt` — casos de conteúdo presente, conteúdo ausente (estado vazio), e ausência de controles de compartilhar/editar/excluir.

- [ ] T11 — `LogViewerViewModelFactory`
      Arquivos: `app/src/main/java/com/mytracksapp/ui/ViewModelFactories.kt`
      Mudança: Adicionar `class LogViewerViewModelFactory(private val logsDir: File) : ViewModelProvider.Factory` seguindo exatamente o padrão `require(modelClass.isAssignableFrom(...))` das demais factories neste arquivo.
      Cobre: (suporte a UI-01/CT-01)
      Acceptance criteria: `LogViewerViewModelFactory(logsDir).create(LogViewerViewModel::class.java)` retorna uma instância de `LogViewerViewModel` construída com o `logsDir` fornecido.
      Testes: Exercitado indiretamente pelos testes de T10 e T12 (mesmo padrão das demais factories neste arquivo, nenhuma delas tem teste dedicado).

## Phase 5: Route + drawer item

Antes de implementar, leia:
1. `.spec/features/startup-geocoding-retry-and-log-viewer/SPEC.md` — requisitos RIGID que esta fase cobre
2. `.spec/features/startup-geocoding-retry-and-log-viewer/PLAN.md` — decomposição completa, dependências e riscos

- [ ] T12 — Wire `Routes.LOGS` + `LogsRoute` + "Ver logs" drawer item
      Arquivos: `app/src/main/java/com/mytracksapp/ui/navigation/AppNavigation.kt`
      Mudança: Adicionar `const val LOGS = "logs"` a `Routes`; adicionar `private @Composable fun LogsRoute()` que resolve `File(LocalContext.current.filesDir, "logs")`, constrói o ViewModel via `LogViewerViewModelFactory` e renderiza `LogViewerScreen`; registrar `composable(Routes.LOGS) { LogsRoute() }` no `NavHost`; adicionar `DRAWER_LOGS_ITEM` a `AppNavigationTestTags`; adicionar um segundo `NavigationDrawerItem` ("Ver logs") imediatamente após "Configurações", reutilizando exatamente o mesmo `shape`/`colors`/padding, fechando o drawer e navegando para `Routes.LOGS` no `onClick`.
      Cobre: UI-01, CT-01
      Acceptance criteria: O drawer contém exatamente um novo `NavigationDrawerItem` além de "Configurações", rotulado "Ver logs", com o mesmo shape/colors/padding do item "Configurações"; tocar nele fecha o drawer e navega para a tela do log-viewer.
      Testes: `app/src/androidTest/java/com/mytracksapp/ui/navigation/AppNavigationLogsDrawerTest.kt` — abre o drawer, toca `DRAWER_LOGS_ITEM`, verifica que o drawer fecha e que `LogViewerScreenTestTags.SCREEN` passa a ser exibido.
