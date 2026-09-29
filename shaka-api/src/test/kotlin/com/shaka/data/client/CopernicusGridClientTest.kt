package com.shaka.data.client

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [CopernicusGridClient] — the toolbox-subprocess path that feeds the
 * PFZ front-finder.
 *
 * Two layers are locked down:
 *  1. The CSV parser: unordered rows are re-indexed into a sorted lat/lon grid,
 *     Kelvin is converted for SST, NaN stays NaN (a missing cell, never a 0),
 *     and a malformed response is null, not a guess.
 *  2. The subprocess boundary: the command carries the right dataset variables
 *     and date, the box is clamped to dataset coverage, and credentials reach
 *     the child ONLY via process environment — never in argv, which is visible
 *     to any process that can read the command line.
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

    private fun client(
        runner: CsvRunner,
        username: String? = "kmertad",
        password: String? = "1245211458+Mk"
    ) = CopernicusGridClient(
        runner = runner,
        username = username,
        password = password,
        cliPath = "fake-copernicusmarine.exe"
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

    // --------------------------------------------------------------- hygiene

    @Test
    fun `credentials reach the child only via environment, never in argv`() {
        val runner = CsvRunner(csv = runCatching { writeRealisticSstGrid().toFile().readText() }.getOrNull())
        runBlocking {
            client(runner).fetchGrid(CopernicusField.SST, 41.5, 2.5, date = date)
        }

        assertEquals(1, runner.runs)
        assertEquals("kmertad", runner.sawEnv["COPERNICUSMARINE_SERVICE_USERNAME"])
        assertEquals("1245211458+Mk", runner.sawEnv["COPERNICUSMARINE_SERVICE_PASSWORD"])

        val argString = runner.sawArgs.joinToString(" ")
        assertFalse(argString.contains("kmertad"), "username must not appear in argv: $argString")
        assertFalse(
            argString.contains("1245211458"),
            "password must not appear in argv: $argString"
        )
    }

    @Test
    fun `the command names the dataset variable and the exact date`() {
        val runner = CsvRunner()
        runBlocking {
            client(runner).fetchGrid(CopernicusField.SSTA, 41.5, 2.5, date = date)
        }
        val args = runner.sawArgs
        assertTrue(args.contains("subset"))
        assertTrue(args.contains("--dataset-id"))
        assertTrue(
            args.contains("SST_MED_SSTA_L4_NRT_OBSERVATIONS_010_004_d"),
            "SSA field must pin its own dataset: ${args.joinToString(" ")}"
        )
        assertTrue(args.contains("sst_anomaly"))
        assertTrue(args.contains("2026-09-26"), "--start/--end datetime must be the requested date")
        assertTrue(args.contains("--file-format") && args.contains("csv"))
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
    fun `a box entirely outside the dataset coverage does not call the CLI`() {
        val runner = CsvRunner()
        // Mediterranean SST coverage starts at 30.25N.
        val grid = runBlocking {
            client(runner).fetchGrid(CopernicusField.SST, 27.0, 2.5, date = date)
        }
        assertNull(grid)
        assertEquals(0, runner.runs)
    }
}