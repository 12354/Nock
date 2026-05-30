package app.nock.android.domain.time

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Injectable wall-clock seam so time-sensitive logic is testable.
 *
 * Production uses the default implementation backed by the device clock; tests
 * subclass this with a deterministic, advanceable fake. Constructor-injectable,
 * so Hilt wires it automatically without a module.
 */
@Singleton
open class TimeSource @Inject constructor() {
    open fun nowMs(): Long = System.currentTimeMillis()
}
