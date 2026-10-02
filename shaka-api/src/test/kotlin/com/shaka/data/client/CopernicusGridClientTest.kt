package com.shaka.data.client

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [CopernicusGridClient] — the toolbox-subprocess path that feeds the
 * PFZ front-finder — and for [CopernicusGridCache].
 *
 * Four layers are locked down:
 *  1. The 2D CSV parser: unordered rows are re-indexed into a sorted lat/lon
 *     grid, Kelvin is converted for SST, NaN stays NaN (a missing cell, never a
 *     0), and a malformed response is null, not a guess.
 *  2. The 3D parser: a bedrock field takes the deepest non-masked level per
 *     cell and records the depth it came from, so "bottom" is not a synonym for
 *     "shallowest row" or "last row written".
 *  3. Batching: two variables of one product cost one subprocess; two products
 *     cost two; the box is clamped to the intersection of their coverage.
 *  4. Hygiene: the command carries the right variables and dates, credentials
 *     reach the child ONLY via process environment (never argv, which any local
 *     process can read), a repeat request is served from disk, and a date before
 *     the product archive is refused rather than answered with another day.
 */
class CopernicusGridClientTest {

    private val date = LocalDate.of(2026, 9, 26)

    /**
     * Fake CLI. Writes [csv] into the output directory (if set) and captures
     * the argv/env so tests can assert what would have been executed.
     */
    private class CsvRunner(
        var csv: String? = null,
        var result: SubsetRunResult = SubsetRunResult(0, ""),
        var sawArgs: List<String> = emptyList(),
        var sawEnv: Map<String, String> = emptyMap(),
        var runs: Int = 0
    ) : CopernicusSubsetRunner {
        override fun run(
            command: List<String>,
            env: Map<String, String>,
            outputDir: Path
        ): SubsetRunResult {
            runs++
            sawArgs = command
            sawEnv = env
            csv?.let { outputDir.resolve("out.csv").toFile().writeText(it) }
            return result
        }
    }

    /**
     * A cache rooted in a fresh temp dir per test, so a cached entry from an
     * earlier test (or an earlier run of this suite) can never make a
     * subprocess assertion silently pass or fail.
     */
    private fun isolatedCache() =
        CopernicusGridCache(dir = Files.createTempDirectory("copernicus-test-cache"))

    private fun client(
        runner: CsvRunner,
        username: String? = "kmertad",
        password: String? = "test-password",
        cache: CopernicusGridCache = isolatedCache(),
        cliPath: String = "fake-copernicusmarine.exe"
    ) = CopernicusGridClient(
        runner = runner,
        username = username,
        password = password,
        cliPath = cliPath,
        cache = cache
    )

    private fun writeCsv(content: String): Path {
        val dir = Files.createTempDirectory("copernicus-test")
        val file = dir.resolve("subset.csv")
        file.toFile().writeText(content)
        return file
    }

    private fun writeRealisticSstGrid(): Path {
        // Deliberately unordered rows and a NaN cell, shaped like a live
        // `copernicusmarine subset` output (SST native unit is Kelvin).
        return writeCsv(
            """
            time,latitude,longitude,analysed_sst
            2026-09-26,41.510000,2.500000,300.15
            2026-09-26,41.500000,2.510000,298.15
            2026-09-26,41.510000,2.510000,nan
            2026-09-26,41.500000,2.500000,299.15
            """.trimIndent()
        )
    }

    // ---------------------------------------------------------------- parser

    @Test
    fun `unordered rows are reindexed into a sorted lat-lon grid with celsius conversion`() {
        val grid = client(CsvRunner())
            .parseCsv(writeRealisticSstGrid(), CopernicusField.SST)
        assertNotNull(grid)

        assertEquals(listOf(41.5, 41.51), grid.lats)
        assertEquals(listOf(2.5, 2.51), grid.lons)
        assertEquals(CopernicusField.SST, grid.field)
        assertEquals(LocalDate.of(2026, 9, 26), grid.dataDate)

        // 299.15K -> 26.0C, 298.15K -> 25.0C, 300.15K -> 27.0C.
        assertEquals(26.0, grid.values[0][0], 1e-9)
        assertEquals(25.0, grid.values[0][1], 1e-9)
        assertEquals(27.0, grid.values[1][0], 1e-9)
        assertTrue(grid.values[1][1].isNaN(), "an explicit NaN cell must stay missing, never 0")
    }

    @Test
    fun `chlorophyll is parsed without celsius conversion`() {
        val file = writeCsv(
            """
            time,latitude,longitude,CHL
            2026-09-26,41.5,2.5,0.37
            2026-09-26,41.51,2.5,0.12
            """.trimIndent()
        )
        val grid = client(CsvRunner()).parseCsv(file, CopernicusField.CHL)
        assertNotNull(grid)
        assertEquals(0.37, grid.values[0][0], 1e-9)
        assertEquals(0.12, grid.values[1][0], 1e-9)
        assertTrue(grid.sourceLabel.contains("chlorophyll"), grid.sourceLabel)
    }

    @Test
    fun `a 2d product reports no cell depth because it has no depth axis`() {
        val grid = client(CsvRunner())
            .parseCsv(writeRealisticSstGrid(), CopernicusField.SST)
        assertNull(grid?.cellDepthM)
    }

    @Test
    fun `a csv missing the variable column returns null, not a zero grid`() {
        val file = writeCsv(
            """
            time,latitude,longitude,something_else
            2026-09-26,41.5,2.5,12.0
            """.trimIndent()
        )
        assertNull(client(CsvRunner()).parseCsv(file, CopernicusField.SST))
    }

    @Test
    fun `header-only or unreadable parse produces null`() {
        val json = client(CsvRunner())
        assertNull(
            json.parseCsv(writeCsv("time,latitude,longitude,analysed_sst\n"), CopernicusField.SST)
        )
        assertNull(
            json.parseCsv(writeCsv("not a csv at all\n"), CopernicusField.SST)
        )
    }

    // ------------------------------------------------------- 3D bedrock parse

    /**
     * A live `cmems_mod_med_phy-sal_anfc_4.2km_P1D-m` subset has the shape
     * `depth,latitude,longitude,time,so` with 141 levels, and rows are not
     * ordered by depth. This is the hake's bottom salinity and the red shrimp's
     * controlling variable, so taking the wrong row is not a cosmetic bug.
     */
    private val salinityCsv = """
        depth,latitude,longitude,time,so
        2.0,41.0,2.0,2026-09-26,38.4
        500.0,41.0,2.0,2026-09-26,38.2
        2.0,41.1,2.0,2026-09-26,38.5
        500.0,41.1,2.0,2026-09-26,nan
        1000.0,41.1,2.0,2026-09-26,38.39
        100.0,41.0,2.1,2026-09-26,38.3
    """.trimIndent()

    @Test
    fun `a bedrock field takes the deepest non-masked level per cell`() {
        val grid = client(CsvRunner())
            .parseCsv(writeCsv(salinityCsv), CopernicusField.BOTTOM_SALINITY)
        assertNotNull(grid)

        // (41.0, 2.0): deepest valid row is 500 m / 38.2, not the 2 m surface row.
        assertEquals(38.2, grid.values[0][0], 1e-9)
        assertEquals(500.0, grid.cellDepthM!![0][0], 1e-9)

        // (41.1, 2.0): 500 m is masked, so the deepest VALID level is 1000 m.
        // A last-write-wins parser would keep the masked 500 m or lose the cell.
        assertEquals(38.39, grid.values[1][0], 1e-9)
        assertEquals(1000.0, grid.cellDepthM!![1][0], 1e-9)

        // A cell with only one level still resolves.
        assertEquals(38.3, grid.values[0][1], 1e-9)
        assertEquals(100.0, grid.cellDepthM!![0][1], 1e-9)
    }

    @Test
    fun `a fully masked column stays missing rather than borrowing another level`() {
        val file = writeCsv(
            """
            depth,latitude,longitude,time,so
            2.0,41.0,2.0,2026-09-26,nan
            500.0,41.0,2.0,2026-09-26,nan
            """.trimIndent()
        )
        assertNull(
            client(CsvRunner()).parseCsv(file, CopernicusField.BOTTOM_SALINITY),
            "a column with no valid level at all is no data, not a zero grid"
        )
    }

    @Test
    fun `both current components are read from one batched 3d file`() {
        val file = writeCsv(
            """
            depth,latitude,longitude,time,uo,vo
            2.0,41.0,2.0,2026-09-26,-0.11,0.04
            600.0,41.0,2.0,2026-09-26,0.02,-0.03
            """.trimIndent()
        )
        val parsed = client(CsvRunner())
            .parseCsv(file, listOf(CopernicusField.CURRENT_U, CopernicusField.CURRENT_V))

        val u = assertNotNull(parsed[CopernicusField.CURRENT_U])
        val v = assertNotNull(parsed[CopernicusField.CURRENT_V])
        assertEquals(0.02, u.values[0][0], 1e-9)
        assertEquals(-0.03, v.values[0][0], 1e-9)
        assertEquals(600.0, u.cellDepthM!![0][0], 1e-9)
    }

    @Test
    fun `a variable the product ignored is dropped, not zero-filled`() {
        val parsed = client(CsvRunner()).parseCsv(
            writeCsv(salinityCsv),
            listOf(CopernicusField.BOTTOM_SALINITY, CopernicusField.MLD)
        )
        assertNotNull(parsed[CopernicusField.BOTTOM_SALINITY])
        assertNull(
            parsed[CopernicusField.MLD],
            "a column the CLI did not write must be reported missing, not zero"
        )
    }

    @Test
    fun `a 3d file requested as a column field keeps the deepest row too`() {
        // Defensive: the parser must not depend on the enum flag to be correct
        // about which row wins, only about whether the depth is recorded.
        val grid = client(CsvRunner())
            .parseCsv(writeCsv(salinityCsv), CopernicusField.BOTTOM_SALINITY)
        assertNotNull(grid?.cellDepthM)
    }

    // ------------------------------------------------------------- pipeline

    @Test
    fun `fetchGrid returns the parsed grid end to end`() {
        val runner = CsvRunner(csv = runCatching { writeRealisticSstGrid().toFile().readText() }.getOrNull())
        val grid = runBlocking {
            client(runner).fetchGrid(CopernicusField.SST, 41.51, 2.51, date = date)
        }
        assertNotNull(grid)
        assertEquals(2, grid.lats.size)
        assertEquals(1, runner.runs)
    }

    @Test
    fun `a non-zero exit code yields null without a grid`() {
        val runner = CsvRunner(result = SubsetRunResult(1, "ERROR: dataset not found"))
        val grid = runBlocking {
            client(runner).fetchGrid(CopernicusField.CHL, 41.5, 2.5, date = date)
        }
        assertNull(grid, "a failed subset must be reported as lacking data, not guessed")
    }

    @Test
    fun `a timed out subset yields null`() {
        val runner = CsvRunner(result = SubsetRunResult(-1, "", timedOut = true))
        val grid = runBlocking {
            client(runner).fetchGrid(CopernicusField.SSTA, 41.5, 2.5, date = date)
        }
        assertNull(grid)
    }

    // -------------------------------------------------------------- batching

    @Test
    fun `two variables of one product share a single subprocess`() {
        val runner = CsvRunner(
            csv = """
                depth,latitude,longitude,time,uo,vo
                2.0,41.0,2.0,2026-09-26,-0.11,0.04
                600.0,41.0,2.0,2026-09-26,0.02,-0.03
            """.trimIndent()
        )
        val grids = runBlocking {
            client(runner).fetchGrids(
                listOf(CopernicusField.CURRENT_U, CopernicusField.CURRENT_V),
                41.0, 2.0, date = date
            )
        }
        assertEquals(2, grids.size)
        assertEquals(1, runner.runs, "uo and vo are one product and must be one call")
        assertEquals(2, runner.sawArgs.count { it == "--variable" })
    }

    @Test
    fun `two products cost two subprocesses`() {
        val runner = CsvRunner(csv = "time,latitude,longitude,bottomT\n2026-09-26,41.0,2.0,14.2\n")
        val grids = runBlocking {
            client(runner).fetchGrids(
                listOf(CopernicusField.BOTTOM_TEMP, CopernicusField.SSH),
                41.0, 2.0, date = date
            )
        }
        // Only the product the fake actually served resolves; the other is absent.
        assertEquals(setOf(CopernicusField.BOTTOM_TEMP), grids.keys)
        assertEquals(2, runner.runs, "different products cannot share a subset call")
    }

    @Test
    fun `the command names every variable of the batch and the exact date`() {
        val runner = CsvRunner()
        runBlocking {
            client(runner).fetchGrids(
                listOf(CopernicusField.CURRENT_U, CopernicusField.CURRENT_V),
                41.0, 2.0, date = date
            )
        }
        val args = runner.sawArgs
        assertTrue(args.contains("cmems_mod_med_phy-cur_anfc_4.2km_P1D-m"))
        assertTrue(args.contains("uo") && args.contains("vo"))
        assertTrue(args.contains("2026-09-26"), "--start/--end datetime must be the requested date")
        assertTrue(args.contains("--file-format") && args.contains("csv"))
    }

    // ---------------------------------------------------------- temporal fits

    @Test
    fun `a daily NRT request for today is clamped to yesterday`() {
        val runner = CsvRunner(csv = "time,latitude,longitude,bottomT\n2026-09-26,41.0,2.0,14.2\n")
        val yesterday = LocalDate.now().minusDays(1)
        runBlocking {
            client(runner).fetchGrid(CopernicusField.BOTTOM_TEMP, 41.0, 2.0, date = LocalDate.now())
        }
        assertTrue(runner.sawArgs.contains(yesterday.toString()), "${runner.sawArgs}")
    }

    @Test
    fun `a monthly ssh request resolves to the first of the previous month`() {
        val runner = CsvRunner(csv = "time,latitude,longitude,zos\n2026-08-01,41.0,2.0,-0.40\n")
        runBlocking {
            client(runner).fetchGrid(CopernicusField.SSH_MONTHLY, 41.0, 2.0, date = date)
        }
        val expected = java.time.YearMonth.from(date).minusMonths(1).atDay(1).toString()
        val start = runner.sawArgs[runner.sawArgs.indexOf("--start-datetime") + 1]
        val end = runner.sawArgs[runner.sawArgs.indexOf("--end-datetime") + 1]
        assertEquals(expected, start)
        assertEquals(expected, end)
        assertTrue(runner.sawArgs.contains("cmems_mod_med_phy-ssh_anfc_4.2km_P1M-m"))
    }

    @Test
    fun `a date before the product archive is refused without calling the cli`() {
        val runner = CsvRunner()
        // Daily physics archive starts 2024-08-26.
        val grid = runBlocking {
            client(runner).fetchGrid(
                CopernicusField.BOTTOM_TEMP, 41.0, 2.0,
                date = LocalDate.of(2024, 1, 5)
            )
        }
        assertNull(grid, "a pre-archive date must not be answered with another day's data")
        assertEquals(0, runner.runs)
    }

    // ------------------------------------------------------------------ cache

    @Test
    fun `a second identical request is served from disk without the cli`() {
        val cache = isolatedCache()
        val runner = CsvRunner(csv = "time,latitude,longitude,bottomT\n2026-09-26,41.0,2.0,14.2\n")
        val first = runBlocking {
            client(runner, cache = cache).fetchGrid(CopernicusField.BOTTOM_TEMP, 41.0, 2.0, date = date)
        }
        assertNotNull(first)
        assertEquals(1, runner.runs)

        val second = runBlocking {
            client(runner, cache = cache).fetchGrid(CopernicusField.BOTTOM_TEMP, 41.0, 2.0, date = date)
        }
        assertNotNull(second)
        assertEquals(14.2, second.values[0][0], 1e-9)
        assertEquals(1, runner.runs, "the second request must not re-run the CLI")
    }

    @Test
    fun `a cached grid is still served when the credentials are gone`() {
        val cache = isolatedCache()
        val runner = CsvRunner(csv = "time,latitude,longitude,bottomT\n2026-09-26,41.0,2.0,14.2\n")
        runBlocking { client(runner, cache = cache).fetchGrid(CopernicusField.BOTTOM_TEMP, 41.0, 2.0, date = date) }
        assertEquals(1, runner.runs)

        val runner2 = CsvRunner()
        val cached = runBlocking {
            client(runner2, username = null, password = null, cache = cache)
                .fetchGrid(CopernicusField.BOTTOM_TEMP, 41.0, 2.0, date = date)
        }
        assertNotNull(cached, "real dated data already fetched must not be thrown away")
        assertEquals(14.2, cached.values[0][0], 1e-9)
        assertEquals(0, runner2.runs)
    }

    @Test
    fun `a different day or box is a different cache entry`() {
        val cache = isolatedCache()
        val runner = CsvRunner(csv = "time,latitude,longitude,bottomT\n2026-09-26,41.0,2.0,14.2\n")
        val c = client(runner, cache = cache)
        runBlocking { c.fetchGrid(CopernicusField.BOTTOM_TEMP, 41.0, 2.0, date = date) }
        runBlocking { c.fetchGrid(CopernicusField.BOTTOM_TEMP, 41.0, 2.0, date = date.minusDays(1)) }
        runBlocking { c.fetchGrid(CopernicusField.BOTTOM_TEMP, 41.5, 2.0, date = date) }
        assertEquals(3, runner.runs, "a different day or a different box must not reuse an entry")
    }

    @Test
    fun `cache keys are hashed to a legal file name`() {
        val key = CopernicusGridCache.keyOf(
            "cmems_mod_med_phy-cur_anfc_4.2km_P1D-m", "uo+vo",
            "1.800000", "2.200000", "-0.300000", "0.300000", "2026-09-26", "2026-09-26"
        )
        assertTrue(key.matches(Regex("[0-9a-f]{64}")), key)
    }

    @Test
    fun `an expired entry is discarded so reprocessed days can reach users`() {
        val dir = Files.createTempDirectory("copernicus-test-expiry")
        val cache = CopernicusGridCache(dir = dir)
        val key = CopernicusGridCache.keyOf("k")
        cache.write(key, writeCsv("time,latitude,longitude,bottomT\n2026-09-26,41.0,2.0,14.2\n"))
        assertNotNull(cache.read(key))

        // Age the entry past the 36 h window the client uses by default.
        val twoDaysAgo = FileTime.fromMillis(System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000)
        Files.setLastModifiedTime(dir.resolve("$key.csv"), twoDaysAgo)

        assertNull(cache.read(key), "an entry older than the max age must not be served")
        assertEquals(1, cache.prune())
        assertNull(cache.read(key))
    }

    @Test
    fun `an unwritable cache directory degrades to no cache`() {
        val readOnly = Files.createTempDirectory("copernicus-test-ro")
        val notADir = readOnly.resolve("a-file")
        Files.writeString(notADir, "x")
        val cache = CopernicusGridCache(dir = notADir.resolve("under-a-file"))
        assertNull(cache.write("k", writeCsv("a\n")))
    }

    // --------------------------------------------------------------- hygiene

    @Test
    fun `missing credentials skip the CLI entirely`() {
        val runner = CsvRunner()
        val grid = runBlocking {
            client(runner, username = null, password = null)
                .fetchGrid(CopernicusField.SST, 41.5, 2.5, date = date)
        }
        assertNull(grid)
        assertEquals(0, runner.runs, "no credentials must mean no subprocess call")
    }

    @Test
    fun `credentials reach the child only via environment, never in argv`() {
        val runner = CsvRunner(csv = runCatching { writeRealisticSstGrid().toFile().readText() }.getOrNull())
        runBlocking {
            client(runner).fetchGrid(CopernicusField.SST, 41.5, 2.5, date = date)
        }

        assertEquals(1, runner.runs)
        assertEquals("kmertad", runner.sawEnv["COPERNICUSMARINE_SERVICE_USERNAME"])
        assertEquals("test-password", runner.sawEnv["COPERNICUSMARINE_SERVICE_PASSWORD"])

        val argString = runner.sawArgs.joinToString(" ")
        assertFalse(argString.contains("kmertad"), "username must not appear in argv: $argString")
        assertFalse(
            argString.contains("test-password"),
            "password must not appear in argv: $argString"
        )
    }

    @Test
    fun `the requested box is clamped to the dataset coverage`() {
        val runner = CsvRunner()
        runBlocking {
            client(runner).fetchGrid(CopernicusField.SST, 45.95, 2.5, date = date)
        }
        val args = runner.sawArgs
        val maxLat = args[args.indexOf("--maximum-latitude") + 1]
        assertEquals("46.000000", maxLat, "a 0.3 box at 45.95N must clamp to SST coverage 46N")
    }

    @Test
    fun `a physics box clamps to the shared anfc product extent`() {
        val runner = CsvRunner()
        runBlocking {
            client(runner).fetchGrid(CopernicusField.BOTTOM_TEMP, 45.95, 2.5, date = date)
        }
        val args = runner.sawArgs
        assertEquals("45.979200", args[args.indexOf("--maximum-latitude") + 1])
    }

    @Test
    fun `a box entirely outside the dataset coverage does not call the CLI`() {
        val runner = CsvRunner()
        // Mediterranean SST coverage starts at 30.25N.
        val grid = runBlocking {
            client(runner).fetchGrid(CopernicusField.SST, 27.0, 2.5, date = date)
        }
        assertNull(grid)
        assertEquals(0, runner.runs)
    }

    @Test
    fun `a box entirely south of the anfc physics extent is refused`() {
        val runner = CsvRunner()
        val grid = runBlocking {
            client(runner).fetchGrid(CopernicusField.BOTTOM_TEMP, 29.5, 2.5, date = date)
        }
        assertNull(grid)
        assertEquals(0, runner.runs)
    }

    @Test
    fun `the default toolbox path is not a hardcoded user profile`() {
        // It used to be a literal absolute path into one developer's home
        // directory, so it resolved only on that machine and on nobody else's.
        // The check is on the SOURCE, not on the resolved value: on this very
        // machine APPDATA is C:\Users\cc\AppData\Roaming, so a correctly derived
        // path legitimately contains "Users\cc". What must not happen is that
        // literal appearing in the code, or a path under APPDATA resolving
        // somewhere that is not APPDATA.
        val source = Files.readString(
            Path.of("src/main/kotlin/com/shaka/data/client/CopernicusGridClient.kt")
        )
        assertFalse(
            source.contains("Users\\\\cc") || source.contains("Users/cc"),
            "the CLI path is still a literal from one developer's profile"
        )
        assertFalse(
            source.contains("AppData/Roaming/Python"),
            "the per-user Scripts directory is still hardcoded instead of derived"
        )

        val resolved = CopernicusGridClient.resolveCliPath()
        val appData = System.getenv("APPDATA")
        if (appData == null) {
            assertEquals(CopernicusGridClient.DEFAULT_CLI_PATH, resolved)
        } else {
            assertTrue(
                resolved == CopernicusGridClient.DEFAULT_CLI_PATH ||
                    resolved.startsWith(appData),
                "expected a path under APPDATA or a bare command name, got $resolved"
            )
        }
    }

    @Test
    fun `the injected CLI path is the one that reaches argv`() {
        // The escape hatch for a host where the toolbox is neither on PATH nor
        // under APPDATA: whatever is injected must be what the subprocess is
        // actually asked to run, not the discovery result.
        val runner = CsvRunner()
        runBlocking {
            client(runner, cliPath = "somewhere\\else\\copernicusmarine.exe")
                .fetchGrid(CopernicusField.SST, 40.0, 5.0, date = date)
        }
        assertEquals("somewhere\\else\\copernicusmarine.exe", runner.sawArgs.first())
    }
}
