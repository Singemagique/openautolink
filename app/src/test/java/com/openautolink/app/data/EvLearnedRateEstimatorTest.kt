package com.openautolink.app.data

import com.openautolink.app.transport.ControlMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [EvLearnedRateEstimator.applyTick] — the pure tick math.
 * Avoids DataStore / coroutines / context entirely.
 */
class EvLearnedRateEstimatorTest {

    private fun tick(
        prev: EvLearnedRateEstimator.VehicleState? = null,
        batteryWh: Int? = null,
        speedKmh: Float? = null,
        evChargeRateW: Float? = null,
        nowMs: Long,
    ): Pair<EvLearnedRateEstimator.VehicleState, String> {
        val s = prev ?: EvLearnedRateEstimator.VehicleState()
        val vd = ControlMessage.VehicleData(
            evBatteryLevelWh = batteryWh?.toFloat(),
            speedKmh = speedKmh,
            evChargeRateW = evChargeRateW,
            carMake = "Test",
            carModel = "Model",
            carYear = "2024",
        )
        val status = EvLearnedRateEstimator.applyTick(s, vd, nowMs)
        return s to status
    }

    @Test
    fun firstTickIsAlwaysInit() {
        val (s, status) = tick(batteryWh = 50_000, speedKmh = 60f, nowMs = 1_000)
        assertEquals("init", status)
        assertEquals(0f, s.whPerKm, 0.001f)
        assertEquals(0f, s.sampleKm, 0.001f)
    }

    @Test
    fun consumptionTickAcceptedAndPopulatesEma() {
        // First tick seeds prev battery.
        val (s, _) = tick(batteryWh = 50_000, speedKmh = 60f, nowMs = 1_000)
        // Second tick: 10 minutes later → 10 km traveled at 60 km/h, 2000 Wh
        // consumed → 200 Wh/km. (Stays under the 15-min MAX_TICK_GAP_MS.)
        val (s2, status) = tick(
            prev = s,
            batteryWh = 50_000 - 2_000,
            speedKmh = 60f,
            nowMs = 1_000 + 10 * 60 * 1000,
        )
        assertTrue("expected ok status, got '$status'", status.startsWith("ok:"))
        // EMA seeded by first sample → exactly the instantaneous value.
        assertEquals(200f, s2.whPerKm, 1f)
        assertEquals(10f, s2.sampleKm, 0.5f)
    }

    @Test
    fun chargingTickIsSkipped() {
        val (s, _) = tick(batteryWh = 50_000, speedKmh = 60f, nowMs = 1_000)
        val (s2, status) = tick(
            prev = s,
            batteryWh = 50_500,
            speedKmh = 60f,
            evChargeRateW = 50_000f,  // charging
            nowMs = 1_000 + 60_000,
        )
        assertEquals("skip:charging", status)
        assertEquals(0f, s2.whPerKm, 0.001f)
        assertEquals(0f, s2.sampleKm, 0.001f)
    }

    @Test
    fun regenTickIsSkipped() {
        // First tick at low battery, second tick higher battery → net regen.
        val (s, _) = tick(batteryWh = 30_000, speedKmh = 50f, nowMs = 1_000)
        val (s2, status) = tick(
            prev = s,
            batteryWh = 30_500,            // gained 500 Wh while moving
            speedKmh = 50f,
            nowMs = 1_000 + 60_000,
        )
        assertTrue("expected regen-skip, got '$status'", status.startsWith("skip:regen"))
        assertEquals(0f, s2.whPerKm, 0.001f)
        assertEquals(0f, s2.sampleKm, 0.001f)
    }

    @Test
    fun stationaryTickIsSkipped() {
        val (s, _) = tick(batteryWh = 50_000, speedKmh = 0f, nowMs = 1_000)
        val (s2, status) = tick(
            prev = s,
            batteryWh = 49_950,
            speedKmh = 0f,
            nowMs = 1_000 + 60_000,
        )
        assertTrue("expected stationary-skip, got '$status'", status.startsWith("skip:accumKm"))
        assertEquals(0f, s2.whPerKm, 0.001f)
    }

    @Test
    fun longGapResetsTheTick() {
        val (s, _) = tick(batteryWh = 50_000, speedKmh = 60f, nowMs = 1_000)
        val gapMs = 30 * 60 * 1000L  // 30 min — exceeds MAX_TICK_GAP_MS (15 min)
        val (_, status) = tick(
            prev = s,
            batteryWh = 30_000,
            speedKmh = 60f,
            nowMs = 1_000 + gapMs,
        )
        assertTrue("expected gap skip, got '$status'", status.startsWith("skip:gap"))
    }

    @Test
    fun outliersAreRejected() {
        val (s, _) = tick(batteryWh = 50_000, speedKmh = 60f, nowMs = 1_000)
        // 1 minute, 1 km traveled, 1000 Wh consumed → 1000 Wh/km — implausible.
        val (s2, status) = tick(
            prev = s,
            batteryWh = 50_000 - 1_000,
            speedKmh = 60f,
            nowMs = 1_000 + 60_000,
        )
        assertTrue("expected outlier skip, got '$status'", status.startsWith("skip:outlier"))
        assertEquals(0f, s2.whPerKm, 0.001f)
    }

    @Test
    fun emaConvergesAcrossMultipleTicks() {
        // Seed.
        var s = EvLearnedRateEstimator.VehicleState()
        var battery = 80_000
        var nowMs = 1_000L
        s.windowStartBatteryWh = battery
        s.lastTickElapsedMs = nowMs

        // Repeatedly consume at 200 Wh/km for 5 km each tick (5 minutes at 60 km/h).
        repeat(5) {
            nowMs += 5 * 60 * 1000  // +5 min
            battery -= 5 * 200      // 5 km × 200 Wh/km
            val vd = ControlMessage.VehicleData(
                evBatteryLevelWh = battery.toFloat(),
                speedKmh = 60f,
                carMake = "T", carModel = "M", carYear = "2024",
            )
            val status = EvLearnedRateEstimator.applyTick(s, vd, nowMs)
            assertTrue(status.startsWith("ok:"))
        }
        assertEquals(200f, s.whPerKm, 5f)
        assertTrue("sample km should be > 1 km", s.sampleKm > 1f)

        // Snapshot built from this state should be considered usable.
        val snap = EvLearnedRateEstimator.Snapshot(
            key = "T|M|2024",
            whPerKm = s.whPerKm,
            sampleKm = s.sampleKm,
        )
        assertTrue("expected usable snapshot", snap.usable)
    }

    @Test
    fun accumulatesAcrossFastSubThresholdTicks() {
        // REL-2 regression test. Production feeds ticks ~every 500 ms. At 90 km/h
        // a single 500 ms tick covers only ~0.0125 km — below the 0.05 km window
        // threshold — so the pre-REL-2 code (which advanced the baseline every
        // tick before the gate) discarded every tick and never learned. The
        // window must now accrue distance across ticks until a sample is accepted.
        val s = EvLearnedRateEstimator.VehicleState()
        val startBattery = 60_000
        val whPerKmTarget = 180f
        val speed = 90f
        var nowMs = 0L
        var cumulativeKm = 0f
        var sawOk = false
        repeat(80) {
            val prevMs = nowMs
            nowMs += 500
            if (prevMs > 0L) cumulativeKm += speed * (500f / 3_600_000f)
            val battery = (startBattery - cumulativeKm * whPerKmTarget).toInt()
            val vd = ControlMessage.VehicleData(
                evBatteryLevelWh = battery.toFloat(),
                speedKmh = speed,
                carMake = "T", carModel = "M", carYear = "2024",
            )
            val status = EvLearnedRateEstimator.applyTick(s, vd, nowMs)
            if (status.startsWith("ok:")) sawOk = true
        }
        assertTrue("expected accepted sample(s) at 500 ms cadence", sawOk)
        assertTrue("expected learned rate near target, got ${s.whPerKm}", s.whPerKm in 120f..260f)
    }

    @Test
    fun missingBatteryIsSkipped() {
        val s = EvLearnedRateEstimator.VehicleState()
        val vd = ControlMessage.VehicleData(
            evBatteryLevelWh = null,
            speedKmh = 60f,
            carMake = "T", carModel = "M", carYear = "2024",
        )
        val status = EvLearnedRateEstimator.applyTick(s, vd, 1_000L)
        assertEquals("skip:noBattery", status)
    }
}
