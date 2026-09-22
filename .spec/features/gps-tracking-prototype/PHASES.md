# Phases: gps-tracking-prototype

Gerado por /plan a partir de PLAN.md — view executável para `./ralph.sh .spec/features/gps-tracking-prototype/PHASES.md`.

## Phase 1: Project scaffolding

Antes de implementar, leia:
1. `.spec/features/gps-tracking-prototype/SPEC.md` — requisitos RIGID que esta fase cobre
2. `.spec/features/gps-tracking-prototype/PLAN.md` — decomposição completa, dependências e riscos

- [ ] T01 — Project scaffolding (Gradle, manifest base, minSdk 29)
      Arquivos: `build.gradle.kts`, `app/build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`, `app/src/main/AndroidManifest.xml`
      Mudança: criar módulo `:app` Kotlin com `minSdkVersion=29`; declarar dependências base (Room, Play Services Location, Google Maps SDK for Android `com.google.android.gms:play-services-maps`, UI framework, coroutines/lifecycle); manifest inicial sem permissão de rede. Google Maps SDK for Android requer chave de API do Google Cloud — configuração da chave é responsabilidade de T09 (não commitar valor real da chave).
      Cobre: RNF-01, RNF-05, RNF-02
      Acceptance criteria: `./gradlew assembleDebug` conclui com sucesso; `app/build.gradle.kts` declara `minSdk = 29`; `AndroidManifest.xml` não contém `<uses-permission android:name="android.permission.INTERNET"/>`.
      Testes: N/A (config) — validado via `./gradlew assembleDebug` bem-sucedido.

## Phase 2: Data layer e permissões

Antes de implementar, leia:
1. `.spec/features/gps-tracking-prototype/SPEC.md` — requisitos RIGID que esta fase cobre
2. `.spec/features/gps-tracking-prototype/PLAN.md` — decomposição completa, dependências e riscos

- [ ] T02 — Data layer: Room entities & DAOs
      Arquivos: `app/src/main/java/com/mytracksapp/data/local/entity/TrackingSessionEntity.kt`, `app/src/main/java/com/mytracksapp/data/local/entity/GpsPointEntity.kt`, `app/src/main/java/com/mytracksapp/data/local/dao/TrackingSessionDao.kt`, `app/src/main/java/com/mytracksapp/data/local/dao/GpsPointDao.kt`, `app/src/main/java/com/mytracksapp/data/local/AppDatabase.kt`
      Mudança: definir `TrackingSessionEntity` e `GpsPointEntity` (FK para sessão); DAOs com insert/query por sessão como `Flow`.
      Cobre: RF-02, RF-04, RF-07
      Acceptance criteria: inserir uma sessão com N pontos e reler do banco retorna N pontos com todos os campos intactos.
      Testes: `app/src/androidTest/java/com/mytracksapp/data/local/TrackingSessionDaoTest.kt` — insert + query roundtrip de sessão e pontos.
- [ ] T03 — Location permission handling (API 29+ background location)
      Arquivos: `app/src/main/java/com/mytracksapp/permission/LocationPermissionManager.kt`
      Mudança: encapsular verificação de `ACCESS_FINE_LOCATION` + `ACCESS_BACKGROUND_LOCATION` (API 29+); expor `isBackgroundLocationGranted(): Boolean`.
      Cobre: RF-03
      Acceptance criteria: em API 29+, `isBackgroundLocationGranted()` retorna `false` quando a permissão não foi concedida, sem exceção lançada.
      Testes: `app/src/test/java/com/mytracksapp/permission/LocationPermissionManagerTest.kt` — API 29+ sem `ACCESS_BACKGROUND_LOCATION` concedida retorna `false`.

## Phase 3: Seleção de intervalo e engine de estatísticas

Antes de implementar, leia:
1. `.spec/features/gps-tracking-prototype/SPEC.md` — requisitos RIGID que esta fase cobre
2. `.spec/features/gps-tracking-prototype/PLAN.md` — decomposição completa, dependências e riscos

- [ ] T04 — New Session UI (seleção de intervalo, 9 valores fixos)
      Arquivos: `app/src/main/java/com/mytracksapp/domain/model/SamplingInterval.kt`, `app/src/main/java/com/mytracksapp/ui/newsession/NewSessionScreen.kt`, `app/src/main/java/com/mytracksapp/ui/newsession/NewSessionViewModel.kt`
      Mudança: enum `SamplingInterval` com exatamente os 9 valores `{3,5,10,15,30,45,60,120,180}` segundos; tela apresenta as 9 opções sem campo de entrada livre; ao confirmar, delega a `SessionController` com verificação de permissão prévia.
      Cobre: RF-01, UI-01
      Acceptance criteria: nenhum valor fora do conjunto `{3,5,10,15,30,45,60,120,180}` é representável pelo tipo `SamplingInterval`; a tela não expõe campo de texto/numérico livre para o intervalo.
      Testes: `app/src/test/java/com/mytracksapp/domain/model/SamplingIntervalTest.kt` — apenas os 9 valores válidos existem; `app/src/androidTest/java/com/mytracksapp/ui/newsession/NewSessionScreenTest.kt` — 9 opções renderizadas, sem input livre.
- [ ] T07 — StatsEngine (velocidade, tempo total, classificação parado/em movimento)
      Arquivos: `app/src/main/java/com/mytracksapp/domain/stats/StatsEngine.kt`, `app/src/main/java/com/mytracksapp/domain/stats/DistanceCalculator.kt`, `app/src/main/java/com/mytracksapp/domain/stats/SegmentClassifier.kt`
      Mudança: calcular velocidade instantânea e média a cada novo ponto; calcular tempo total decorrido; classificar segmentos contínuos (raio ≤150m por >300s) como "parado", restante como "em movimento".
      Cobre: RF-04, RF-05, RF-06
      Acceptance criteria: velocidade instantânea/média batem com cálculo de referência para pontos conhecidos; tempo("parado") + tempo("em movimento") == tempo total decorrido para qualquer sessão; casos-limite exatamente 150m/300s cobertos.
      Testes: `app/src/test/java/com/mytracksapp/domain/stats/StatsEngineTest.kt` — pares de pontos conhecidos produzem velocidade esperada; `app/src/test/java/com/mytracksapp/domain/stats/SegmentClassifierTest.kt` — casos-limite de raio/duração e invariante da soma de tempos.

## Phase 4: Orquestração de sessão, histórico e exportação (núcleo)

Antes de implementar, leia:
1. `.spec/features/gps-tracking-prototype/SPEC.md` — requisitos RIGID que esta fase cobre
2. `.spec/features/gps-tracking-prototype/PLAN.md` — decomposição completa, dependências e riscos
3. `.spec/features/gps-tracking-prototype/gpx-export.xsd` e `.spec/features/gps-tracking-prototype/csv-export-schema.json` — contratos formais de exportação (CT-01/CT-02) implementados por T11

- [ ] T05 — SessionController (orquestração do ciclo de vida da sessão)
      Arquivos: `app/src/main/java/com/mytracksapp/domain/session/SessionController.kt`
      Mudança: orquestrar criação de sessão somente se intervalo válido (RF-01) e permissão concedida (RF-03); iniciar/parar `LocationForegroundService`; ao encerrar, disparar cálculo final via `StatsEngine` e persistir metadados (RF-07).
      Cobre: RF-01, RF-03, RF-07
      Acceptance criteria: início com intervalo inválido não cria sessão nem grava no banco; início sem permissão de background location não cria sessão, não inicia o serviço e nenhum ponto é gravado.
      Testes: `app/src/test/java/com/mytracksapp/domain/session/SessionControllerTest.kt` — intervalo inválido não cria sessão; permissão ausente bloqueia início e não grava nenhum ponto.
- [ ] T10 — HistoryUI (lista de sessões passadas + detalhe)
      Arquivos: `app/src/main/java/com/mytracksapp/ui/history/HistoryListScreen.kt`, `app/src/main/java/com/mytracksapp/ui/history/HistoryViewModel.kt`, `app/src/main/java/com/mytracksapp/ui/history/SessionDetailScreen.kt`
      Mudança: listar sessões persistidas (id, data/hora de início, intervalo configurado); tela de detalhe reexibe as 5 métricas de UI-03 para sessão encerrada.
      Cobre: UI-04, UI-03
      Acceptance criteria: toda sessão persistida por RF-07 aparece na lista com id, data/hora de início e intervalo preenchidos; a tela de detalhe exibe as 5 métricas sem navegação adicional.
      Testes: `app/src/test/java/com/mytracksapp/ui/history/HistoryViewModelTest.kt` — sessões mapeadas para itens de lista com os 3 campos mínimos; `app/src/androidTest/java/com/mytracksapp/ui/history/SessionDetailScreenTest.kt` — 5 métricas visíveis na tela de detalhe.
- [ ] T11 — ExportService (geração de arquivo GPX/CSV)
      Arquivos: `app/src/main/java/com/mytracksapp/domain/export/ExportService.kt`, `app/src/main/java/com/mytracksapp/domain/export/GpxExporter.kt`, `app/src/main/java/com/mytracksapp/domain/export/CsvExporter.kt`
      Mudança: gerar arquivo GPX (um `<trkpt>` por ponto, `<time>` ISO-8601) conforme `gpx-export.xsd`; gerar arquivo CSV com colunas `session_id` (string UUID, decisão registrada em 2026-09-22), `timestamp`, `latitude`, `longitude`, `accuracy`, `speed_instant`, `segment_status` (literais exatos `moving`/`stopped`, decisão registrada em 2026-09-22 — mapear o estado interno do `SegmentClassifier` para esses literais na serialização) conforme `csv-export-schema.json`, uma linha por ponto.
      Cobre: RF-08, CT-01, CT-02
      Acceptance criteria: arquivo GPX válido contra `gpx-export.xsd` com exatamente um `<trkpt>` por ponto coletado; arquivo CSV com exatamente uma linha por ponto e os 7 campos mínimos de `csv-export-schema.json` na ordem especificada, com `session_id` em formato UUID string e `segment_status` restrito aos literais `moving`/`stopped`; ambos formatos disponíveis para sessão "encerrada".
      Testes: `app/src/test/java/com/mytracksapp/domain/export/GpxExporterTest.kt` — validação estrutural contra `gpx-export.xsd`; `app/src/test/java/com/mytracksapp/domain/export/CsvExporterTest.kt` — validação contra `csv-export-schema.json`, incluindo enum de `segment_status` e formato UUID de `session_id`.

## Phase 5: Foreground service e UI de exportação

Antes de implementar, leia:
1. `.spec/features/gps-tracking-prototype/SPEC.md` — requisitos RIGID que esta fase cobre
2. `.spec/features/gps-tracking-prototype/PLAN.md` — decomposição completa, dependências e riscos
3. `.spec/features/gps-tracking-prototype/csv-export-schema.json` e `.spec/features/gps-tracking-prototype/gpx-export.xsd` — contratos de exportação acionados pela UI de T12

- [ ] T06 — LocationForegroundService (coleta periódica de pontos GPS)
      Arquivos: `app/src/main/java/com/mytracksapp/service/LocationForegroundService.kt`, `app/src/main/java/com/mytracksapp/service/LocationCollector.kt`, `app/src/main/AndroidManifest.xml`
      Mudança: usar `FusedLocationProviderClient` (Google Play Services confirmado disponível no(s) dispositivo(s) de teste do desenvolvedor — decisão registrada em 2026-09-22, sem fallback para `LocationManager` puro) com `LocationRequest` configurado pelo intervalo da sessão; a cada callback, gravar ponto (lat, lon, timestamp real, accuracy, desvio observado); recusar coleta se não houver sessão ativa.
      Cobre: RF-02, RF-03, RNF-01, RNF-03
      Acceptance criteria: para pares de pontos consecutivos com sinal disponível, o intervalo real observado é registrado junto ao ponto; nenhum ponto é gravado quando não há sessão ativa.
      Testes: `app/src/test/java/com/mytracksapp/service/LocationCollectorTest.kt` — provedor falso emite pontos com desvio observado registrado; `app/src/test/java/com/mytracksapp/service/LocationForegroundServiceStartGuardTest.kt` — serviço recusa coleta sem sessão ativa/permissão.
- [ ] T12 — Export UI (seletor de formato GPX/CSV)
      Arquivos: `app/src/main/java/com/mytracksapp/ui/history/SessionDetailScreen.kt`, `app/src/main/java/com/mytracksapp/ui/export/ExportFormatDialog.kt`
      Mudança: adicionar ação de exportação na tela de detalhe de sessão encerrada; ao acionar, exibir seletor GPX/CSV; disparar `ExportService` com o formato escolhido.
      Cobre: UI-05, RF-08
      Acceptance criteria: a ação de exportação está disponível apenas para sessões "encerrada"; ao ser acionada, apresenta seletor com GPX e CSV e dispara a geração do arquivo no formato escolhido.
      Testes: `app/src/androidTest/java/com/mytracksapp/ui/export/ExportFormatDialogTest.kt` — ação visível apenas para sessão encerrada; seleção de cada formato invoca `ExportService` com o argumento correto.

## Phase 6: TrackingViewModel

Antes de implementar, leia:
1. `.spec/features/gps-tracking-prototype/SPEC.md` — requisitos RIGID que esta fase cobre
2. `.spec/features/gps-tracking-prototype/PLAN.md` — decomposição completa, dependências e riscos

- [ ] T08 — TrackingViewModel (estado reativo de polyline e métricas ao vivo)
      Arquivos: `app/src/main/java/com/mytracksapp/ui/tracking/TrackingViewModel.kt`
      Mudança: observar `Flow` da sessão ativa (Room), delegar a `StatsEngine` o recálculo a cada nova emissão, expor estado de UI (polyline + 5 métricas).
      Cobre: UI-02, UI-03, RNF-04
      Acceptance criteria: após um novo ponto ser persistido no Room, o estado exposto pelo ViewModel reflete esse ponto (último vértice da polyline + métricas recalculadas) no ciclo de coleta subsequente do `Flow`.
      Testes: `app/src/test/java/com/mytracksapp/ui/tracking/TrackingViewModelTest.kt` — fluxo simulado de emissões atualiza polyline/métricas a cada emissão.

## Phase 7: MapUI

Antes de implementar, leia:
1. `.spec/features/gps-tracking-prototype/SPEC.md` — requisitos RIGID que esta fase cobre
2. `.spec/features/gps-tracking-prototype/PLAN.md` — decomposição completa, dependências e riscos

- [ ] T09 — MapUI (tela de sessão ativa: polyline + métricas)
      Arquivos: `app/src/main/java/com/mytracksapp/ui/tracking/TrackingScreen.kt`, `app/src/main/java/com/mytracksapp/ui/tracking/MapComponent.kt`, `app/src/main/AndroidManifest.xml` (meta-data `com.google.android.geo.API_KEY`), `app/src/main/res/values/google_maps_api.xml` (novo — placeholder de chave, valor real não versionado)
      Mudança: renderizar mapa com Google Maps SDK for Android (`com.google.android.gms.maps.MapView`/`GoogleMap`) — decisão registrada em 2026-09-22, substitui a alternativa OSMDroid antes em aberto — com polyline atualizada a partir do estado do `TrackingViewModel`; exibir simultaneamente as 5 métricas de UI-03. Dependência declarada em T01; chave de API do Google Cloud configurada via `google_maps_api.xml`/manifest, não commitada em texto real.
      Cobre: UI-02, UI-03
      Acceptance criteria: a cada novo ponto no estado do ViewModel, a polyline exibida passa a incluir esse ponto como último vértice; as 5 métricas estão visíveis simultaneamente sem navegação adicional; `MapComponent.kt` referencia a API do Google Maps SDK (`com.google.android.gms.maps.*`).
      Testes: `app/src/androidTest/java/com/mytracksapp/ui/tracking/TrackingScreenTest.kt` — polyline atualiza ao adicionar ponto; 5 métricas visíveis simultaneamente.

## Phase 8: Validação de integração e Doze mode

Antes de implementar, leia:
1. `.spec/features/gps-tracking-prototype/SPEC.md` — requisitos RIGID que esta fase cobre
2. `.spec/features/gps-tracking-prototype/PLAN.md` — decomposição completa, dependências e riscos

- [ ] T13 — Validação de integração e comportamento em Doze mode
      Arquivos: `app/src/androidTest/java/com/mytracksapp/e2e/TrackingSessionE2ETest.kt`, `docs/testing/manual-doze-mode-validation.md`
      Mudança: teste instrumentado de ciclo completo de sessão (criar → coletar N pontos simulados → encerrar → verificar persistência); documentar checklist de teste manual em dispositivo físico atravessando Doze mode, registrando desvio observado por ponto e confirmando ausência de tráfego de rede.
      Cobre: RF-02, RNF-02, RNF-03, RNF-04
      Acceptance criteria: teste instrumentado passa cobrindo criação→coleta→encerramento→persistência de uma sessão completa; checklist manual permite confirmar em dispositivo físico que pontos continuam sendo registrados durante Doze mode e que nenhuma chamada de rede é observada durante a sessão.
      Testes: `TrackingSessionE2ETest.kt` (automatizado) + checklist manual documentado em `docs/testing/manual-doze-mode-validation.md`.
