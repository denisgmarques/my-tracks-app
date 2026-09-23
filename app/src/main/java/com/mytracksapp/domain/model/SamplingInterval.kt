package com.mytracksapp.domain.model

/**
 * The sampling interval a user may select when starting a new tracking session (RF-01, UI-01).
 *
 * RF-01's original AC required that "qualquer valor fora do conjunto [3,5,10,15,30,45,60,120,180]
 * é rejeitado e nenhuma sessão é criada". A later follow-up phase (post-prototype developer
 * feedback) added a 10th value, `ONE_SECOND`, for users who want the finest-grained sampling
 * available — the set is now [1,3,5,10,15,30,45,60,120,180]. Either way, this is satisfied
 * *structurally*, not by runtime validation: [SamplingInterval] is a closed Kotlin enum with
 * exactly these 10 members, so no other value can ever be constructed, passed, or persisted
 * through this type anywhere in the codebase. There is no `fromSeconds(Int)` factory that could
 * throw/fail at runtime for an invalid value — the type itself makes an invalid value
 * unrepresentable.
 */
enum class SamplingInterval(val seconds: Int) {
    ONE_SECOND(1),
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
        /** The 10 allowed values — the exact set required by RF-01/UI-01 plus the `ONE_SECOND` follow-up. */
        val ALLOWED_SECONDS: Set<Int> = entries.map { it.seconds }.toSet()
    }
}
