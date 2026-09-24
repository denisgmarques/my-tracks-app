# Phases: exception-handling-and-error-logging

Gerado por /plan a partir de PLAN.md — view executável para `./ralph.sh .spec/features/exception-handling-and-error-logging/PHASES.md`.

## Phase 1: FileLogger foundation

Antes de implementar, leia:
1. `.spec/features/exception-handling-and-error-logging/SPEC.md` — requisitos RIGID que esta fase cobre
2. `.spec/features/exception-handling-and-error-logging/PLAN.md` — decomposição completa, dependências e riscos

- [ ] T01 — FileLogger core: append-only writer, rotation, concurrency safety
      Arquivos: `app/src/main/java/com/mytracksapp/logging/Logger.kt`, `app/src/main/java/com/mytracksapp/logging/FileLogger.kt`
      Mudança: Implementar `LogLevel`/`Logger` e o objeto `FileLogger` com `init(context)` e `log(level, tag, message, throwable)`: timestamp ISO-8601, escaping de quebras de linha (uma entrada = uma linha física), uma única seção `synchronized` que verifica o tamanho do arquivo ativo e rotaciona (mantendo exatamente 1 backup) ANTES de anexar a entrada que disparou a rotação, escreve via `FileOutputStream` append + `fd.sync()`, e nunca deixa uma falha interna (I/O) escapar do próprio `log()`.
      Cobre: RF-02, RF-03, RNF-02, RNF-03
      Acceptance criteria: 50 chamadas concorrentes de `log()` a partir de 4+ dispatchers distintos produzem exatamente 50 linhas completas e não corrompidas; uma entrada com stack trace multi-linha vira exatamente uma linha física recuperável via o escape documentado; uma leitura imediata após `log()` (sem close explícito) já observa a entrada no disco; o arquivo ativo nunca ultrapassa 1.048.576 bytes + o tamanho de uma entrada no momento da rotação; após duas rotações sucessivas existe exatamente 1 backup, com o conteúdo da segunda rotação; um teste de concorrência perto do limiar produz exatamente 1 rotação por cruzamento de limiar; `gradle/libs.versions.toml`/`app/build.gradle.kts` não ganham nenhuma dependência `Timber`/`Crashlytics`/`Sentry`/Firebase Crashlytics.
      Testes: `app/src/test/java/com/mytracksapp/logging/FileLoggerTest.kt` — concorrência, escaping, durabilidade, rotação, race de rotação, swallow de falha interna; `app/src/test/java/com/mytracksapp/logging/NoExternalLoggingDependencyTest.kt` — ausência de dependência externa de logging

## Phase 2: Application crash handler, pipeline de GPS, ViewModels, export e catches existentes

Antes de implementar, leia:
1. `.spec/features/exception-handling-and-error-logging/SPEC.md` — requisitos RIGID que esta fase cobre
2. `.spec/features/exception-handling-and-error-logging/PLAN.md` — decomposição completa, dependências e riscos

- [ ] T02 — Custom Application + global Thread.UncaughtExceptionHandler
      Arquivos: `app/src/main/java/com/mytracksapp/MyTracksApplication.kt`, `app/src/main/AndroidManifest.xml`
      Mudança: `onCreate()` chama `FileLogger.init(applicationContext)`, captura o `previousHandler` via `Thread.getDefaultUncaughtExceptionHandler()`, instala um novo handler (extraído em `buildUncaughtExceptionHandler(logger, previousHandler)` para ser testável) que loga (com try/catch interno que engole falhas de log) e SEMPRE, fora de qualquer try/catch supressor, invoca `previousHandler` com a mesma thread/throwable. Adicionar `android:name=".MyTracksApplication"` ao `<application>` do manifest.
      Cobre: RF-01
      Acceptance criteria: forçar uma `RuntimeException` não capturada em uma thread de fundo resulta em exatamente uma entrada de log (classe, mensagem, stack trace, timestamp) escrita antes do handler anterior ser invocado, e o handler anterior é invocado com a mesma thread/throwable; quando o próprio logger lança durante o tratamento, o handler anterior ainda assim é invocado com a mesma thread/throwable, sem exceção escapando do handler novo.
      Testes: `app/src/test/java/com/mytracksapp/MyTracksApplicationTest.kt` — handler construído com logger/previousHandler fakes, caso de sucesso e caso de falha do logger

- [ ] T03 — LocationCollector.onLocationSample try/catch
      Arquivos: `app/src/main/java/com/mytracksapp/service/LocationCollector.kt`
      Mudança: Adicionar parâmetro final `logger: Logger = FileLogger`. Envolver o corpo de `onLocationSample` (do cálculo de drift até `onFirstPointRecorded`) em try/catch que loga em nível de erro e retorna normalmente, mantendo `collecting` inalterado e `lastAcceptedTimestamp` no último valor aceito com sucesso.
      Cobre: RF-04
      Acceptance criteria: com `gpsPointDao.insert` lançando uma vez entre várias amostras, o logger fake recebe exatamente uma entrada de erro contendo a exceção, nenhuma exceção escapa de `onLocationSample`, e toda outra amostra (não lançante) no mesmo teste continua sendo persistida.
      Testes: `app/src/test/java/com/mytracksapp/service/LocationCollectorTest.kt` (caso novo)

- [ ] T04 — LocationForegroundService.serviceScope CoroutineExceptionHandler
      Arquivos: `app/src/main/java/com/mytracksapp/service/LocationForegroundService.kt`
      Mudança: Extrair `serviceScopeExceptionHandler(logger: Logger = FileLogger): CoroutineExceptionHandler` que loga em nível de erro, e mudar `serviceScope` para `CoroutineScope(Dispatchers.Default + serviceJob + serviceScopeExceptionHandler())`.
      Cobre: RF-06
      Acceptance criteria: uma coroutine lançada via `serviceScope.launch` que lança uma exceção não tratada resulta em exatamente uma entrada de erro no logger fake, verificável construindo o mesmo `CoroutineExceptionHandler` isoladamente; o processo/serviço hospedeiro não termina como resultado.
      Testes: `app/src/test/java/com/mytracksapp/service/LocationForegroundServiceExceptionHandlingTest.kt` (novo)

- [ ] T06 — FirstPointGeocodingCoordinator try/catch around updateLocationName
      Arquivos: `app/src/main/java/com/mytracksapp/domain/geocoding/FirstPointGeocodingCoordinator.kt`
      Mudança: Adicionar parâmetro final `logger: Logger = FileLogger`. Envolver apenas a chamada `trackingSessionDao.updateLocationName(...)` em try/catch que loga em nível de erro, mantendo o `runCatching` existente do reverse-geocode inalterado.
      Cobre: RF-09
      Acceptance criteria: com `updateLocationName` lançando, o logger fake recebe exatamente uma entrada de erro, nenhuma exceção escapa da coroutine lançada, e a suíte de testes existente deste arquivo passa sem modificação.
      Testes: `app/src/test/java/com/mytracksapp/domain/geocoding/FirstPointGeocodingCoordinatorTest.kt` (caso novo)

- [ ] T07 — Logging opcional dentro dos 3 catches corretos existentes (sem mudar fluxo)
      Arquivos: `app/src/main/java/com/mytracksapp/service/AndroidReverseGeocoder.kt`, `app/src/main/java/com/mytracksapp/ui/tracking/MapComponent.kt`, `app/src/main/java/com/mytracksapp/domain/session/OrphanedSessionRecovery.kt`
      Mudança: Em cada um dos três `catch` já corretos, adicionar apenas uma chamada de log (via `logger: Logger = FileLogger` injetado nos dois primeiros; via singleton `FileLogger` direto no `MapComponent.kt`, por ser `@Composable`) — o tipo de exceção capturado e o desvio de fluxo pós-catch (valor retornado, branch executado, continuação de loop) permanecem byte-idênticos.
      Cobre: RF-10
      Acceptance criteria: diff de revisão de código mostra cada cláusula `catch` existente com tipo de exceção e fluxo de controle pós-catch idênticos a antes desta feature; a única adição dentro de cada bloco catch é a chamada de log.
      Testes: `app/src/test/java/com/mytracksapp/domain/session/OrphanedSessionRecoveryTest.kt` (caso novo, DAO fake lança para uma de várias sessões órfãs); `AndroidReverseGeocoder`/`MapComponent` não têm teste unitário direto hoje — verificação primária é o diff de revisão citado no AC

- [ ] T08 — Export call-site failure containment (SessionDetailRoute.onExport)
      Arquivos: `app/src/main/java/com/mytracksapp/ui/navigation/AppNavigation.kt`
      Mudança: Extrair `exportSessionSafely(exportService, sessionId, format, exportsDir, logger: Logger = FileLogger)` que envolve `exportService.export(...)` + `.writeTo(...)` em try/catch, loga em nível de erro e não relança; religar `SessionDetailRoute.onExport` para chamar essa função.
      Cobre: RF-08
      Acceptance criteria: forçar `ExportService.export`/`writeTo` a lançar (incluindo `NoSuchElementException`/`IllegalStateException`/`IllegalArgumentException`) resulta em exatamente uma entrada de erro no logger fake e nenhuma exceção não tratada; nenhum elemento novo de UI (toast/dialog/snackbar) aparece e nenhuma navegação ocorre; os testes existentes `SessionDetailExportTest`'s dois métodos continuam passando sem modificação (eles usam um `onExport` inline próprio, fora de `AppNavigation.kt`).
      Testes: `app/src/test/java/com/mytracksapp/ui/navigation/ExportSessionSafelyTest.kt` (novo)

- [ ] T09 — NewSessionViewModel failure containment
      Arquivos: `app/src/main/java/com/mytracksapp/ui/newsession/NewSessionViewModel.kt`
      Mudança: Adicionar parâmetro final `logger: Logger = FileLogger`. Envolver o `viewModelScope.launch` do `init` (collect de settings) e o de `onConfirm()` cada um em try/catch que loga em nível de erro, sem atualizar `_uiState` após uma falha capturada.
      Cobre: RF-07
      Acceptance criteria: com `SettingsRepository`/`SessionController` fake lançando, o logger fake recebe uma entrada de erro referenciando `NewSessionViewModel`, nenhuma exceção escapa do `viewModelScope.launch`, e `uiState` permanece no último valor válido.
      Testes: `app/src/test/java/com/mytracksapp/ui/newsession/NewSessionViewModelTest.kt` (novo)

- [ ] T10 — HistoryViewModel failure containment
      Arquivos: `app/src/main/java/com/mytracksapp/ui/history/HistoryViewModel.kt`
      Mudança: Adicionar parâmetro final `logger: Logger = FileLogger`. Envolver o `combine(...).collect{}` do `init` e o `viewModelScope.launch` de `deleteSession()` cada um em try/catch que loga em nível de erro.
      Cobre: RF-07
      Acceptance criteria: com DAO fake lançando, o logger fake recebe uma entrada de erro referenciando `HistoryViewModel`, nenhuma exceção escapa, `uiState` inalterado, e a suíte de testes existente deste arquivo passa sem modificação.
      Testes: `app/src/test/java/com/mytracksapp/ui/history/HistoryViewModelTest.kt` (caso novo)

- [ ] T11 — TrackingViewModel failure containment
      Arquivos: `app/src/main/java/com/mytracksapp/ui/tracking/TrackingViewModel.kt`
      Mudança: Adicionar parâmetro final `logger: Logger = FileLogger`. Envolver o `combine(...).collect{}` do `init` em try/catch que loga em nível de erro.
      Cobre: RF-07
      Acceptance criteria: com DAO fake lançando, o logger fake recebe uma entrada de erro referenciando `TrackingViewModel`, nenhuma exceção escapa, `uiState` inalterado, e a suíte de testes existente deste arquivo passa sem modificação.
      Testes: `app/src/test/java/com/mytracksapp/ui/tracking/TrackingViewModelTest.kt` (caso novo)

- [ ] T12 — SettingsViewModel failure containment (9 launch sites)
      Arquivos: `app/src/main/java/com/mytracksapp/ui/settings/SettingsViewModel.kt`
      Mudança: Adicionar parâmetro final `logger: Logger = FileLogger`. Envolver individualmente cada um dos 9 `viewModelScope.launch{}` (init, os 6 setters de enum/número, `onGpsPrecisionSelected`, `onKeepScreenOnToggled`, `onDefaultExportFormatSelected`, `clearHistory`) em seu próprio try/catch que loga em nível de erro — nunca um wrapper único para todos.
      Cobre: RF-07
      Acceptance criteria: com `SettingsRepository`/`TrackingSessionDao` fake lançando em um setter representativo e em `clearHistory()`, o logger fake recebe uma entrada de erro por caso referenciando `SettingsViewModel`, nenhuma exceção escapa de nenhum `viewModelScope.launch`, e `uiState` permanece no último valor válido.
      Testes: `app/src/test/java/com/mytracksapp/ui/settings/SettingsViewModelTest.kt` (novo)

- [ ] T13 — SessionDetailViewModel failure containment
      Arquivos: `app/src/main/java/com/mytracksapp/ui/history/SessionDetailScreen.kt` (classe `SessionDetailViewModel`)
      Mudança: Adicionar parâmetro final `logger: Logger = FileLogger`. Envolver o `combine(...).collect{}` do `init` em try/catch que loga em nível de erro.
      Cobre: RF-07
      Acceptance criteria: com DAO fake lançando, o logger fake recebe uma entrada de erro referenciando `SessionDetailViewModel`, nenhuma exceção escapa, `uiState` inalterado; `SessionDetailExportTest`'s construção posicional de 4 argumentos ainda compila e seus dois métodos continuam passando sem modificação.
      Testes: `app/src/test/java/com/mytracksapp/ui/history/SessionDetailViewModelTest.kt` (novo)

## Phase 3: FusedLocationSampleSource callback e Encerrar sessão (dependem de arquivos da Fase 2)

Antes de implementar, leia:
1. `.spec/features/exception-handling-and-error-logging/SPEC.md` — requisitos RIGID que esta fase cobre
2. `.spec/features/exception-handling-and-error-logging/PLAN.md` — decomposição completa, dependências e riscos

- [ ] T05 — FusedLocationSampleSource.onLocationResult try/catch
      Arquivos: `app/src/main/java/com/mytracksapp/service/LocationForegroundService.kt`
      Mudança: Adicionar parâmetro final `logger: Logger = FileLogger` a `FusedLocationSampleSource`. Extrair `deliverSample(sample, onLocation, logger)` (suspend) que envolve `onLocation(sample)` em try/catch logando em nível de erro; chamar essa função de dentro do `coroutineScope.launch{}` existente em `onLocationResult`, garantindo que a exceção nunca alcance o `CoroutineExceptionHandler` de T04 nem cancele o scope.
      Cobre: RF-05
      Acceptance criteria: com `onLocation` fake lançando para uma invocação, o logger fake recebe exatamente uma entrada de erro, o scope permanece ativo (uma invocação subsequente não lançante completa e seu efeito é observado), e nenhuma exceção escapa de `deliverSample` nem do `LocationCallback` registrado.
      Testes: `app/src/test/java/com/mytracksapp/service/FusedLocationSampleSourceDeliveryTest.kt` (novo, JVM puro, chamando `deliverSample` diretamente)

- [ ] T14 — TrackingScreen "Encerrar sessão" stopSession() failure containment
      Arquivos: `app/src/main/java/com/mytracksapp/ui/navigation/AppNavigation.kt`
      Mudança: O botão "Encerrar sessão" (`TrackingScreen.kt:360-369`) chama `onFinishSession`, religado em `TrackingRoute` (`AppNavigation.kt:479-482`) como `{ finishedSessionId -> sessionController.stopSession(finishedSessionId); onSessionFinished() }` — é aqui, em `AppNavigation.kt`, que falta o try/catch. Extrair `finishSessionSafely(sessionController, sessionId, onSessionFinished, logger: Logger = FileLogger)` que envolve `sessionController.stopSession(sessionId)` + `onSessionFinished()` em try/catch, loga em nível de erro e não relança; religar `TrackingRoute.onFinishSession` para chamar essa função. O caminho de sucesso (retorno/efeitos de `stopSession()`, navegação pós-sucesso via `onSessionFinished()`, e o reset de `isFinishing` em `TrackingScreen.kt`) permanece idêntico.
      Cobre: RF-12
      Acceptance criteria: com `SessionControllerImpl.stopSession()` fake lançando, o logger fake recebe exatamente uma entrada de erro referenciando este call site, nenhuma exceção escapa de `finishSessionSafely`, `onSessionFinished` NÃO é chamado nesse caso, e um segundo teste sem falha confirma que `onSessionFinished` É chamado e nenhuma entrada de log é registrada (caminho de sucesso inalterado).
      Testes: `app/src/test/java/com/mytracksapp/ui/navigation/FinishSessionSafelyTest.kt` (novo, JVM puro)
