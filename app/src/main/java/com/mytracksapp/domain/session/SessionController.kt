package com.mytracksapp.domain.session

import com.mytracksapp.domain.model.SamplingInterval

/**
 * Orchestrates a tracking session's lifecycle (RF-01, RF-03, RF-07): validating the requested
 * sampling interval, verifying background location permission, creating/persisting the session,
 * starting the collection service, and — on stop — finalizing metrics and persisting metadata.
 *
 * This interface is the contract that [com.mytracksapp.ui.newsession.NewSessionViewModel] (T04)
 * depends on. **It is not implemented in T04** — the concrete implementation is T05
 * (`domain.session.SessionControllerImpl`, Phase 4 of PLAN.md), which will wire in
 * `LocationPermissionManager` (T03), the Room DAOs (T02) and `LocationForegroundService` (T06).
 * T04 depends only on this interface so NewSessionViewModel can be built, tested (with a fake),
 * and shipped ahead of T05's implementation, per PLAN.md's phase ordering (T04 in Phase 3, T05 in
 * Phase 4).
 *
 * Contract notes for the T05 implementer:
 *  - [startSession] receives a [SamplingInterval], a type that can only ever hold one of the 9
 *    RF-01-allowed values — no "invalid interval" outcome is needed in [SessionStartOutcome].
 *  - Callers (e.g. NewSessionViewModel) are expected to have already checked
 *    `LocationPermissionManager.isBackgroundLocationGranted()` before calling [startSession], per
 *    RF-03. An implementation MAY still re-verify permission defensively before creating a
 *    session/starting the service (PLAN.md's SessionController T05 acceptance criteria requires
 *    exactly this: "chamada de início sem permissão ... não cria sessão, não inicia o serviço e
 *    nenhum ponto é gravado") — hence [SessionStartOutcome.PermissionDenied] exists as a possible
 *    outcome here too, so the UI layer can surface a message even if its own pre-check was
 *    bypassed or raced by a permission revocation.
 *  - On success, no GPS point may have been recorded yet (RF-01: "criar uma nova sessão ... antes
 *    de iniciar qualquer coleta de ponto GPS") — [SessionStartOutcome.Started] signals the session
 *    was created and collection was (or is about to be) started, not that any point yet exists.
 */
interface SessionController {

    /**
     * Starts a new tracking session sampling at [interval].
     *
     * @return [SessionStartOutcome.Started] with the new session's id on success, or
     *   [SessionStartOutcome.PermissionDenied] if background location permission (RF-03) is not
     *   granted, in which case no session is created and no point is recorded.
     */
    suspend fun startSession(interval: SamplingInterval): SessionStartOutcome

    /**
     * Stops the active session identified by [sessionId]: stops the collection service, triggers
     * final metric computation (via StatsEngine, T07) and persists the session's end metadata
     * (RF-04, RF-07).
     */
    suspend fun stopSession(sessionId: String)
}

/** Outcome of [SessionController.startSession]. */
sealed interface SessionStartOutcome {

    /**
     * The session was created and collection was started.
     *
     * @property sessionId the id of the newly created [com.mytracksapp.data.local.entity.TrackingSessionEntity],
     *   for the caller to navigate to the tracking screen / observe collection.
     */
    data class Started(val sessionId: String) : SessionStartOutcome

    /**
     * The session was NOT created because background location permission (RF-03) is not
     * granted. No session row was persisted and no collection was started.
     */
    data object PermissionDenied : SessionStartOutcome
}
