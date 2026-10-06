package com.shaka.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the weather-tile interpreter validation.
 *
 * The pipeline job launches `python3`, which on Windows often resolves to the
 * Microsoft Store "App Execution Alias" stub: the stub exists on PATH, launches
 * cleanly, and then exits 9009 printing "Python was not found; run without
 * arguments to install from the Microsoft Store". So an interpreter is only
 * usable when a launched `--version` run both exits 0 AND answers like a real
 * Python - anything else must fall through to the next candidate.
 */
class WeatherTileServiceTest {

    @Test
    fun `accepts a real python version banner`() {
        assertTrue(WeatherTileService.isUsablePythonVersion("Python 3.13.7", 0))
        assertTrue(WeatherTileService.isUsablePythonVersion("Python 3.11.4 (main, Apr 6 2023) :: Anaconda, Inc. on linux", 0))
    }

    @Test
    fun `rejects the Windows Store stub output even with exit zero`() {
        assertFalse(
            WeatherTileService.isUsablePythonVersion(
                "Python was not found; run without arguments to install from the Microsoft Store, or disable this shortcut from Settings > Apps > Advanced app settings > App execution aliases.",
                0,
            )
        )
    }

    @Test
    fun `rejects a non-zero exit`() {
        assertFalse(WeatherTileService.isUsablePythonVersion("Python 3.13.7", 9009))
        assertFalse(WeatherTileService.isUsablePythonVersion("Python 3.13.7", 1))
    }

    @Test
    fun `rejects a program that is not python`() {
        assertFalse(WeatherTileService.isUsablePythonVersion("GNU bash, version 5.2.26", 0))
        assertFalse(WeatherTileService.isUsablePythonVersion("", 0))
    }
}