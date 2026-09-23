package com.mytracksapp.domain.model

/**
 * User-configurable GPS precision/power tradeoff (RF-04, RF-05). Mapped by T07's
 * `GpsPrecision.toLocationRequestPriority()` to the corresponding `Priority` constant used to
 * build the `LocationRequest` for a new tracking session.
 */
enum class GpsPrecision {
    HIGH_ACCURACY,
    BALANCED,
}
