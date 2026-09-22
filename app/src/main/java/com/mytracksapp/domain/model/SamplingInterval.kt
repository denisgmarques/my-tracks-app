package com.mytracksapp.domain.model

/**
 * The sampling interval a user may select when starting a new tracking session (RF-01, UI-01).
 *
 * RF-01's AC requires that "qualquer valor fora do conjunto [3,5,10,15,30,45,60,120,180] é
 * rejeitado e nenhuma sessão é criada" — this is satisfied *structurally*, not by runtime
 * validation: [SamplingInterval] is a closed Kotlin enum with exactly these 9 members, so no
 * other value can ever be constructed, passed, or persisted through this type anywhere in the
 * codebase. There is no `fromSeconds(Int)` factory that could throw/fail at runtime for an
 * invalid value — the type itself makes an invalid value unrepresentable.
 */
enum class SamplingInterval(val seconds: Int) {
    THREE_SECONDS(3),
    FIVE_SECONDS(5),
    TEN_SECONDS(10),
    FIFTEEN_SECONDS(15),
    THIRTY_SECONDS(30),
    FORTY_FIVE_SECONDS(45),
    SIXTY_SECONDS(60),
    ONE_TWENTY_SECONDS(120),
    ONE_EIGHTY_SECONDS(180),
    ;

    companion object {
        /** The 9 allowed values, in ascending order — the exact set required by RF-01/UI-01. */
        val ALLOWED_SECONDS: Set<Int> = entries.map { it.seconds }.toSet()
    }
}
