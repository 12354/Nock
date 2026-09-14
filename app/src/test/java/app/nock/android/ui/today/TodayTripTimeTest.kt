package app.nock.android.ui.today

import androidx.lifecycle.ViewModelStore
import app.nock.android.data.NockRepository
import app.nock.android.data.dao.ActiveEscalationDao
import app.nock.android.data.dao.CalendarTripDao
import app.nock.android.data.entity.CalendarTripEntity
import app.nock.android.domain.escalation.activeEntity
import app.nock.android.domain.escalation.group
import app.nock.android.domain.escalation.reminder
import app.nock.android.domain.model.Schedule
import app.nock.android.domain.trip.TripChain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TodayTripTimeTest {
    private val minute = 60_000L

    @Test fun listReactsToTripBufferEditsAndKeepsDepartureStored() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val leaveBy = System.currentTimeMillis() + 120 * minute
            val r = reminder(schedule = Schedule.OneShot(leaveBy), nextFireAt = leaveBy)
            val trip = CalendarTripEntity(
                reminderId = r.id, calendarId = 1, eventId = 1,
                eventStartMs = leaveBy + 23 * minute, title = "Dentist", location = "Dentist address",
                originAddress = null, travelMode = "car", bufferMs = 30 * minute,
                originLat = null, originLon = null, destLat = null, destLon = null,
                lastTravelMs = 23 * minute, lastComputedAtMs = null,
            )
            val trips = MutableStateFlow(listOf(trip))
            val repo = mockk<NockRepository>(relaxed = true)
            val activeDao = mockk<ActiveEscalationDao>()
            val tripDao = mockk<CalendarTripDao>()
            every { repo.observeReminders() } returns flowOf(listOf(r))
            every { repo.observeGroups() } returns flowOf(listOf(group().copy(seedKey = "trips")))
            every { activeDao.observeAll() } returns flowOf(emptyList())
            every { tripDao.observeAll() } returns trips
            val vm = TodayViewModel(repo, mockk(relaxed = true), mockk(relaxed = true), activeDao,
                mockk(relaxed = true), mockk(relaxed = true), tripDao)
            store.put("today", vm)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.items.collect() }
            runCurrent()
            assertEquals(leaveBy - 30 * minute, vm.items.value.single().displayTimeMs)

            trips.value = listOf(trip.copy(bufferMs = 45 * minute))
            runCurrent()
            assertEquals(leaveBy - 45 * minute, vm.items.value.single().displayTimeMs)
            assertEquals(leaveBy, vm.items.value.single().reminder.nextFireAt)
            assertFalse(vm.items.value.single().isActive)

            // An ordinary reminder still displays its own trigger time.
            trips.value = emptyList()
            runCurrent()
            assertEquals(leaveBy, vm.items.value.single().displayTimeMs)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun ringingWarningIsActiveBeforeDeparture() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val leaveBy = System.currentTimeMillis() + 20 * minute
            val r = reminder(schedule = Schedule.OneShot(leaveBy), nextFireAt = leaveBy)
            val chain = TripChain.build(30 * minute, 10 * minute)
            val trip = CalendarTripEntity(
                reminderId = r.id, calendarId = 1, eventId = 1,
                eventStartMs = leaveBy + 23 * minute, title = "Dentist", location = "Dentist address",
                originAddress = null, travelMode = "car", bufferMs = 30 * minute,
                originLat = null, originLon = null, destLat = null, destLon = null,
                lastTravelMs = 23 * minute, lastComputedAtMs = null,
            )
            val repo = mockk<NockRepository>(relaxed = true)
            val activeDao = mockk<ActiveEscalationDao>()
            val tripDao = mockk<CalendarTripDao>()
            every { repo.observeReminders() } returns flowOf(listOf(r))
            every { repo.observeGroups() } returns flowOf(listOf(group().copy(seedKey = "trips")))
            every { tripDao.observeAll() } returns flowOf(listOf(trip))
            every { activeDao.observeAll() } returns flowOf(listOf(activeEntity(
                startedAtMs = leaveBy, nextStageIndex = chain.lastIndex,
                nextFireAtMs = leaveBy - 20 * minute, chain = chain,
            )))
            val vm = TodayViewModel(repo, mockk(relaxed = true), mockk(relaxed = true), activeDao,
                mockk(relaxed = true), mockk(relaxed = true), tripDao)
            store.put("today", vm)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.items.collect() }
            runCurrent()
            assertTrue(vm.items.value.single().isActive)
            assertEquals(leaveBy - 30 * minute, vm.items.value.single().displayTimeMs)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
