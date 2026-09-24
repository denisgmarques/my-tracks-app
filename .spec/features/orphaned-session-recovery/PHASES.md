# Phases: orphaned-session-recovery

Gerado por /plan a partir de PLAN.md — view executável para `./ralph.sh .spec/features/orphaned-session-recovery/PHASES.md`.

## Phase 1: Extract shared finalization math

Antes de implementar, leia:
1. `.spec/features/orphaned-session-recovery/SPEC.md` — requisitos RIGID que esta fase cobre
2. `.spec/features/orphaned-session-recovery/PLAN.md` — decomposição completa, dependências e riscos

- [ ] T01 — Extract `finalizeSession` as the single source of truth for RF-03's math
      Arquivos: `app/src/main/java/com/mytracksapp/domain/session/SessionControllerImpl.kt`
      Mudança: extrair o bloco de finalização de `stopSession` (SegmentClassifier + StatsEngine + `existing.copy(...)`, linhas 110-124) para uma função interna pura `finalizeSession(existing, points, endTimestampFallback): TrackingSessionEntity`, sem chamada a DAO; `stopSession` passa a chamar `trackingSessionDao.update(finalizeSession(existing, points, endTimestampFallback = clock()))`, comportamento idêntico ao atual.
      Cobre: RF-03
      Acceptance criteria: `SessionControllerTest` passa sem nenhuma mudança de comportamento (mesmos valores persistidos, com e sem pontos); `finalizeSession` é chamável a partir de `OrphanedSessionRecovery.kt` (mesmo módulo) com visibilidade `internal`.
      Testes: `app/src/test/java/com/mytracksapp/domain/session/SessionControllerTest.kt` — suíte existente sem alterações de resultado; adicionar/confirmar asserção de paridade antes/depois da extração para os casos com e sem pontos.

## Phase 2: New OrphanedSessionRecovery domain class

Antes de implementar, leia:
1. `.spec/features/orphaned-session-recovery/SPEC.md` — requisitos RIGID que esta fase cobre
2. `.spec/features/orphaned-session-recovery/PLAN.md` — decomposição completa, dependências e riscos

- [ ] T02 — New `OrphanedSessionRecovery` domain class
      Arquivos: `app/src/main/java/com/mytracksapp/domain/session/OrphanedSessionRecovery.kt` (novo)
      Mudança: classe Android-free recebendo `TrackingSessionDao`/`GpsPointDao`, com `suspend fun recover(): Int`: lê `getSessionsByStatus(ACTIVE)` (CT-01), para cada sessão lê `getPointsForSession(id)` (CT-03, somente leitura), chama `finalizeSession(session, points, endTimestampFallback = session.startTimestamp)` (T01, divergência RF-02), persiste via `update(...)` (CT-02), calcula métricas isoladamente por sessão (RF-05), nunca chama `insert`/`insertAll` de `GpsPointDao` (RF-04), retorna a contagem de sessões finalizadas.
      Cobre: RF-01, RF-02, RF-03, RF-04, RF-05
      Acceptance criteria: zero sessões `ACTIVE` → retorna 0 e nenhum `update` é chamado; sessão `ACTIVE` sem pontos → `FINISHED` com `endTimestamp == startTimestamp` e as 4 métricas zeradas, sem exceção; sessão `ACTIVE` com pontos → métricas numericamente idênticas às que `finalizeSession`/`stopSession` produziriam para o mesmo conjunto; duas ou mais sessões `ACTIVE` com pontos distintos → todas `FINISHED` na mesma chamada, métricas isoladas por sessão; nenhuma chamada a `GpsPointDao.insert`/`insertAll` ocorre.
      Testes: `app/src/test/java/com/mytracksapp/domain/session/OrphanedSessionRecoveryTest.kt` (novo) — fakes no estilo de `SessionControllerTest`, cobrindo os cinco casos do Acceptance criteria acima.

## Phase 3: Wire recovery into the composition root

Antes de implementar, leia:
1. `.spec/features/orphaned-session-recovery/SPEC.md` — requisitos RIGID que esta fase cobre
2. `.spec/features/orphaned-session-recovery/PLAN.md` — decomposição completa, dependências e riscos

- [ ] T03 — Wire recovery into the composition root, non-blocking, with a Snackbar
      Arquivos: `app/src/main/java/com/mytracksapp/MainActivity.kt`, `app/src/main/java/com/mytracksapp/ui/navigation/AppNavigation.kt`
      Mudança: em `MainActivity.onCreate`, instanciar `OrphanedSessionRecovery(trackingSessionDao, gpsPointDao)` junto às demais dependências manuais e passá-la como novo parâmetro a `MyTracksApp(...)`. Em `MyTracksApp`, adicionar parâmetro `orphanedSessionRecovery: OrphanedSessionRecovery`; adicionar `SnackbarHostState` e ligá-lo ao `Scaffold` existente via `snackbarHost = { SnackbarHost(snackbarHostState) }` (slot ainda não declarado); adicionar `var recoveredCount by remember { mutableStateOf<Int?>(null) }` com `LaunchedEffect(Unit) { recoveredCount = orphanedSessionRecovery.recover() }` (nunca aguardado pelo resto da árvore — RNF-01); adicionar `LaunchedEffect(recoveredCount)` que chama `snackbarHostState.showSnackbar(...)` somente quando `recoveredCount != null && recoveredCount > 0` (UI-01).
      Cobre: RF-01, RNF-01, UI-01
      Acceptance criteria: com um fake `recover()` que nunca completa (`delay(Long.MAX_VALUE)`), a tag da tela inicial (`HistoryListScreenTestTags.SCREEN`) ainda é exibida imediatamente; com um fake retornando `2`, um Snackbar aparece e a navegação pela barra inferior continua funcionando; com um fake retornando `0`, nenhum Snackbar aparece; as suítes existentes (`HistoryListScreenTest`, `NewSessionScreenTest`, `MainActivityTest`, etc.) continuam passando sem alteração.
      Testes: `app/src/androidTest/java/com/mytracksapp/ui/navigation/OrphanedSessionRecoverySnackbarTest.kt` (novo) — `composeTestRule.setContent { MyTracksApp(..., orphanedSessionRecovery = <fake>) }` cobrindo os três casos do Acceptance criteria acima.

## Phase 4: Real-device end-to-end regression

Antes de implementar, leia:
1. `.spec/features/orphaned-session-recovery/SPEC.md` — requisitos RIGID que esta fase cobre
2. `.spec/features/orphaned-session-recovery/PLAN.md` — decomposição completa, dependências e riscos

- [ ] T04 — Real-device end-to-end regression (RF-01, RF-05, RF-06)
      Arquivos: `app/src/androidTest/java/com/mytracksapp/e2e/OrphanedSessionRecoveryE2ETest.kt` (novo)
      Mudança: usando `ApplicationProvider.getApplicationContext()` + `AppDatabase.getInstance(context)` (o mesmo singleton usado por `MainActivity` em produção), semear via os DAOs reais, antes de lançar a Activity: (a) uma sessão `ACTIVE` sem pontos, (b) uma sessão `ACTIVE` com pontos, (c) uma segunda sessão `ACTIVE` concorrente com pontos distintos (RF-05); controlar o lançamento manualmente (`createEmptyComposeRule()` + `ActivityScenario.launch(MainActivity::class.java)` dentro do corpo do teste, após a semeadura, já que `createAndroidComposeRule<MainActivity>()` lança a Activity antes de qualquer semeadura possível); após o lançamento, verificar que todas as linhas semeadas estão `FINISHED` no banco e aparecem em `HistoryListScreen` com métricas isoladas por sessão e sem nenhum rótulo de "recuperada" (RF-06); `@After` remove as sessões semeadas.
      Cobre: RF-01, RF-05, RF-06
      Acceptance criteria: as três linhas semeadas ficam `FINISHED` após o cold start, cada uma com métricas calculadas exclusivamente a partir dos seus próprios pontos; `HistoryListScreen` renderiza cada uma de forma idêntica a uma sessão finalizada manualmente, sem nenhum selo distintivo; a remoção das linhas semeadas ocorre no `@After` independentemente do resultado do teste.
      Testes: o próprio arquivo `OrphanedSessionRecoveryE2ETest.kt` (instrumentado).
