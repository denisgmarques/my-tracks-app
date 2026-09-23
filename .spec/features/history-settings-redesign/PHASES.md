# Phases: history-settings-redesign

Gerado por /plan a partir de PLAN.md — view executável para `./ralph.sh .spec/features/history-settings-redesign/PHASES.md`.

## Phase 1: Foundational data and domain building blocks

Antes de implementar, leia:
1. `.spec/features/history-settings-redesign/SPEC.md` — requisitos RIGID que esta fase cobre
2. `.spec/features/history-settings-redesign/PLAN.md` — decomposição completa, dependências e riscos

- [ ] T01 — TrackingSessionEntity: add locationName/distanceMeters; bump DB version
      Arquivos: `app/src/main/java/com/mytracksapp/data/local/entity/TrackingSessionEntity.kt`, `app/src/main/java/com/mytracksapp/data/local/AppDatabase.kt`
      Mudança: Adicionar `locationName: String? = null` e `distanceMeters: Double = 0.0` a `TrackingSessionEntity`; bump `version = 1` -> `version = 2` em `@Database` e adicionar `.fallbackToDestructiveMigration()` ao `Room.databaseBuilder(...)` em `AppDatabase.getInstance`.
      Cobre: CT-01, CT-02
      Acceptance criteria: `TrackingSessionEntity` compila com os 2 novos campos com os defaults `null`/`0.0`; `AppDatabase` está em `version = 2` com `fallbackToDestructiveMigration()` configurado; o app compila e roda sobre um DB real recriado do zero sem crash.
      Testes: `app/src/androidTest/java/com/mytracksapp/data/local/TrackingSessionDaoTest.kt` — round-trip de `locationName`/`distanceMeters` via Room com defaults corretos quando omitidos.

- [ ] T03 — SettingsRepository/UserSettings: 3 novas preferências
      Arquivos: `app/src/main/java/com/mytracksapp/domain/model/GpsPrecision.kt` (novo), `app/src/main/java/com/mytracksapp/data/settings/SettingsRepository.kt`, `app/src/androidTest/java/com/mytracksapp/data/settings/SettingsRepositoryTest.kt`
      Mudança: Novo enum `GpsPrecision { HIGH_ACCURACY, BALANCED }`. Adicionar a `UserSettings`: `gpsPrecision: GpsPrecision = GpsPrecision.HIGH_ACCURACY`, `keepScreenOnEnabled: Boolean = true`, `defaultExportFormat: ExportFormat = ExportFormat.GPX` (reaproveitar `com.mytracksapp.domain.export.ExportFormat`). Adicionar `Keys` correspondentes e `setGpsPrecision`/`setKeepScreenOnEnabled`/`setDefaultExportFormat`, seguindo o padrão exato dos 5 setters já existentes neste arquivo. Estender `Preferences.toUserSettings()` com o mesmo padrão de fallback (`runCatching { valueOf(...) }.getOrNull() ?: defaults.x`).
      Cobre: CT-03, RF-04, RF-06, RF-10
      Acceptance criteria: uma instalação nova (sem preferência persistida) expõe `gpsPrecision = HIGH_ACCURACY`, `keepScreenOnEnabled = true`, `defaultExportFormat = GPX`; chamar cada `set...` e reconstruir `SettingsRepository` sobre o mesmo `dataStoreName` retorna o valor persistido.
      Testes: `SettingsRepositoryTest.kt` — 3 novos grupos de teste (default, round-trip, sobrevivência a uma nova instância), mirrorando os testes já existentes para `samplingInterval`/`speedUnit`.

- [ ] T04 — LocationCollector: callback "primeiro ponto registrado" (dispara 1x)
      Arquivos: `app/src/main/java/com/mytracksapp/service/LocationCollector.kt`, `app/src/test/java/com/mytracksapp/service/LocationCollectorTest.kt`
      Mudança: Adicionar parâmetro de construtor `onFirstPointRecorded: (latitude: Double, longitude: Double) -> Unit = {}` (não-suspend, fire-and-forget). Em `onLocationSample`, invocar exatamente uma vez, apenas quando `previousTimestamp` (capturado antes de atualizar `lastAcceptedTimestamp`) for `null` — ou seja, apenas no primeiro ponto aceito da sessão.
      Cobre: RF-01 (ponto de disparo), RNF-01
      Acceptance criteria: o callback dispara exatamente uma vez com as coordenadas exatas do primeiro ponto aceito; não dispara novamente para pontos subsequentes da mesma sessão; não dispara se `start()` retornar `false`.
      Testes: `LocationCollectorTest.kt` — 3 novos testes cobrindo os 3 critérios acima.

- [ ] T05 — Abstração ReverseGeocoder + implementação Android
      Arquivos: `app/src/main/java/com/mytracksapp/domain/geocoding/ReverseGeocoder.kt` (novo), `app/src/main/java/com/mytracksapp/service/AndroidReverseGeocoder.kt` (novo)
      Mudança: Interface Android-free `ReverseGeocoder { suspend fun reverseGeocode(latitude: Double, longitude: Double): String? }`. `AndroidReverseGeocoder(context: Context) : ReverseGeocoder` envolve `android.location.Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1)` em `withContext(Dispatchers.IO)`, capturando `IOException`/lista vazia -> `null`; sucesso retorna `address.locality ?: address.subAdminArea ?: address.adminArea ?: address.featureName` (trim, blank -> `null`). Nenhuma lógica de retry nesta classe.
      Cobre: RF-01 (mecanismo), RF-02
      Acceptance criteria: `AndroidReverseGeocoder.reverseGeocode` nunca lança exceção ao chamador (captura `IOException` internamente) e retorna `null` para lista vazia/resultado em branco; `ReverseGeocoder` não referencia nenhuma classe Android em sua assinatura de interface.
      Testes: nenhum teste dedicado nesta task (adaptador fino Android-SDK, mesmo precedente de `FusedLocationSampleSource` não testado diretamente); a lógica "uma tentativa, falha graciosa, sem retry" é testada em T06 contra um fake de `ReverseGeocoder`.

## Phase 2: Independent consumers of Phase 1 (DAO mass-ops + tracking screen keep-awake)

Antes de implementar, leia:
1. `.spec/features/history-settings-redesign/SPEC.md` — requisitos RIGID que esta fase cobre
2. `.spec/features/history-settings-redesign/PLAN.md` — decomposição completa, dependências e riscos

- [ ] T02 — TrackingSessionDao: exclusão em massa + atualização de nome de local
      Arquivos: `app/src/main/java/com/mytracksapp/data/local/dao/TrackingSessionDao.kt`, `app/src/test/java/com/mytracksapp/domain/session/SessionControllerTest.kt` (`FakeTrackingSessionDao`), `app/src/test/java/com/mytracksapp/ui/history/HistoryViewModelTest.kt` (`FakeTrackingSessionDao`), `app/src/androidTest/java/com/mytracksapp/ui/history/HistoryListScreenTest.kt` (`FakeMutableTrackingSessionDao`), `app/src/androidTest/java/com/mytracksapp/ui/history/SessionDetailScreenTest.kt` (2 objetos anônimos), `app/src/androidTest/java/com/mytracksapp/ui/export/ExportFormatDialogTest.kt` (`FakeTrackingSessionDao`)
      Mudança: Adicionar `@Transaction @Query("DELETE FROM tracking_sessions") suspend fun deleteAll()` (cascata de FK já existente remove os `gps_points`). Adicionar `@Query("UPDATE tracking_sessions SET locationName = :locationName WHERE id = :sessionId") suspend fun updateLocationName(sessionId: String, locationName: String?)` (update de coluna única, evita corrida com o `update()` de linha inteira em `SessionControllerImpl.stopSession`). Atualizar todos os 5 fakes listados para compilar com os 2 novos membros abstratos.
      Cobre: CT-04, RF-01 (hook de persistência), RF-12, RNF-03
      Acceptance criteria: após `deleteAll()`, as tabelas `tracking_sessions` E `gps_points` ficam vazias; `updateLocationName` altera apenas a coluna `locationName`, preservando os demais campos da linha; todos os 5 arquivos de fake listados compilam e a suíte de testes completa passa.
      Testes: `TrackingSessionDaoTest.kt` — novo teste de `deleteAll()` com N sessões + M pontos cada, assertando ambas tabelas vazias; novo teste de `updateLocationName` assertando isolamento de coluna.

- [ ] T09 — Manter tela ativa durante a sessão de rastreamento
      Arquivos: `app/src/main/java/com/mytracksapp/ui/tracking/TrackingViewModel.kt`, `app/src/main/java/com/mytracksapp/ui/tracking/TrackingScreen.kt`, `app/src/test/java/com/mytracksapp/ui/tracking/TrackingViewModelTest.kt`, `app/src/androidTest/java/com/mytracksapp/ui/tracking/TrackingScreenTest.kt`
      Mudança: Adicionar `keepScreenOnEnabled: Boolean = true` a `TrackingUiState`, populado a partir do combine já existente com `settingsRepository.userSettings` em `TrackingViewModel.init`. Em `TrackingScreen`, `DisposableEffect(uiState.keepScreenOnEnabled)` usando `LocalView.current` para setar `keepScreenOn`, resetando para `false` em `onDispose`.
      Cobre: RF-07, RNF-04
      Acceptance criteria: com a preferência ativada, a `View` composta tem `keepScreenOn == true` enquanto `TrackingScreen` está em composição; ao sair da tela (ou desativar a preferência), `keepScreenOn` volta a `false`; nenhuma outra tela (Histórico, Configurações, `SessionDetailScreen`) é afetada.
      Testes: `TrackingViewModelTest.kt` — `keepScreenOnEnabled` reflete e reage a mudanças de `SettingsRepository`. `TrackingScreenTest.kt` — instrumentado, assertando o flag `keepScreenOn` da `View` ligado/desligado conforme a preferência e a composição da tela.

## Phase 3: Geocoding orchestration, distance persistence, export flow, and the two ViewModels

Antes de implementar, leia:
1. `.spec/features/history-settings-redesign/SPEC.md` — requisitos RIGID que esta fase cobre
2. `.spec/features/history-settings-redesign/PLAN.md` — decomposição completa, dependências e riscos

- [ ] T06 — FirstPointGeocodingCoordinator (orquestração geocodifica-uma-vez)
      Arquivos: `app/src/main/java/com/mytracksapp/domain/geocoding/FirstPointGeocodingCoordinator.kt` (novo), `app/src/test/java/com/mytracksapp/domain/geocoding/FirstPointGeocodingCoordinatorTest.kt` (novo)
      Mudança: Classe Android-free `FirstPointGeocodingCoordinator(reverseGeocoder, trackingSessionDao, coroutineScope)` expondo `onFirstPointRecorded(sessionId, latitude, longitude)`, implementado como `coroutineScope.launch { val name = runCatching { reverseGeocoder.reverseGeocode(lat, lon) }.getOrNull()?.takeIf { it.isNotBlank() }; if (name != null) trackingSessionDao.updateLocationName(sessionId, name) }`. Nenhuma escrita em caso de falha/nulo/blank (o default `null` já satisfaz RF-02).
      Cobre: RF-01, RF-02, RF-03, RNF-01
      Acceptance criteria: sucesso do `ReverseGeocoder` resulta em exatamente 1 chamada a `updateLocationName` com o nome geocodificado; exceção lançada pelo `ReverseGeocoder` resulta em 0 chamadas a `updateLocationName` e nenhuma exceção propagada ao chamador; retorno `null`/blank resulta em 0 chamadas; `onFirstPointRecorded` retorna antes da suspend function do `ReverseGeocoder` completar (fire-and-forget).
      Testes: `FirstPointGeocodingCoordinatorTest.kt` — os 4 cenários acima, com fakes de `ReverseGeocoder`/`TrackingSessionDao`.

- [ ] T08 — Persistir distância total ao encerrar a sessão
      Arquivos: `app/src/main/java/com/mytracksapp/domain/session/SessionControllerImpl.kt`, `app/src/test/java/com/mytracksapp/domain/session/SessionControllerTest.kt`, `app/src/androidTest/java/com/mytracksapp/e2e/TrackingSessionE2ETest.kt`
      Mudança: Em `stopSession`, computar `val distanceMeters = StatsEngine.totalDistanceMeters(points)` (pontos já buscados) e incluir `distanceMeters = distanceMeters` no `existing.copy(...)` junto aos demais campos finais já persistidos.
      Cobre: RF-13, CT-02
      Acceptance criteria: para toda sessão `FINISHED` com >= 2 pontos, `distanceMeters` persistido é igual a `StatsEngine.totalDistanceMeters` sobre os pontos daquela sessão.
      Testes: `SessionControllerTest.kt` — assert `stored.distanceMeters` no teste de encerramento existente. `TrackingSessionE2ETest.kt` — assert `finishedSession.distanceMeters` recomputado independentemente, mesmo padrão do `averageSpeedMetersPerSecond` já coberto.

- [ ] T10 — SessionDetailScreen exporta diretamente com o formato padrão
      Arquivos: `app/src/main/java/com/mytracksapp/ui/history/SessionDetailScreen.kt`, `app/src/androidTest/java/com/mytracksapp/ui/export/ExportFormatDialogTest.kt`, `app/src/androidTest/java/com/mytracksapp/ui/history/SessionDetailScreenTest.kt`, `app/src/androidTest/java/com/mytracksapp/ui/history/SessionDetailExportTest.kt` (novo)
      Mudança: Adicionar `defaultExportFormat: ExportFormat = ExportFormat.GPX` a `SessionDetailUiState`, populado pelo combine já existente com `settingsRepository.userSettings`. Remover `showExportDialog`/`ExportFormatDialog` de `SessionDetailScreen`; `onClick` do botão de exportar chama `coroutineScope.launch { onExport(uiState.sessionId, uiState.defaultExportFormat) }` diretamente. `ExportFormatDialog.kt` é mantido (não excluído), apenas desconectado da tela.
      Cobre: RF-11
      Acceptance criteria: com `defaultExportFormat = CSV`, tocar "Exportar" produz um `.csv` sem nenhum diálogo intermediário; `ExportFormatDialogTestTags.DIALOG` nunca aparece na árvore de nós durante o fluxo de exportação de `SessionDetailScreen`.
      Testes: mover os testes de visibilidade do botão de `ExportFormatDialogTest.kt` para `SessionDetailScreenTest.kt`; criar `SessionDetailExportTest.kt` (novo) cobrindo o export direto sem diálogo; reduzir `ExportFormatDialogTest.kt` a testar apenas o composable `ExportFormatDialog` isolado (sem `SessionDetailScreen`).

- [ ] T11 — HistoryViewModel: nome de local, distância, formatação por unidade
      Arquivos: `app/src/main/java/com/mytracksapp/ui/history/HistoryViewModel.kt`, `app/src/test/java/com/mytracksapp/ui/history/HistoryViewModelTest.kt`
      Mudança: Adicionar parâmetro de construtor `settingsRepository: SettingsRepository`; substituir o `collect` único por `combine(trackingSessionDao.getSessionsByStatus(FINISHED), settingsRepository.userSettings) { ... }` (mesmo padrão de `SessionDetailViewModel`/`TrackingViewModel`). Adicionar `locationName: String?` e `distanceMeters: Double` a `HistoryListItem`, mais `formattedLocationName` (placeholder genérico não-vazio quando `null`) e formatação de distância ciente da unidade configurada (`DistanceFormatter.toDisplayValue` + `unit.displaySuffix`).
      Cobre: RF-13 (dados), UI-01, UI-02 (dados)
      Acceptance criteria: uma sessão com `locationName` preenchido expõe exatamente esse valor; uma sessão sem `locationName` expõe um placeholder genérico não-vazio; a distância formatada reflete a unidade atualmente configurada em `SettingsRepository`, reagindo a mudanças ao vivo.
      Testes: `HistoryViewModelTest.kt` — fixtures com/sem `locationName`, assert do placeholder; assert de reatividade à mudança de `distanceUnit`.

- [ ] T12 — SettingsViewModel: precisão de GPS, tela ativa, formato de exportação, limpar histórico
      Arquivos: `app/src/main/java/com/mytracksapp/ui/settings/SettingsViewModel.kt`, `app/src/androidTest/java/com/mytracksapp/ui/settings/SettingsViewModelTest.kt` (novo)
      Mudança: Adicionar parâmetro de construtor `trackingSessionDao: TrackingSessionDao`. Adicionar `gpsPrecision`, `keepScreenOnEnabled`, `defaultExportFormat` a `SettingsUiState`, populados pelo `collect` já existente. Adicionar `onGpsPrecisionSelected`/`onKeepScreenOnToggled`/`onDefaultExportFormatSelected` (cada um `viewModelScope.launch { settingsRepository.setX(value) }`, persistência imediata). Adicionar `fun clearHistory() { viewModelScope.launch { trackingSessionDao.deleteAll() } }` (chamador confirma antes de chamar, mesmo contrato de `HistoryViewModel.deleteSession`).
      Cobre: RF-04, RF-06, RF-10, RF-12 (estado/dados); habilita UI-04, UI-05, UI-07, UI-08
      Acceptance criteria: selecionar cada uma das 3 novas opções persiste imediatamente via `SettingsRepository`; `clearHistory()` chama `trackingSessionDao.deleteAll()` exatamente uma vez por invocação.
      Testes: `SettingsViewModelTest.kt` (novo) — persistência imediata das 3 novas opções; `clearHistory()` chama `deleteAll()` uma vez com um fake `TrackingSessionDao`.

## Phase 4: Service wiring, navigation wiring, and the two redesigned screens

Antes de implementar, leia:
1. `.spec/features/history-settings-redesign/SPEC.md` — requisitos RIGID que esta fase cobre
2. `.spec/features/history-settings-redesign/PLAN.md` — decomposição completa, dependências e riscos

- [ ] T07 — Conectar geocodificação + precisão de GPS ao LocationForegroundService
      Arquivos: `app/src/main/java/com/mytracksapp/service/LocationForegroundService.kt`, `app/src/test/java/com/mytracksapp/service/FusedLocationSampleSourceTest.kt` (novo)
      Mudança: Adicionar parâmetro `priority: Int` (default `Priority.PRIORITY_HIGH_ACCURACY`) a `FusedLocationSampleSource`, usado no `LocationRequest.Builder`. Adicionar função pura `fun GpsPrecision.toLocationRequestPriority(): Int` mapeando `HIGH_ACCURACY`/`BALANCED` para os 2 valores de `Priority`. Em `onStartCommand`: manter `startForeground(...)` síncrono primeiro; mover a construção dependente de settings (`FusedLocationSampleSource`, `LocationCollector`, `AndroidReverseGeocoder`, `FirstPointGeocodingCoordinator`) para dentro do `serviceScope.launch` já existente, lendo `settingsRepository.userSettings.first().gpsPrecision` uma única vez ali; passar `onFirstPointRecorded = coordinator::onFirstPointRecorded` ao `LocationCollector`.
      Cobre: RF-01, RF-02, RF-03, RF-05, RNF-01, RNF-02
      Acceptance criteria: com precisão "Equilibrada" configurada, uma nova sessão constrói um `LocationRequest` com `PRIORITY_BALANCED_POWER_ACCURACY`; alterar a preferência durante uma sessão já ativa não altera o `LocationRequest` daquela sessão; `startForeground` continua sendo chamado de forma síncrona antes de qualquer leitura de settings.
      Testes: `FusedLocationSampleSourceTest.kt` (novo) — `GpsPrecision.HIGH_ACCURACY.toLocationRequestPriority()`/`BALANCED.toLocationRequestPriority()` retornam as constantes `Priority` corretas.

- [ ] T13 — Conectar novas dependências de ViewModel em factories e navegação
      Arquivos: `app/src/main/java/com/mytracksapp/ui/ViewModelFactories.kt`, `app/src/main/java/com/mytracksapp/ui/navigation/AppNavigation.kt`
      Mudança: `HistoryViewModelFactory` passa a receber também `settingsRepository: SettingsRepository`. `SettingsViewModelFactory` passa a receber também `trackingSessionDao: TrackingSessionDao`. Atualizar `HistoryRoute`/`SettingsRoute` em `AppNavigation.kt` para repassar essas dependências (ambas já disponíveis em `MyTracksApp`).
      Cobre: habilita T11/T12 em produção
      Acceptance criteria: o app compila e navega normalmente para Histórico e Configurações com as novas dependências injetadas via as factories atualizadas.
      Testes: nenhum teste novo (wiring mecânico; coberto transitivamente pelos testes de T11/T12/T14/T15).

- [ ] T14 — HistoryListScreen: redesign Organic + local/distância/seta
      Arquivos: `app/src/main/java/com/mytracksapp/ui/history/HistoryListScreen.kt`, `app/src/androidTest/java/com/mytracksapp/ui/history/HistoryListScreenTest.kt`
      Mudança: Restilizar o card por `design_handoff_my_tracks/my-tracks-design.html`/`organic-styles.css` (fundo `ColorSurface`, `radius-lg`, `locationName` como título 15px/700, linha secundária 13px `Neutral700` com "data · Duração: X · distância", ícone de seta/chevron indicando clicabilidade). Preservar swipe-to-delete, diálogo de confirmação e todas as test tags existentes; adicionar novas test tags para nome de local, distância e seta.
      Cobre: UI-01, UI-02, UI-03, UI-09
      Acceptance criteria: todo card exibe nome de local (ou placeholder genérico não-vazio) em destaque; a linha secundária contém data, duração e distância nessa ordem; todo card tem um ícone de seta visível; swipe-to-delete e o diálogo de confirmação continuam funcionando exatamente como antes.
      Testes: `HistoryListScreenTest.kt` — asserts de local/distância/seta; testes de swipe/click/empty-state existentes continuam passando sem modificação de comportamento.

- [ ] T15 — SettingsScreen: redesign Organic + 4 controles novos
      Arquivos: `app/src/main/java/com/mytracksapp/ui/settings/SettingsScreen.kt`, `app/src/androidTest/java/com/mytracksapp/ui/settings/SettingsScreenTest.kt`
      Mudança: Restilizar por `design_handoff_my_tracks/my-tracks-design.html` (labels de seção 13px/700 uppercase, segmented controls `.seg`/`.seg-opt`, switches pill 44x26/20dp), preservando o seletor de 10 valores de `SamplingInterval`, os seletores de unidade e os 2 campos numéricos sem alteração de comportamento/test tags. Adicionar 4 seções novas: (1) segmented control "Precisão do GPS" (UI-04); (2) switch pill "Manter tela ativa durante a sessão" (UI-05, SEM o toggle "Alerta de pausa longa" do mockup — feature removida); (3) controle "Formato de exportação" mostrando o valor atual (UI-07); (4) ação destrutiva "Limpar histórico de trilhas" com diálogo de confirmação obrigatório antes de `viewModel.clearHistory()` (UI-08).
      Cobre: UI-04, UI-05, UI-07, UI-08, UI-09
      Acceptance criteria: cada um dos 3 novos seletores/switch persiste imediatamente ao toque; "Limpar histórico de trilhas" sempre abre um diálogo de confirmação antes de qualquer exclusão, e a exclusão só ocorre após confirmação explícita; os controles pré-existentes (10 intervalos, unidades, raio/duração de parada) continuam funcionando sem regressão.
      Testes: `SettingsScreenTest.kt` — persistência imediata dos 3 novos controles; fluxo completo de "Limpar histórico" (abrir diálogo -> cancelar não exclui -> confirmar exclui exatamente uma vez); testes pré-existentes continuam passando.
