package com.shaka.pfz

import com.shaka.data.cache.SpotDataCache
import com.shaka.data.client.SpotDatabase
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlin.math.abs

/**
 * Builds a [PfzObservation] from cached spot data and scores it.
 *
 * This is deliberately a separate service rather than another method on
 * [com.shaka.service.SpotService]. SpotService is the SoCal pipeline and is
 * already ~2000 lines wide; PFZ has different data rules and must not be able
 * to regress them. The separation also keeps the v2 offshore front-finder honest:
 * it will produce a [PfzObservation] by some other route and call the same
 * [PfzEngine], and this class is the "spot route" implementation of that contract.
 *
 * The single rule this class exists to enforce: **every field is either a real
 * measurement or null.** There is no default, no fallback, no "reasonable guess".
 * A null here becomes a named missing factor in the response, which is the
 * outcome we want. Inventing a calm 0.5 m swell would silently turn an unknown
 * into a perfect score for every species in the roster.
 */
class PfzService(
    private val spotDb: SpotDatabase = SpotDatabase,
    /**
     * Cache seam.
     *
     * Defaults to the real global cache. Overridable because
     * `SpotDataCache.update*` all early-return when no database is connected, so
     * an in-memory test run cannot seed it — and an untestable data-mapping
     * layer is exactly where fabricated defaults hide.
     */
    private val cacheLookup: (String) -> SpotDataCache.SpotData? = SpotDataCache::get,
    /**
     * Optional spatial corridor: Copernicus Med grids + bathymetry analysis.
     *
     * When present, [observeWithGrid] enriches the cached observation with
     * real SST/SSTA/CHL gradients, front coincidence and bathymetric slope.
     * When absent (or when it fails), that corridor simply reports missing —
     * this service never invents a front.
     */
    private val gridSource: PfzGridService? = null
) {
    private val logger = LoggerFactory.getLogger(PfzService::class.java)

    /**
     * Result of a PFZ request. [notFound] distinguishes "no such spot" from
     * "spot exists but we have no data", because those are different failures
     * for the caller.
     */
    sealed interface Result {
        data class Ok(val response: PfzResponse) : Result
        data object UnknownSpot : Result
        data class BadDate(val message: String) : Result
    }

    /**
     * Result of a ranked-zones request. Empty zones are still [ZonesResult.Ok]:
     * the honest state when the grid corridor did not resolve is a response that
     * says so, not an error status.
     */
    sealed interface ZonesResult {
        data class Ok(val response: PfzZonesResponse) : ZonesResult
        data class BadDate(val message: String) : ZonesResult
        data class BadLocation(val message: String) : ZonesResult
        data object UnknownSpecies : ZonesResult
    }

    fun evaluate(spotId: String, date: String): Result {
        if (spotDb.findSpotById(spotId) == null) {
            return Result.UnknownSpot
        }

        val parsed = try {
            LocalDate.parse(date)
        } catch (e: DateTimeParseException) {
            return Result.BadDate("expected YYYY-MM-DD, got '$date'")
        }

        val obs = observe(spotId, parsed)
        logger.debug(
            "PFZ {} {} depth={} ({}) sst={} chl={}",
            spotId, date, obs.depthM, obs.depthSource, obs.waterTempC, obs.chlorophyllMgM3
        )
        return Result.Ok(PfzEngine.evaluate(obs))
    }

    /**
     * Like [evaluate], but with the spatial corridor enriched from the grid
     * source when one is configured. The route uses this; tests of the
     * cache-only path keep using [evaluate].
     */
    suspend fun evaluateWithGrid(spotId: String, date: String): Result {
        if (spotDb.findSpotById(spotId) == null) {
            return Result.UnknownSpot
        }

        val parsed = try {
            LocalDate.parse(date)
        } catch (e: DateTimeParseException) {
            return Result.BadDate("expected YYYY-MM-DD, got '$date'")
        }

        val obs = observeWithGrid(spotId, parsed)
        logger.debug(
            "PFZ-grid {} {} sstGrad={} chlGrad={} coinc={} anomaly={} day={}",
            spotId, date, obs.sstGradientCkm, obs.chlaGradient,
            obs.frontCoincidence, obs.sstAnomalyC, obs.forecastDay
        )
        return Result.Ok(PfzEngine.evaluate(obs))
    }

    /**
     * Same-day scored response, but for an arbitrary offshore coordinate rather
     * than a cached catalog spot.
     */
    suspend fun evaluateZones(lat: Double, lon: Double, date: String, speciesId: String): ZonesResult {
        if (!lat.isFinite() || abs(lat) > 90 || !lon.isFinite() || abs(lon) > 180) {
            return ZonesResult.BadLocation("lat in [-90, 90], lon in [-180, 180], got ($lat, $lon)")
        }
        val profile = PfzSpeciesRegistry.byId(speciesId) ?: return ZonesResult.UnknownSpecies
        val parsed = try {
            LocalDate.parse(date)
        } catch (e: DateTimeParseException) {
            return ZonesResult.BadDate("expected YYYY-MM-DD, got '$date'")
        }

        if (gridSource == null) {
            return ZonesResult.Ok(
                zonesResponse(profile, lat, lon, parsed, emptyList(), "No spatial grid corridor is configured.")
            )
        }

        val data = gridSource.analyzeZones(lat, lon, parsed)
        if (data.isEmpty()) {
            return ZonesResult.Ok(
                zonesResponse(
                    profile, lat, lon, parsed, emptyList(),
                    "Copernicus SST/SSTA/CHL grids did not resolve around ($lat, $lon). " +
                        "No zones can be ranked today."
                )
            )
        }

        val ranked = data
            .map { datum -> datum to PfzEngine.evaluateSpecies(profile, zoneObservation(parsed, datum)) }
            .sortedWith(
                compareByDescending<Pair<PfzZoneDatum, PfzSpeciesResult>> { it.second.status == PfzStatus.SCOREABLE }
                    .thenByDescending { it.second.pfz ?: -1 }
                    .thenBy { it.first.frontKm ?: Double.MAX_VALUE }
                    .thenByDescending { it.second.confidence }
                    .thenBy { it.first.lat }
                    .thenBy { it.first.lon }
            )

        val zones = ranked.mapIndexed { index, (datum, result) ->
            val rank = index + 1
            PfzZone(
                rank = rank,
                name = targetName(profile, parsed, rank),
                lat = datum.lat,
                lon = datum.lon,
                polygon = datum.polygon,
                holes = datum.holes,
                status = result.status,
                pfz = result.pfz,
                confidence = result.confidence,
                frontKm = datum.frontKm,
                frontCoincidence = datum.frontCoincidence,
                sstGradientCkm = datum.sstGradientCkm,
                chlaGradientMgM3km = datum.chlaGradientMgM3km,
                sstAnomalyC = datum.sstAnomalyC,
                depthM = datum.depthM,
                drivers = result.drivers,
                blockers = result.blockers,
                missingFactors = result.missingFactors,
                lowConfidenceFactors = result.lowConfidenceFactors
            )
        }
        return ZonesResult.Ok(zonesResponse(profile, lat, lon, parsed, zones))
    }

    /**
     * SatCatch-style target label for a ranked zone, e.g.
     * "Atlantic Bluefin Tuna", 27 Sep, rank 1 -> "BluefinTuna 27/09 001".
     */
    private fun targetName(profile: SpeciesProfile, date: LocalDate, rank: Int): String {
        val tag = profile.targetTag
            ?: profile.commonName.filter { it.isLetterOrDigit() }
        val day = date.dayOfMonth.toString().padStart(2, '0')
        val month = date.monthValue.toString().padStart(2, '0')
        val index = rank.toString().padStart(3, '0')
        return "$tag $day/$month $index"
    }

    /** Offshore zones have no cache: only what the corridor measured enters the engine. */
    private fun zoneObservation(date: LocalDate, datum: PfzZoneDatum) = PfzObservation(
        spotId = "offshore",
        date = date,
        depthM = datum.depthM,
        chlaGradient = datum.chlaGradientMgM3km,
        sstGradientCkm = datum.sstGradientCkm,
        sstAnomalyC = datum.sstAnomalyC,
        depthGradientMperKm = datum.depthGradientMperKm,
        frontKm = datum.frontKm,
        frontCoincidence = datum.frontCoincidence,
        dataDate = datum.dataDate,
        forecastDay = datum.forecastDay,
        region = null
    )

    private fun zonesResponse(
        profile: SpeciesProfile,
        lat: Double,
        lon: Double,
        date: LocalDate,
        zones: List<PfzZone>,
        extraNote: String? = null
    ): PfzZonesResponse {
        val notes = mutableListOf(
            "Each zone is one connected patch of the Copernicus Med grid sharing " +
                "the same thermal-core class (positive/negative/neutral SST anomaly), split " +
                "where a detected SST/CHL front crosses the grid; the polygon is that patch's " +
                "outline and its centre is the patch centroid. Grid-resolution bathymetry is " +
                "not available, so depth is a single-point reading at a sample cell inside " +
                "each polygon.",
            "Sea state, solunar and SST/CHL concentration are not measurable for an arbitrary " +
                "offshore point, so those factors are missing (weights renormalize) rather than guessed.",
            "Zone scores use the basin-wide habitat bands; a per-region SST override is not applied " +
                "outside the catalog spots."
        )
        extraNote?.let(notes::add)
        PfzSpeciesRegistry.loadErrors.forEach { notes += "roster: $it" }
        return PfzZonesResponse(
            speciesId = profile.id,
            speciesName = profile.commonName,
            speciesScientificName = profile.scientificName,
            date = date.toString(),
            lat = lat,
            lon = lon,
            confidence = zones.maxOfOrNull { it.confidence } ?: 0,
            zones = zones,
            missingFactors = zones.flatMap { it.missingFactors }.toSet().sorted(),
            coverageNotes = notes
        )
    }

    /**
     * Assemble what we actually know about a spot. Public so the route can
     * report data availability, and so tests can assert the mapping directly.
     */
    fun observe(spotId: String, date: LocalDate): PfzObservation {
        val cached = cacheLookup(spotId)
        val exposure = cached?.exposure

        return PfzObservation(
            spotId = spotId,
            date = date,

            // Real bathymetry, never SpotRecord.depth. The DB value is a
            // hand-entered nominal that is wrong by design for exactly the
            // deep-water species PFZ cares about.
            depthM = exposure?.depthM,
            depthSource = exposure?.depthSource,

            waterTempC = cached?.sst?.value,
            chlorophyllMgM3 = resolveChlorophyll(cached),
            // gradCHL is not computed on the cache-only path: the app's point
            // concentration is structurally incapable of representing a front.
            // observeWithGrid() fills this when a grid source is configured.
            chlaGradient = null,

            windSpeedKmh = cached?.wind?.value?.speedKnots?.let {
                SpotDataCache.knotsToKmh(it)
            },

            // Guard the cache, do not trust it. SpotService caches
            // `ocean.swellHeight` - the legacy field that falls back to 0.5m when
            // the provider omits swell - and labels it "open-meteo", so the value
            // alone is indistinguishable from a real observation. The
            // `measured` flag is the only thing separating them, and a habitat
            // model must not score a placeholder. An unmeasured swell is reported
            // as missing so it drops out and the weights renormalize.
            waveHeightM = cached?.swell?.value
                ?.takeIf { it.measured }
                ?.heightFt
                ?.let { SpotDataCache.feetToMeters(it) },
            swellHeightM = cached?.swell?.value
                ?.takeIf { it.measured }
                ?.swellHeightFt
                ?.let { SpotDataCache.feetToMeters(it) },

            // Not cached anywhere yet. OpenMeteoClient now maps it, but there
            // is no SpotDataCache column for it, so it stays null until the
            // cache gains one. No species currently defines a current gate.
            oceanCurrentVelocityKmh = null,

            solunarDayRating = cached?.solunar?.value?.dayRating,
            moonPhase = cached?.solunar?.value?.moonPhase,
            tideState = cached?.tide?.value?.state,

            exposureBearingDeg = exposure?.bearing,
            // ExposureInfo.width is the angular spread of the open-water arc in
            // degrees, despite the historical column name "exposure_width".
            exposureWidthDeg = exposure?.width,

            region = PfzRegions.classify(spotId)
        )
    }

    /**
     * The cache mapping, enriched with the spatial corridor when a
     * [gridSource] is present and resolves. Whatever cannot resolve stays null
     * and is reported as a named missing factor by the engine.
     */
    suspend fun observeWithGrid(spotId: String, date: LocalDate): PfzObservation {
        val base = observe(spotId, date)
        val source = gridSource ?: return base
        val record = spotDb.findSpotById(spotId) ?: return base

        val grid = source.analyze(record.coordinates.lat, record.coordinates.lon, date)
        if (grid.dataDate == null && grid.frontCoincidence == null &&
            grid.sstGradientCkm == null && grid.sstAnomalyC == null
        ) {
            // Nothing resolved — the analysis corridor is honestly unknown.
            return base
        }

        val forecastDays = grid.dataDate?.let { analyzed ->
            java.time.temporal.ChronoUnit.DAYS.between(analyzed, date).toInt().coerceAtLeast(0)
        } ?: 0

        return base.copy(
            chlaGradient = grid.chlaGradientMgM3km,
            sstGradientCkm = grid.sstGradientCkm,
            sstAnomalyC = grid.sstAnomalyC,
            depthGradientMperKm = grid.depthGradientMperKm,
            frontKm = grid.frontKm,
            frontCoincidence = grid.frontCoincidence,
            dataDate = grid.dataDate,
            forecastDay = forecastDays
        )
    }

    /**
     * Only NOAA ERDDAP chlorophyll counts.
     *
     * SpotDataCache.gibsChlorophyll is documented in the codebase as
     * "DISPLAY ONLY - do NOT represent actual chlorophyll concentrations",
     * because coastal imagery is contaminated by sediment, kelp and bottom
     * reflectance. Feeding that into a habitat model would be feeding it a
     * decorative image. It is deliberately not used as a fallback here.
     */
    private fun resolveChlorophyll(cached: SpotDataCache.SpotData?): Double? =
        cached?.chlorophyll?.value
}
