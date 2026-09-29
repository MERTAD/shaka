package com.shaka.data.client

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Opt-in live verification of the extended [CopernicusGridClient] against the
 * real Copernicus Marine Toolbox.
 *
 * The unit tests fake the CLI, so they can only prove the parser and the
 * subprocess arguments are self-consistent. This suite proves the claims the
 * evidence matrix rests on:
 *
 *  - `uo` + `vo` really do come back from ONE batched 3D call, and the deepest
 *    level per cell is a real deep level, not the first row in the file;
 *  - `bottomT` is in degrees Celsius and not Kelvin (a wrong `celsius` flag
 *    would silently produce a score of ~290);
 *  - `so` is in psu, not the catalogue's `0.001` units string;
 *  - the monthly SSH product resolves to a *complete* month, which is what
 *    makes the derived SSH anomaly a mean difference rather than a partial one;
 *  - a repeat request for the same box and day is served from disk.
 *
 * Not part of the routine suite: every test returns early (passes silently)
 * when `LIVE_PFZ` is unset.
 *
 *     $env:LIVE_PFZ="1"
 *     $env:COPERNICUSMARINE_SERVICE_USERNAME="<user>"
 *     $env:COPERNICUSMARINE_SERVICE_PASSWORD="<pass>"
 *     .\gradlew.bat test --tests "com.shaka.data.client.LiveCopernicusGridTest" --console=plain
 *
 * The numbers are printed to stdout; read them from
 * `build/test-results/test/TEST-com.shaka.data.client.LiveCopernicusGridTest.xml`.
 */
class LiveCopernicusGridTest {

    /** Off the Catalan shelf edge: deep enough for a bedrock value, inside the anfc extent. */
    private val lat = 41.0
    private val lon = 2.2
    private val date = LocalDate.now().minusDays(1)

    /** Counts subprocesses so the cache assertion measures work, not wall time. */
    private class CountingRunner(private val inner: CopernicusSubsetRunner) : CopernicusSubsetRunner {
        var runs = 0
        override fun run(
            command: List<String>,
            env: Map<String, String>,
            outputDir: Path
        ): SubsetRunResult {
            runs++
            return inner.run(command, env, outputDir)
        }
    }

    private fun live(): Boolean = System.getenv("LIVE_PFZ") == "1"

    /** Isolated cache so these runs never depend on (or pollute) the real one. */
    private fun client(runner: CopernicusSubsetRunner) = CopernicusGridClient(
        runner = runner,
        cache = CopernicusGridCache(
            dir = java.nio.file.Files.createTempDirectory("live-copernicus-cache")
        )
    )

    @Test
    fun `batched bottom currents resolve from one call at a real deep level`() {
        if (!live()) return
        val counter = CountingRunner(CopernicusSubsetProcess())
        val grids = runBlocking {
            client(counter).fetchGrids(
                listOf(CopernicusField.CURRENT_U, CopernicusField.CURRENT_V),
                lat, lon, boxDeg = 0.08, date = date
            )
        }

        val u = assertNotNull(grids[CopernicusField.CURRENT_U], "uo did not resolve")
        val v = assertNotNull(grids[CopernicusField.CURRENT_V], "vo did not resolve")
        assertEquals(1, counter.runs, "uo and vo must be one batched subset call")

        val depths = assertNotNull(u.cellDepthM, "a 3D field must record the level it read")
        val resolved = depths.flatMap { it.toList() }.filter { !it.isNaN() }
        assertTrue(resolved.isNotEmpty(), "no cell resolved a level")
        assertTrue(
            resolved.max() > 100.0,
            "deepest resolved level was only ${resolved.max()} m — a 3D file read as a surface would look like this"
        )

        val us = u.values.flatten().filter { !it.isNaN() }
        val vs = v.values.flatten().filter { !it.isNaN() }
        assertTrue(us.isNotEmpty() && vs.isNotEmpty(), "batched file produced no usable cells")
        assertTrue(us.all { it.isFinite() && kotlin.math.abs(it) < 3.0 }, "uo out of range: ${us.min()}..${us.max()}")
        assertTrue(vs.all { it.isFinite() && kotlin.math.abs(it) < 3.0 }, "vo out of range: ${vs.min()}..${vs.max()}")

        // A bottom current of 0 is not evidence; the hake band is < 0.034 m/s and
        // the assertion is that we can actually resolve a number near it.
        val speeds = us.indices.map { i -> Math.hypot(us[i], vs[i]) }
        println(
            "LIVE cur   date=${u.dataDate} cells=${us.size} " +
                "levelM=${resolved.min()}..${resolved.max()} " +
                "speed=${"%.4f".format(speeds.min())}..${"%.4f".format(speeds.max())} m/s"
        )
    }

    @Test
    fun `bottom temperature is in celsius and bottom salinity in psu`() {
        if (!live()) return
        val grids = runBlocking {
            client(CopernicusSubsetProcess()).fetchGrids(
                listOf(CopernicusField.BOTTOM_TEMP, CopernicusField.BOTTOM_SALINITY),
                lat, lon, boxDeg = 0.08, date = date
            )
        }

        val temp = assertNotNull(grids[CopernicusField.BOTTOM_TEMP], "bottomT did not resolve")
        val sal = assertNotNull(grids[CopernicusField.BOTTOM_SALINITY], "so did not resolve")

        val temps = temp.values.flatten().filter { !it.isNaN() }
        assertTrue(temps.isNotEmpty(), "no bottom temperature resolved")
        assertTrue(
            temps.all { it in 2.0..30.0 },
            "bottomT is not in degrees Celsius (${temps.min()}..${temps.max()}) — a Kelvin value would be ~280"
        )

        val sals = sal.values.flatten().filter { !it.isNaN() }
        assertTrue(sals.isNotEmpty(), "no bottom salinity resolved")
        assertTrue(
            sals.all { it in 34.0..40.0 },
            "so is not in psu (${sals.min()}..${sals.max()}) — the catalogue's 0.001 scale is not in the CSV"
        )
        assertNotNull(sal.cellDepthM, "so is a 3D field and must record its level")

        println(
            "LIVE btm   date=${temp.dataDate} " +
                "bottomT=${"%.2f".format(temps.min())}..${"%.2f".format(temps.max())} C  " +
                "so=${"%.3f".format(sals.min())}..${"%.3f".format(sals.max())} psu"
        )
    }

    @Test
    fun `mld and daily ssh resolve and the monthly mean lands on a complete month`() {
        if (!live()) return
        val grids = runBlocking {
            client(CopernicusSubsetProcess()).fetchGrids(
                listOf(CopernicusField.MLD, CopernicusField.SSH, CopernicusField.SSH_MONTHLY),
                lat, lon, boxDeg = 0.08, date = date
            )
        }

        val mld = assertNotNull(grids[CopernicusField.MLD], "mlotst did not resolve")
        val ssh = assertNotNull(grids[CopernicusField.SSH], "daily zos did not resolve")
        val monthly = assertNotNull(grids[CopernicusField.SSH_MONTHLY], "monthly zos did not resolve")

        val mlds = mld.values.flatten().filter { !it.isNaN() }
        assertTrue(mlds.all { it in 0.0..2000.0 }, "mlotst out of range: ${mlds.min()}..${mlds.max()}")
        assertNull(mld.cellDepthM, "mlotst is a 2D product and must report no level")

        val expectedMonth = YearMonth.from(date).minusMonths(1).atDay(1)
        assertEquals(expectedMonth, monthly.dataDate, "monthly SSH must be the last complete month")
        assertEquals(1, monthly.dataDate.dayOfMonth, "a monthly mean is stamped on the 1st")

        // This is the derived SSH anomaly bluefin spawning is scored on. Both
        // terms come from the same 4.2 km product, so the lattices must match
        // cell for cell; if they ever do not, the difference is meaningless and
        // the test should say so rather than quietly pairing neighbours.
        assertEquals(ssh.lats, monthly.lats, "daily and monthly SSH lattices differ in latitude")
        assertEquals(ssh.lons, monthly.lons, "daily and monthly SSH lattices differ in longitude")
        val anomaly = ssh.values.indices.flatMap { i ->
            ssh.values[i].indices.mapNotNull { j ->
                val d = ssh.values[i][j]
                val m = monthly.values[i][j]
                if (d.isNaN() || m.isNaN()) null else d - m
            }
        }
        assertTrue(anomaly.isNotEmpty(), "the daily and monthly SSH fields did not overlap")

        println(
            "LIVE ssh   date=${ssh.dataDate} mld=${"%.1f".format(mlds.min())}..${"%.1f".format(mlds.max())} m  " +
                "monthly=${monthly.dataDate}  derivedSSHa=${"%.3f".format(anomaly.min())}.." +
                "${"%.3f".format(anomaly.max())} m"
        )
    }

    @Test
    fun `a repeat request for the same box and day is served from disk`() {
        if (!live()) return
        val counter = CountingRunner(CopernicusSubsetProcess())
        val c = client(counter)

        val first = runBlocking {
            c.fetchGrid(CopernicusField.BOTTOM_TEMP, lat, lon, boxDeg = 0.08, date = date)
        }
        assertNotNull(first, "first request must resolve before the cache can be tested")
        assertEquals(1, counter.runs)

        val second = runBlocking {
            c.fetchGrid(CopernicusField.BOTTOM_TEMP, lat, lon, boxDeg = 0.08, date = date)
        }
        assertNotNull(second)
        assertEquals(first.values, second.values, "a cached grid must be the same grid")
        assertEquals(
            1, counter.runs,
            "the second identical request must not re-run the CLI (each run is 9-20 s)"
        )
    }

    @Test
    fun `a pre-archive date is refused without touching the network`() {
        if (!live()) return
        val counter = CountingRunner(CopernicusSubsetProcess())
        val grid = runBlocking {
            client(counter).fetchGrid(
                CopernicusField.BOTTOM_TEMP, lat, lon, boxDeg = 0.08,
                date = LocalDate.of(2024, 1, 5)
            )
        }
        assertNull(grid, "a date before the physics archive must be missing, not substituted")
        assertEquals(0, counter.runs)
    }
}
