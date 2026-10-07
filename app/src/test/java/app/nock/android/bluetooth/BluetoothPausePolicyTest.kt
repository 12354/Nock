package app.nock.android.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothPausePolicyTest {

    private val car = "AA:BB:CC:DD:EE:01"
    private val headphones = "AA:BB:CC:DD:EE:02"

    @Test fun nothing_selected_means_nothing_pauses() {
        val result = BluetoothPausePolicy.connectedSelected(emptySet(), setOf(car)) { true }
        assertEquals(emptySet<String>(), result)
    }

    @Test fun live_state_wins_over_tracked_state() {
        // Tracked says connected, but the platform reports it isn't (stale track).
        val stale = BluetoothPausePolicy.connectedSelected(setOf(car), setOf(car)) { false }
        assertEquals(emptySet<String>(), stale)
        // Tracked missed the connect (e.g. app installed while connected).
        val missed = BluetoothPausePolicy.connectedSelected(setOf(car), emptySet()) { true }
        assertEquals(setOf(car), missed)
    }

    @Test fun falls_back_to_tracked_state_when_live_is_unknown() {
        val result = BluetoothPausePolicy.connectedSelected(setOf(car, headphones), setOf(car)) { null }
        assertEquals(setOf(car), result)
    }

    @Test fun unselected_connected_devices_do_not_pause() {
        val result = BluetoothPausePolicy.connectedSelected(setOf(car), setOf(headphones)) { it == headphones }
        assertEquals(emptySet<String>(), result)
    }
}
