package com.shaka.service

import com.shaka.data.cache.OceanDataCache
import com.shaka.data.cache.SpotDataCache
import com.shaka.data.client.CommunityClient
import kotlin.math.roundToInt
import com.shaka.data.client.CopernicusClient
import com.shaka.data.client.GIBSClient
import com.shaka.data.client.NOAAClient
import com.shaka.data.client.TideClient
import com.shaka.data.client.OpenMeteoClient
import com.shaka.data.client.ProtectedSeasClient
import com.shaka.data.client.SpotDatabase
import com.shaka.data.db.UserSpotRepository
import com.shaka.model.*
import com.shaka.scoring.GibsColormap
import com.shaka.scoring.ShakaScorer
import com.shaka.util.SpotTime
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Service for searching and retrieving spearfishing spots.
 * 
 * Uses ACTUAL MEASURED DATA from Copernicus Marine L3 NRT:
 * - ZSD (Secchi disk depth) = real underwater visibility in meters
 * - CHL (Chlorophyll-a) = real plankton concentration
 * 
 * NO ESTIMATES. If satellite data unavailable, we say so honestly.
 * 
 * @see https://data.marine.copernicus.eu/product/OCEANCOLOUR_GLO_BGC_L3_NRT_009_101/description
 */
class SpotService {

    private val logger = LoggerFactory.getLogger(SpotService::class.java)
    private val openMeteo = OpenMeteoClient()
    private val copernicus = CopernicusClient()
    private val tidesClient = TideClient.create()
    private val noaaClient = NOAAClient()
    private val community = CommunityClient()
    private val forecastService = ForecastService()
    private val protectedSeasClient = ProtectedSeasClient()
    private val bathymetryClient = com.shaka.data.client.BathymetryClient()
    private val spotDb = SpotDatabase
    // Note: GlobalFishingWatchClient and SolunarClient are used by DataPrefetchJobs
    // SpotService reads the cached data from SpotDataCache
    
    // Tracks in-flight prefetches to prevent duplicate concurrent runs for the same spot
    private val inFlightPrefetches = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    
    // Lazy-load regulatory links from JSON resource
    private val regulatoryLinks: JsonElement? by lazy {
        try {
            val stream = javaClass.classLoader.getResourceAsStream("regulatory_links.json")
            if (stream != null) {
                val json = stream.bufferedReader().use { it.readText() }
                Json.parseToJsonElement(json)
            } else {
                logger.warn("Could not find regulatory_links.json")
                null
            }
        } catch (e: Exception) {
            logger.warn("Failed to load regulatory_links.json: ${e.message}")
            null
        }
    }

    /**
     * Search for spots within radius of a location.
     * 
     * OPTIMIZED: Uses prefetched cache for instant lookups (no API calls).
     * Falls back to live API calls only if cache is empty (during startup).
     */
    suspend fun searchSpots(lat: Double, lon: Double, radiusKm: Int, date: String): SearchResponse = coroutineScope {
        // Get spots from database within radius
        val nearbySpots = spotDb.findNearbySpots(lat, lon, radiusKm.toDouble())
        
        // Check if we have prefetched data available
        val hasPrefetchedData = nearbySpots.any { SpotDataCache.has(it.id) }
        
        // Fallback data for when cache is empty (during startup)
        val fallbackWeather: WeatherData?
        val fallbackOcean: OceanData?
        val fallbackWaterQuality: WaterQuality?
        val fallbackTide: TideData?
        
        if (!hasPrefetchedData) {
            // Cache is empty - fetch data the old way (only happens during startup)
            logger.info("Prefetch cache empty, fetching data live for search at ($lat, $lon)")
            
            val weatherDeferred = async {
                withTimeoutOrNull(5000) {
                    OceanDataCache.getWeather(lat, lon, date)
                        ?: openMeteo.getWeather(lat, lon, date)?.also {
                            OceanDataCache.putWeather(lat, lon, date, it)
                        }
                }
            }
            
            val oceanDeferred = async {
                withTimeoutOrNull(5000) {
                    OceanDataCache.getOcean(lat, lon, date)
                        ?: openMeteo.getMarineData(lat, lon, date)?.also {
                            OceanDataCache.putOcean(lat, lon, date, it)
                        }
                }
            }
            
            val waterQualityDeferred = async {
                withTimeoutOrNull(6000) {
                    copernicus.getWaterQuality(lat, lon, date)
                }
            }
            
            val tideDeferred = async {
                withTimeoutOrNull(5000) {
                    OceanDataCache.getTide(lat, lon, date) ?: run {
                        val data = tidesClient.getTideData(lat, lon, date)
                        OceanDataCache.putTide(lat, lon, date, data)
                        data
                    }
                }
            }
            
            fallbackWeather = weatherDeferred.await()
            fallbackOcean = oceanDeferred.await()
            fallbackWaterQuality = waterQualityDeferred.await()
            fallbackTide = tideDeferred.await()
        } else {
            fallbackWeather = null
            fallbackOcean = null
            fallbackWaterQuality = null
            fallbackTide = null
            logger.debug("Using prefetched cache for search at ($lat, $lon)")
        }

        // Score each spot using prefetched data or fallbacks
        val scoredSpots = nearbySpots.map { spot ->
            // Get prefetched data for this spot (instant lookup!)
            val cached = SpotDataCache.get(spot.id)
            
            // Build conditions from cached data or fallbacks.
            // Null = unavailable; do not fabricate values (Jun 2026 lesson).
            val weather: WeatherData? = if (cached?.wind != null && cached.swell != null) {
                WeatherData(
                    temperature = null,
                    windSpeed = cached.wind.value.speedKnots / 0.539957,  // knots back to km/h for the scorer
                    windDirection = null,  // cache carries only the cardinal; do not fabricate 0 deg
                    precipitation = null,
                    cloudCover = null,
                    visibility = null
                )
            } else {
                fallbackWeather
            }
            
            val ocean: OceanData? = if (cached?.swell != null) {
                val htM = (cached.swell.value.correctedHeightFt ?: cached.swell.value.heightFt) / 3.28084
                OceanData(
                    waveHeight = htM,
                    wavePeriod = cached.swell.value.periodSec,
                    waveDirection = 0,
                    waterTemperature = cached.sst?.value,
                    swellHeight = htM,
                    swellDirection = 0
                )
            } else {
                fallbackOcean
            }
            
            val waterQuality = if (cached?.visibility != null || cached?.sst != null) {
                WaterQuality(
                    chlorophyllA = cached.chlorophyll?.value,
                    visibility = cached.visibility?.value,
                    seaSurfaceTemp = cached.sst?.value,
                    dataSource = "Prefetched (${cached.visibility?.ageString() ?: "N/A"})"
                )
            } else {
                fallbackWaterQuality ?: WaterQuality(
                    chlorophyllA = null, visibility = null,
                    seaSurfaceTemp = null, dataSource = "Data temporarily unavailable"
                )
            }
            
            // Derive the now-state on read from the persisted chart (no FES);
            // fall back to the cached snapshot only if today's chart is missing.
            val tideData = deriveTideData(spot.id, spot.coordinates.lon)
                ?: if (cached?.tide != null) {
                    TideData(
                        currentHeight = cached.tide.value.currentHeight,
                        nextHighTide = cached.tide.value.nextHighTide,
                        nextLowTide = cached.tide.value.nextLowTide,
                        tideState = cached.tide.value.state,
                        nextHighTideTime = cached.tide.value.nextHighTideTime?.toEpochMilli(),
                        nextLowTideTime = cached.tide.value.nextLowTideTime?.toEpochMilli()
                    )
                } else {
                    fallbackTide ?: TideData(
                        currentHeight = 0.5, nextHighTide = "Check local source", 
                        nextLowTide = "Check local source", tideState = "Unknown"
                    )
                }
            
            val effectiveChl = resolveChlorophyll(
                waterQuality?.chlorophyllA, cached?.chlorophyll?.value, cached?.gibsChlorophyll?.value
            )

            val score = ShakaScorer.generateScore(
                targetDate = date,
                windSpeedKmh = weather?.windSpeed,
                waveHeightM = ocean?.waveHeight,
                chlorophyllMgM3 = effectiveChl,
                solunarDayRating = cached?.solunar?.value?.dayRating,
                moonPhase = cached?.solunar?.value?.moonPhase
            )

            val sst = resolveSST(cached?.sst?.value, spot.coordinates.lat, spot.coordinates.lon)
            
            // Data freshness from cache. Wind is the fastest-moving condition we
            // display, so it anchors the "updated X ago" label. Tide fetchedAt is
            // months old by design (full-year precompute) and must never feed this.
            val dataUpdatedMinutesAgo = cached?.wind?.minutesSinceFetch()?.toInt()
            val satelliteDataDate = cached?.sst?.dataDateString()
            
            // Build satellite readings from cache - colors for display, NOAA ERDDAP for actual chlorophyll
            val gibsReadings = if (cached?.gibsChlorophyll != null || cached?.chlorophyll != null) {
                val gibs = cached.gibsChlorophyll?.value
                GibsSatelliteReadings(
                    // Satellite imagery colors (display only - may include sediment/kelp)
                    paceTodayColor = gibs?.paceTodayColor,
                    paceYesterdayColor = gibs?.paceYesterdayColor,
                    noaa20TodayColor = gibs?.noaa20TodayColor,
                    noaa20YesterdayColor = gibs?.noaa20YesterdayColor,
                    noaa21TodayColor = gibs?.noaa21TodayColor,
                    noaa21YesterdayColor = gibs?.noaa21YesterdayColor,
                    sentinel3aTodayColor = gibs?.sentinel3aTodayColor,
                    sentinel3aYesterdayColor = gibs?.sentinel3aYesterdayColor,
                    sentinel3bTodayColor = gibs?.sentinel3bTodayColor,
                    sentinel3bYesterdayColor = gibs?.sentinel3bYesterdayColor,
                    // Observation times
                    paceObservationTime = gibs?.paceObservationTime?.toString(),
                    noaa20ObservationTime = gibs?.noaa20ObservationTime?.toString(),
                    noaa21ObservationTime = gibs?.noaa21ObservationTime?.toString(),
                    dataDate = gibs?.dataDate?.toString(),
                    // ACTUAL measured chlorophyll from NOAA ERDDAP (the trusted source)
                    chlorophyllMgM3 = cached.chlorophyll?.value,
                    noaaErddapFetchTime = cached.chlorophyll?.fetchedAt?.toString()
                )
            } else null
            
            SpotSummary(
                id = spot.id,
                name = spot.name,
                coordinates = spot.coordinates,
                shakaScore = score.overall,
                confidence = score.confidence,
                conditions = buildSwellConditionFields(cached).let { scf ->
                    val swellHt = cached?.swell?.value?.let { (it.correctedHeightFt ?: it.heightFt).roundToInt().toDouble() }
                        ?: ocean?.let { SpotDataCache.metersToFeet(it.waveHeight).roundToInt().toDouble() }
                    val swellPer = cached?.swell?.value?.periodSec?.roundToInt()?.toDouble()
                        ?: ocean?.wavePeriod?.roundToInt()?.toDouble()
                    val swellDir = cached?.swell?.value?.direction
                        ?: ocean?.let { SpotDataCache.degreesToCardinal(it.waveDirection.toDouble()) }
                    val windKts = cached?.wind?.value?.speedKnots
                        ?: weather?.windSpeed?.let { SpotDataCache.kmhToKnots(it) }
                    val windDir = cached?.wind?.value?.direction
                        ?: weather?.windDirection?.let { SpotDataCache.degreesToCardinal(it.toDouble()) }
                    SpotConditions(
                        visibility = getVisibilityLabel(effectiveChl),
                        waterTemp = formatWaterTemp(sst),
                        swell = cached?.swell?.let { 
                            "${it.value.heightFt.roundToInt()}ft @ ${it.value.periodSec.roundToInt()}s ${it.value.direction}" 
                        } ?: ocean?.let { "${it.waveHeight.roundToInt()}-${(it.waveHeight + 1).roundToInt()}ft @ ${it.wavePeriod.roundToInt()}s" }
                          ?: "Unavailable",
                        wind = cached?.wind?.let { 
                            SpotDataCache.formatWindLabel(it.value.speedKnots, it.value.direction) 
                        } ?: weather?.windSpeed?.let { ws -> weather.windDirection?.let { wd -> SpotDataCache.formatWindLabel(SpotDataCache.kmhToKnots(ws), SpotDataCache.degreesToCardinal(wd.toDouble())) } }
                          ?: "Unavailable",
                        tideState = "${tideData.tideState} - Next high: ${tideData.nextHighTide}",
                        dataUpdatedMinutesAgo = dataUpdatedMinutesAgo,
                        satelliteDataDate = satelliteDataDate,
                        swellSource = cached?.swell?.value?.source,
                        swellCorrected = scf.swellCorrected,
                        secondarySwell = scf.secondarySwell,
                        secondarySwellCorrected = scf.secondarySwellCorrected,
                        exposureBearing = scf.exposureBearing,
                        exposureWidth = scf.exposureWidth,
                        bathymetryDepthM = scf.bathymetryDepthM,
                        swellHeightFt = swellHt,
                        swellPeriodSec = swellPer,
                        swellDirection = swellDir,
                        windSpeedKts = windKts,
                        windDirectionCardinal = windDir,
                        waterTempC = sst,
                        swellRetrievedAt = cached?.swell?.fetchedAt?.toEpochMilli(),
                        windRetrievedAt = cached?.wind?.fetchedAt?.toEpochMilli(),
                        windDirectionDeg = cached?.wind?.value?.directionDeg ?: weather?.windDirection,
                        windValidAt = cached?.wind?.dataValidAt?.toEpochMilli(),
                        windKind = if (windKts != null) "hourlyForecast" else null,
                        windSource = if (windKts != null) "open-meteo" else null
                    )
                },
                gearRecommendations = generateGearRecs(sst, spot.depth),
                risks = generateRisks(weather, ocean),
                bestTimeOfDay = getBestTimeOfDay(cached?.solunar?.value?.moonPhase),
                satelliteReadings = gibsReadings
            )
        }.sortedByDescending { it.shakaScore }

        SearchResponse(
            spots = scoredSpots,
            searchCenter = Coordinates(lat, lon),
            radiusKm = radiusKm,
            date = date
        )
    }

    /**
     * Get detailed information for a specific spot.
     * 
     * OPTIMIZED: Uses prefetched cache for instant lookups.
     * Falls back to live API calls only if cache is empty.
     */
    suspend fun getSpotDetail(spotId: String, date: String): SpotDetail? = coroutineScope {
        val spot = spotDb.findSpotById(spotId) ?: return@coroutineScope null
        val lat = spot.coordinates.lat
        val lon = spot.coordinates.lon
        val region = inferRegionFromSpotId(spotId)
        
        // Check prefetched cache first (instant!)
        val cached = SpotDataCache.get(spotId)
        // Serve wind from the prefetched cache so the detail loads instantly. The
        // near-real-time wind is fetched separately by the client after first
        // paint (GET /spots/{id}/wind/live), so a slow upstream never blocks this
        // response (this was the ~3s regression when the live call was inline).
        val effectiveWind = cached?.wind
        
        // Build data from cache or fetch live. Null = genuinely unavailable;
        // never substitute fabricated values (Jun 2026 lesson).
        val weather: WeatherData?
        val ocean: OceanData?
        val waterQuality: WaterQuality
        val tideData: TideData
        
        if (cached != null && cached.tide != null) {
            // Use prefetched data - instant lookup, no API calls!
            logger.debug("Using prefetched data for spot detail: ${spot.name}")
            
            weather = if (cached.wind != null) {
                WeatherData(
                    temperature = null,
                    windSpeed = cached.wind.value.speedKnots / 0.539957,  // knots back to km/h for the scorer
                    windDirection = null,  // cache carries only the cardinal; do not fabricate 0 deg
                    precipitation = null,
                    cloudCover = null,
                    visibility = null
                )
            } else {
                null
            }
            
            ocean = if (cached.swell != null) {
                val htM = (cached.swell.value.correctedHeightFt ?: cached.swell.value.heightFt) / 3.28084
                OceanData(
                    waveHeight = htM,
                    wavePeriod = cached.swell.value.periodSec,
                    waveDirection = 0,
                    waterTemperature = cached.sst?.value,
                    swellHeight = htM,
                    swellDirection = 0
                )
            } else {
                null
            }
            
            waterQuality = WaterQuality(
                chlorophyllA = cached.chlorophyll?.value,
                visibility = cached.visibility?.value,
                seaSurfaceTemp = cached.sst?.value,
                dataSource = "Prefetched (updated ${cached.tide.ageString()})"
            )
            
            // Derive the now-state on read from the persisted chart (no FES);
            // fall back to the cached snapshot only if today's chart is missing.
            tideData = deriveTideData(spotId, lon) ?: TideData(
                currentHeight = cached.tide.value.currentHeight,
                nextHighTide = cached.tide.value.nextHighTide,
                nextLowTide = cached.tide.value.nextLowTide,
                tideState = cached.tide.value.state,
                nextHighTideTime = cached.tide.value.nextHighTideTime?.toEpochMilli(),
                nextLowTideTime = cached.tide.value.nextLowTideTime?.toEpochMilli()
            )
        } else {
            // Cache miss - fetch live (only during startup)
            logger.info("Prefetch cache miss for ${spot.name}, fetching live")
            
            val weatherDeferred = async {
                withTimeoutOrNull(5000) {
                    OceanDataCache.getWeather(lat, lon, date)
                        ?: openMeteo.getWeather(lat, lon, date)?.also {
                            OceanDataCache.putWeather(lat, lon, date, it)
                        }
                }
            }
            
            val oceanDeferred = async {
                withTimeoutOrNull(5000) {
                    OceanDataCache.getOcean(lat, lon, date)
                        ?: openMeteo.getMarineData(lat, lon, date)?.also {
                            OceanDataCache.putOcean(lat, lon, date, it)
                        }
                }
            }
            
            val waterQualityDeferred = async {
                withTimeoutOrNull(8000) {
                    copernicus.getWaterQuality(lat, lon, date)
                }
            }
            
            val tideDeferred = async {
                withTimeoutOrNull(5000) {
                    OceanDataCache.getTide(lat, lon, date) ?: run {
                        val data = tidesClient.getTideData(lat, lon, date)
                        OceanDataCache.putTide(lat, lon, date, data)
                        data
                    }
                }
            }
            
            weather = weatherDeferred.await()
            ocean = oceanDeferred.await()
            waterQuality = waterQualityDeferred.await() ?: WaterQuality(
                null, null, null, "Data temporarily unavailable"
            )
            tideData = deriveTideData(spotId, lon)
                ?: tideDeferred.await()
                ?: TideData(0.5, "Check local source", "Check local source", "Unknown")
        }
        
        // Forecast is lazy-loaded by the client via /forecast/{spotId} when user taps Forecast tab
        
        val solunarData = cached?.solunar?.value?.let { sol ->
            SolunarData(
                moonPhase = sol.moonPhase,
                illumination = sol.illumination,
                majorPeriods = listOfNotNull(
                    if (sol.majorStart1 != null && sol.majorEnd1 != null) 
                        TimePeriod(sol.majorStart1, sol.majorEnd1) else null,
                    if (sol.majorStart2 != null && sol.majorEnd2 != null) 
                        TimePeriod(sol.majorStart2, sol.majorEnd2) else null
                ),
                minorPeriods = listOfNotNull(
                    if (sol.minorStart1 != null && sol.minorEnd1 != null) 
                        TimePeriod(sol.minorStart1, sol.minorEnd1) else null,
                    if (sol.minorStart2 != null && sol.minorEnd2 != null) 
                        TimePeriod(sol.minorStart2, sol.minorEnd2) else null
                ),
                dayRating = sol.dayRating,
                hourlyRating = null
            )
        }

        logger.info("Spot detail loaded: ${spot.name} (${if (cached != null) "from cache" else "live fetch"})")

        val effectiveChl = resolveChlorophyll(
            waterQuality?.chlorophyllA, cached?.chlorophyll?.value, cached?.gibsChlorophyll?.value
        )

        val score = ShakaScorer.generateScore(
            targetDate = date,
            windSpeedKmh = weather?.windSpeed,
            waveHeightM = ocean?.waveHeight,
            chlorophyllMgM3 = effectiveChl,
            solunarDayRating = cached?.solunar?.value?.dayRating,
            moonPhase = cached?.solunar?.value?.moonPhase
        )
        
        val sst = resolveSST(cached?.sst?.value, lat, lon)
        
        // Data freshness from cache (wind-anchored; see getSpotSummaries).
        val dataUpdatedMinutesAgo = cached?.wind?.minutesSinceFetch()?.toInt()
        val satelliteDataDate = cached?.sst?.dataDateString()

        // Build satellite readings - colors for display, NOAA ERDDAP for actual chlorophyll
        val gibsReadings = if (cached?.gibsChlorophyll != null || cached?.chlorophyll != null) {
            val gibs = cached.gibsChlorophyll?.value
            GibsSatelliteReadings(
                // Satellite imagery colors (display only - may include sediment/kelp)
                paceTodayColor = gibs?.paceTodayColor,
                paceYesterdayColor = gibs?.paceYesterdayColor,
                noaa20TodayColor = gibs?.noaa20TodayColor,
                noaa20YesterdayColor = gibs?.noaa20YesterdayColor,
                noaa21TodayColor = gibs?.noaa21TodayColor,
                noaa21YesterdayColor = gibs?.noaa21YesterdayColor,
                sentinel3aTodayColor = gibs?.sentinel3aTodayColor,
                sentinel3aYesterdayColor = gibs?.sentinel3aYesterdayColor,
                sentinel3bTodayColor = gibs?.sentinel3bTodayColor,
                sentinel3bYesterdayColor = gibs?.sentinel3bYesterdayColor,
                // Observation times
                paceObservationTime = gibs?.paceObservationTime?.toString(),
                noaa20ObservationTime = gibs?.noaa20ObservationTime?.toString(),
                noaa21ObservationTime = gibs?.noaa21ObservationTime?.toString(),
                dataDate = gibs?.dataDate?.toString(),
                // ACTUAL measured chlorophyll from NOAA ERDDAP (the trusted source)
                chlorophyllMgM3 = cached.chlorophyll?.value,
                noaaErddapFetchTime = cached.chlorophyll?.fetchedAt?.toString()
            )
        } else null

        // Build water context with chlorophyll trend and SST nearby readings
        val waterContext = buildWaterContext(cached, waterQuality, lat, lon)

        // Load structured tide chart data from spot_tide_days
        val tideChart = loadTideChartData(spotId, lon)

        SpotDetail(
            id = spot.id,
            name = spot.name,
            description = spot.description,
            coordinates = spot.coordinates,
            score = score,
            access = AccessInfo(
                directions = spot.directions,
                parkingInfo = spot.parking,
                permitRequired = false
            ),
            conditions = run {
                val swellHt = cached?.swell?.value?.let { (it.correctedHeightFt ?: it.heightFt).roundToInt().toDouble() }
                    ?: ocean?.let { SpotDataCache.metersToFeet(it.waveHeight).roundToInt().toDouble() }
                val swellPer = cached?.swell?.value?.periodSec?.roundToInt()?.toDouble()
                    ?: ocean?.wavePeriod?.roundToInt()?.toDouble()
                val swellDir = cached?.swell?.value?.direction
                    ?: ocean?.let { SpotDataCache.degreesToCardinal(it.waveDirection.toDouble()) }
                val windKts = effectiveWind?.value?.speedKnots
                    ?: weather?.windSpeed?.let { SpotDataCache.kmhToKnots(it) }
                val windDir = effectiveWind?.value?.direction
                    ?: weather?.windDirection?.let { SpotDataCache.degreesToCardinal(it.toDouble()) }
                SpotConditions(
                    visibility = getVisibilityLabel(effectiveChl),
                    waterTemp = formatWaterTemp(sst),
                    swell = cached?.swell?.let { 
                        "${it.value.heightFt.roundToInt()}ft @ ${it.value.periodSec.roundToInt()}s ${it.value.direction}" 
                    } ?: ocean?.let { "${it.waveHeight.roundToInt()}-${(it.waveHeight + 1).roundToInt()}ft @ ${it.wavePeriod.roundToInt()}s" }
                      ?: "Unavailable",
                    wind = effectiveWind?.let { 
                        SpotDataCache.formatWindLabel(it.value.speedKnots, it.value.direction) 
                    } ?: weather?.windSpeed?.let { ws -> weather.windDirection?.let { wd -> SpotDataCache.formatWindLabel(SpotDataCache.kmhToKnots(ws), SpotDataCache.degreesToCardinal(wd.toDouble())) } }
                      ?: "Unavailable",
                    tideState = buildTideStateString(tideChart, tideData),
                    dataUpdatedMinutesAgo = dataUpdatedMinutesAgo,
                    satelliteDataDate = satelliteDataDate,
                    swellSource = cached?.swell?.value?.source,
                    swellCorrected = buildSwellConditionFields(cached).swellCorrected,
                    secondarySwell = buildSwellConditionFields(cached).secondarySwell,
                    secondarySwellCorrected = buildSwellConditionFields(cached).secondarySwellCorrected,
                    exposureBearing = cached?.exposure?.bearing,
                    exposureWidth = cached?.exposure?.width,
                    bathymetryDepthM = cached?.exposure?.depthM,
                    swellHeightFt = swellHt,
                    swellPeriodSec = swellPer,
                    swellDirection = swellDir,
                    windSpeedKts = windKts,
                    windDirectionCardinal = windDir,
                    waterTempC = sst,
                    swellRetrievedAt = cached?.swell?.fetchedAt?.toEpochMilli(),
                    windRetrievedAt = effectiveWind?.fetchedAt?.toEpochMilli(),
                    windDirectionDeg = effectiveWind?.value?.directionDeg ?: weather?.windDirection,
                    windValidAt = effectiveWind?.dataValidAt?.toEpochMilli(),
                    windKind = if (windKts != null) "hourlyForecast" else null,
                    windSource = if (windKts != null) "open-meteo" else null
                )
            },
            forecast = emptyList(),
            gearRecommendations = generateGearRecs(sst, spot.depth).map { item ->
                GearItem(item = item, reason = "Recommended for conditions", essential = true)
            },
            risks = generateRisks(weather, ocean).map { risk ->
                RiskInfo(risk = risk, severity = "moderate", mitigation = "Check conditions before entry")
            },
            communityReports = emptyList(),
            bestTimeOfDay = getBestTimeOfDay(cached?.solunar?.value?.moonPhase),
            imageUrl = spot.imageUrl,
            satelliteReadings = gibsReadings,
            regulations = getRegulationInfo(spotId, inferSpecificRegionFromSpotId(spotId), inferCountryFromSpotId(spotId)),
            solunar = solunarData,
            waterContext = waterContext,
            tide = tideChart
        )
    }

    // Near-real-time wind for detail screens, fetched on demand by the client
    // AFTER first paint via GET /spots/{id}/wind/live (so it never blocks the
    // detail load). Open-Meteo's `current` block updates ~every 15 min, so we
    // cache results in ~0.05deg buckets for the TTL to avoid an API storm when
    // many spots share coordinates, and bound each call so a slow upstream can't
    // hang the request.
    private val liveWindCache = ConcurrentHashMap<String, Pair<Instant, SpotDataCache.WindInfo>>()
    private val LIVE_WIND_TTL = Duration.ofMinutes(15)
    private val LIVE_WIND_TIMEOUT_MS = 2500L

    /**
     * Resolve near-real-time wind for a spot (catalog or user spot). Returns a
     * fresh live value from the bucket cache or a bounded Open-Meteo call, or
     * null when the spot is unknown or live wind is unavailable. Safe to call on
     * its own request path; never throws and never blocks beyond the timeout.
     */
    suspend fun getLiveWind(spotId: String): LiveWindResponse? {
        val coords = SpotDataCache.getSpotCoordinates(spotId)
            ?: spotDb.findSpotById(spotId)?.coordinates?.let { it.lat to it.lon }
            ?: return null
        val (lat, lon) = coords
        val key = "${(lat * 20).roundToInt()}:${(lon * 20).roundToInt()}"
        val now = Instant.now()
        liveWindCache[key]?.let { (ts, info) ->
            if (Duration.between(ts, now) < LIVE_WIND_TTL) {
                return info.toLiveWindResponse(ts)
            }
        }
        val live = try {
            withTimeoutOrNull(LIVE_WIND_TIMEOUT_MS) { openMeteo.getCurrentWind(lat, lon) }
        } catch (e: Exception) {
            logger.debug("Live wind fetch failed for ($lat,$lon): ${e.message}")
            null
        } ?: return null
        val info = SpotDataCache.WindInfo(
            speedKnots = SpotDataCache.kmhToKnots(live.speedKmh),
            direction = SpotDataCache.degreesToCardinal(live.directionDeg.toDouble()),
            gustKnots = live.gustKmh?.let { SpotDataCache.kmhToKnots(it) },
            directionDeg = live.directionDeg
        )
        liveWindCache[key] = now to info
        return info.toLiveWindResponse(now)
    }

    private fun SpotDataCache.WindInfo.toLiveWindResponse(retrievedAt: Instant) = LiveWindResponse(
        windSpeedKts = speedKnots,
        windDirectionCardinal = direction,
        gustKts = gustKnots,
        retrievedAt = retrievedAt.toEpochMilli(),
        windDirectionDeg = directionDeg,
        windKind = "live",
        windSource = "open-meteo"
    )

    /**
     * Full hourly swell + wind curves for a spot, served from the in-memory
     * series. Returns null if no series is loaded for this spot.
     */
    fun getSpotHourly(spotId: String): SpotHourlyResponse? {
        val cached = SpotDataCache.get(spotId) ?: return null
        val swell = cached.swellSeries?.points.orEmpty()
        val wind = cached.windSeries?.points.orEmpty()
        if (swell.isEmpty() && wind.isEmpty()) return null
        val tz = cached.swellSeries?.timezoneId ?: cached.windSeries?.timezoneId

        // Resolve the spot's zone the same way the tide system does, then group
        // points by spot-local date so the client never computes date
        // boundaries. lon is only a fallback when the IANA tz is missing.
        val lonFallback = spotDb.findSpotById(spotId)?.coordinates?.lon ?: 0.0
        val zone = SpotTime.resolveZone(tz, lonFallback)
        val today = SpotTime.spotLocalDate(tz, lonFallback).toString()

        val swellByDate = swell.groupBy { SpotTime.localDateOf(it.epochMs, zone).toString() }
        val windByDate = wind.groupBy { SpotTime.localDateOf(it.epochMs, zone).toString() }

        // Only keep today onward so days[0] is reliably the spot-local "today".
        val dates = (swellByDate.keys + windByDate.keys)
            .filter { it >= today }
            .toSortedSet()

        val days = dates.map { d ->
            SpotHourlyDay(
                localDate = d,
                swell = swellByDate[d].orEmpty(),
                wind = windByDate[d].orEmpty()
            )
        }
        if (days.isEmpty()) return null
        val generatedAt = listOfNotNull(
            cached.swellSeries?.fetchedAt,
            cached.windSeries?.fetchedAt
        ).maxOrNull()?.toEpochMilli()
        // Spot-local time metadata for clients without an IANA tz database:
        // current offset (DST-aware) + a short display label.
        val nowInZone = java.time.Instant.now().atZone(zone)
        val offsetMinutes = nowInZone.offset.totalSeconds / 60
        // "zzz" yields DST-aware abbreviations ("HST", "PDT"); zones without
        // one fall back to "GMT+X" which is still an honest display label.
        val zoneAbbr = nowInZone.format(
            java.time.format.DateTimeFormatter.ofPattern("zzz", java.util.Locale.ENGLISH)
        )
        return SpotHourlyResponse(
            spotId, tz, days, generatedAt,
            utcOffsetMinutes = offsetMinutes,
            timezoneAbbr = zoneAbbr
        )
    }

    /**
     * Multi-day tide chart curves, one TideChartData per spot-local day starting
     * at today. Generalizes loadTideChartData (today-only) by looping local
     * dates resolved from the spot's IANA timezone. Only today's entry gets
     * currentHeightFt / currentStage; other days are pure forecast curves.
     */
    fun getTideRange(spotId: String, days: Int): SpotTideRangeResponse? {
        return try {
            val series = SpotDataCache.getTideSeries(spotId, tidesClient.provider)
            val tz = series?.timezoneId
            val lonFallback = spotDb.findSpotById(spotId)?.coordinates?.lon ?: 0.0
            val startDate = SpotTime.spotLocalDate(tz, lonFallback)
            val json = Json { ignoreUnknownKeys = true }
            val nowMs = System.currentTimeMillis()

            val result = ArrayList<TideChartData>(days)
            for (i in 0 until days) {
                val date = startDate.plusDays(i.toLong())
                val dateStr = date.toString()
                val row = SpotDataCache.getTideDay(spotId, dateStr, tidesClient.provider) ?: continue

                val points: List<TidePoint> = row.pointsJson?.let {
                    json.decodeFromString<List<TidePoint>>(it)
                } ?: continue
                if (points.isEmpty()) continue
                val extremes: List<TideExtreme> = row.extremesJson?.let {
                    json.decodeFromString<List<TideExtreme>>(it)
                } ?: emptyList()

                val isToday = i == 0
                val (currentHeight, currentStage) = if (isToday) {
                    interpolateTide(points, extremes, nowMs)
                } else {
                    Pair(null, null)
                }

                result.add(
                    TideChartData(
                        provider = row.provider,
                        stationId = series?.stationId ?: row.stationId ?: "",
                        stationName = series?.stationName ?: row.stationName ?: "",
                        stationDistanceMi = series?.stationDistanceMi ?: row.stationDistanceMi ?: 0.0,
                        datum = row.datum ?: series?.datum ?: "MLLW",
                        timezoneId = row.timezoneId ?: series?.timezoneId ?: "",
                        points = points,
                        extremes = extremes,
                        currentHeightFt = currentHeight,
                        currentStage = currentStage,
                        localDate = dateStr
                    )
                )
            }
            if (result.isEmpty()) return null
            SpotTideRangeResponse(spotId, tz, result)
        } catch (e: Exception) {
            logger.debug("Failed to load tide range for $spotId: ${e.message}")
            null
        }
    }

    private fun spotLocalDate(lon: Double): LocalDate = SpotTime.spotLocalDate(null, lon)

    /**
     * Today's date in the spot's real local timezone. Prefers the IANA tz
     * recorded in spot_tide_series (set during year backfill); the longitude
     * approximation is only a fallback for spots without a series row yet.
     * Delegates to the shared SpotTime helper so all timezone resolution stays
     * in one place.
     */
    private fun spotLocalDate(spotId: String, lon: Double): LocalDate {
        return SpotTime.spotLocalDate(SpotDataCache.getTideSeries(spotId)?.timezoneId, lon)
    }

    /**
     * Materialize a full-year tide horizon for a single (new) spot, mirroring
     * the catalog backfill: each day to spot_tide_days plus the horizon in
     * spot_tide_series. Run fire-and-forget on spot creation so a brand-new
     * spot stops re-hitting the FES service on every later read.
     */
    private suspend fun materializeTideYearForSpot(
        fesClient: com.shaka.data.client.FES2022TideClient,
        spotId: String,
        lat: Double,
        lon: Double,
        days: Int = 365
    ) {
        val result = fesClient.getTideYear(lat, lon, null, days) ?: return
        val jsonEncoder = Json { ignoreUnknownKeys = true }
        for (day in result.tideDays) {
            SpotDataCache.upsertTideDay(SpotDataCache.TideDayRow(
                spotId = spotId,
                localDate = day.localDate,
                provider = "fes2022",
                stationId = "",
                stationName = "FES2022",
                stationDistanceMi = 0.0,
                timezoneId = result.timezoneId,
                datum = result.datum,
                pointsJson = jsonEncoder.encodeToString(ListSerializer(TidePoint.serializer()), day.points),
                extremesJson = jsonEncoder.encodeToString(ListSerializer(TideExtreme.serializer()), day.extremes),
                fetchedAt = Instant.now()
            ))
        }
        SpotDataCache.upsertTideSeries(SpotDataCache.TideSeriesRow(
            spotId = spotId,
            provider = "fes2022",
            lat = lat,
            lon = lon,
            timezoneId = result.timezoneId,
            datum = result.datum,
            stationId = null,
            stationName = "FES2022",
            stationDistanceMi = null,
            modelVersion = "FES2022",
            stepMinutes = result.stepMinutes,
            generatedFrom = result.tideDays.firstOrNull()?.localDate,
            generatedThrough = result.tideDays.lastOrNull()?.localDate,
            generatedAt = Instant.now(),
            status = "ready"
        ))
        logger.info("Full-year tide materialized for new spot $spotId: ${result.tideDays.size} days")
    }

    private fun loadTideChartData(spotId: String, lon: Double): TideChartData? {
        return try {
            // One series lookup serves both the local-date boundary (real IANA
            // tz) and the chart metadata (station/datum/tz), so the per-day rows
            // only need to carry the time-varying points/extremes.
            val series = SpotDataCache.getTideSeries(spotId, tidesClient.provider)
            val today = run {
                val tzId = series?.timezoneId
                if (!tzId.isNullOrEmpty()) {
                    try { return@run Instant.now().atZone(java.time.ZoneId.of(tzId)).toLocalDate() }
                    catch (_: Exception) { /* fall through */ }
                }
                spotLocalDate(lon)
            }.toString()
            val row = SpotDataCache.getTideDay(spotId, today, tidesClient.provider) ?: return null

            val json = Json { ignoreUnknownKeys = true }
            val points: List<TidePoint> = row.pointsJson?.let {
                json.decodeFromString<List<TidePoint>>(it)
            } ?: return null
            val extremes: List<TideExtreme> = row.extremesJson?.let {
                json.decodeFromString<List<TideExtreme>>(it)
            } ?: emptyList()

            if (points.isEmpty()) return null

            val nowMs = System.currentTimeMillis()
            val (currentHeight, currentStage) = interpolateTide(points, extremes, nowMs)

            TideChartData(
                provider = row.provider,
                stationId = series?.stationId ?: row.stationId ?: "",
                stationName = series?.stationName ?: row.stationName ?: "",
                stationDistanceMi = series?.stationDistanceMi ?: row.stationDistanceMi ?: 0.0,
                datum = row.datum ?: series?.datum ?: "MLLW",
                timezoneId = row.timezoneId ?: series?.timezoneId ?: "",
                points = points,
                extremes = extremes,
                currentHeightFt = currentHeight,
                currentStage = currentStage
            )
        } catch (e: Exception) {
            logger.debug("Failed to load tide chart for $spotId: ${e.message}")
            null
        }
    }

    private fun interpolateTide(
        points: List<TidePoint>,
        extremes: List<TideExtreme>,
        nowMs: Long
    ): Pair<Double?, String?> {
        if (points.size < 2) return Pair(null, null)

        var before: TidePoint? = null
        var after: TidePoint? = null
        for (p in points) {
            if (p.epochMs <= nowMs) before = p
            if (p.epochMs > nowMs && after == null) after = p
        }

        val height = if (before != null && after != null) {
            val fraction = (nowMs - before.epochMs).toDouble() / (after.epochMs - before.epochMs)
            before.heightFt + fraction * (after.heightFt - before.heightFt)
        } else {
            (before ?: after)?.heightFt
        }

        val stage = extremes.filter { it.epochMs > nowMs }.minByOrNull { it.epochMs }?.let {
            if (it.type == "H") "rising" else "falling"
        }

        return Pair(
            height?.let { (it * 100).toInt() / 100.0 },
            stage
        )
    }

    /**
     * Derive the live tide now-state (current height, stage, next high/low) by
     * interpolating the persisted spot_tide_days chart against the current time.
     * Tide curves are deterministic, so this needs no FES call -- it replaces the
     * old hourly snapshot-refresh job and is computed on read for the requested
     * spot only. Returns null when today's chart is missing or already elapsed,
     * in which case callers fall back to the cached snapshot.
     */
    private fun deriveTideData(spotId: String, lon: Double): TideData? {
        return try {
            val series = SpotDataCache.getTideSeries(spotId, tidesClient.provider)
            val today = run {
                val tzId = series?.timezoneId
                if (!tzId.isNullOrEmpty()) {
                    try { return@run Instant.now().atZone(java.time.ZoneId.of(tzId)).toLocalDate() }
                    catch (_: Exception) { /* fall through */ }
                }
                spotLocalDate(lon)
            }.toString()
            val row = SpotDataCache.getTideDay(spotId, today, tidesClient.provider) ?: return null
            val json = Json { ignoreUnknownKeys = true }
            val points: List<TidePoint> = row.pointsJson?.let {
                json.decodeFromString<List<TidePoint>>(it)
            } ?: return null
            if (points.size < 2) return null
            val extremes: List<TideExtreme> = row.extremesJson?.let {
                json.decodeFromString<List<TideExtreme>>(it)
            } ?: emptyList()

            val nowMs = System.currentTimeMillis()
            var before: TidePoint? = null
            var after: TidePoint? = null
            for (p in points) {
                if (p.epochMs <= nowMs) before = p
                if (p.epochMs > nowMs && after == null) after = p
            }
            // Chart day is over (or hasn't started); fall back to cached snapshot
            if (before == null || after == null) return null

            val fraction = (nowMs - before.epochMs).toDouble() / (after.epochMs - before.epochMs)
            val currentHeight = before.heightFt + fraction * (after.heightFt - before.heightFt)

            val zoneId = (row.timezoneId ?: series?.timezoneId)?.takeIf { it.isNotEmpty() }
                ?.let { try { java.time.ZoneId.of(it) } catch (_: Exception) { null } }
                ?: java.time.ZoneId.systemDefault()
            fun fmt(e: TideExtreme): String {
                val time = Instant.ofEpochMilli(e.epochMs).atZone(zoneId)
                    .format(java.time.format.DateTimeFormatter.ofPattern("h:mma"))
                return "$time (${String.format("%.1f", e.heightFt)}ft)"
            }
            val nextHigh = extremes.filter { it.type == "H" && it.epochMs > nowMs }.minByOrNull { it.epochMs }
            val nextLow = extremes.filter { it.type == "L" && it.epochMs > nowMs }.minByOrNull { it.epochMs }
            val state = when {
                nextHigh != null && (nextLow == null || nextHigh.epochMs < nextLow.epochMs) -> "rising"
                nextLow != null -> "falling"
                else -> return null
            }

            TideData(
                currentHeight = (currentHeight * 100).toInt() / 100.0,
                nextHighTide = nextHigh?.let { fmt(it) } ?: "N/A",
                nextLowTide = nextLow?.let { fmt(it) } ?: "N/A",
                tideState = state,
                nextHighTideTime = nextHigh?.epochMs,
                nextLowTideTime = nextLow?.epochMs
            )
        } catch (e: Exception) {
            logger.debug("Chart-derived tide failed for $spotId: ${e.message}")
            null
        }
    }

    /**
     * Build the tideState string for the Conditions card from live chart data,
     * falling back to the cached summary when chart data is unavailable.
     *
     * This ensures the Conditions card and the Tides chart header always agree
     * on rising/falling stage and next-high time.
     */
    private fun buildTideStateString(tideChart: TideChartData?, tideData: TideData): String {
        val nowMs = System.currentTimeMillis()

        val stage = tideChart?.currentStage ?: tideData.tideState

        val nextHighText = tideChart?.extremes
            ?.filter { it.type == "H" && it.epochMs > nowMs }
            ?.minByOrNull { it.epochMs }
            ?.let { ext ->
                val zoneId = tideChart.timezoneId.takeIf { it.isNotEmpty() }
                    ?.let { try { java.time.ZoneId.of(it) } catch (_: Exception) { null } }
                    ?: java.time.ZoneId.systemDefault()
                val time = java.time.Instant.ofEpochMilli(ext.epochMs)
                    .atZone(zoneId)
                    .format(java.time.format.DateTimeFormatter.ofPattern("h:mma"))
                "$time (${String.format("%.1f", ext.heightFt)}ft)"
            }
            ?: tideData.nextHighTide

        return "$stage - Next high: $nextHighText"
    }

    /**
     * Get community reports for a region from multiple sources.
     * Sources include Reddit, DeeperBlue, Spearfisherman.com, regional blogs.
     */
    suspend fun getCommunityReports(region: String): List<CommunityReport> {
        return try {
            community.getReportsForRegion(region)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Search spots by name (for type-ahead search).
     * Returns basic spot info with shaka score from prefetch cache.
     */
    fun searchSpotsByName(query: String, limit: Int): List<SpotSearchResult> {
        val queryLower = query.lowercase()
        val today = java.time.LocalDate.now().toString()
        
        return spotDb.getAllSpots()
            .filter { spot ->
                spot.name.lowercase().contains(queryLower) ||
                spot.id.lowercase().contains(queryLower) ||
                spot.description.lowercase().contains(queryLower)
            }
            .take(limit)
            .map { spot ->
                // Get cached data for score calculation
                val cached = SpotDataCache.get(spot.id)
                
                // Calculate score using cached data if available
                val score = if (cached != null) {
                    val visM = cached.visibility?.value ?: 15.0
                    val swellHeightFt = cached.swell?.value?.correctedHeightFt ?: cached.swell?.value?.heightFt ?: 2.0
                    // Null = unknown wind; never default to a fabricated 5 kts
                    // (which scored as near-perfect glass).
                    val windKts: Double? = cached.wind?.value?.speedKnots
                    
                    // Simple weighted score from key factors
                    val visScore = when {
                        visM >= 25 -> 100
                        visM >= 20 -> 90
                        visM >= 15 -> 80
                        visM >= 10 -> 70
                        visM >= 7 -> 60
                        visM >= 5 -> 50
                        else -> 35
                    }
                    val swellScore = when {
                        swellHeightFt <= 1 -> 100
                        swellHeightFt <= 2 -> 90
                        swellHeightFt <= 3 -> 80
                        swellHeightFt <= 4 -> 65
                        swellHeightFt <= 6 -> 45
                        else -> 25
                    }
                    val windScore = when {
                        windKts == null -> null  // unknown: drop the wind term below
                        windKts <= 5 -> 100
                        windKts <= 10 -> 85
                        windKts <= 15 -> 65
                        windKts <= 20 -> 45
                        else -> 25
                    }
                    
                    // Weighted average: visibility 40%, swell 35%, wind 25%.
                    // Unknown wind redistributes its weight instead of scoring
                    // a fabricated value.
                    if (windScore != null) {
                        ((visScore * 0.40) + (swellScore * 0.35) + (windScore * 0.25)).toInt()
                    } else {
                        ((visScore * 0.40 + swellScore * 0.35) / 0.75).toInt()
                    }
                } else {
                    // Default score when no cache - use reasonable estimate
                    65
                }
                
                SpotSearchResult(
                    id = spot.id,
                    name = spot.name,
                    region = inferRegionFromSpotId(spot.id),
                    coordinates = spot.coordinates,
                    shakaScore = score
                )
            }
    }
    
    /**
     * Batch fetch current conditions for multiple spots.
     * Used for favorites/home screen to show live data.
     */
    suspend fun getSpotsBatch(spotIds: List<String>, date: String): BatchSpotsResponse {
        val spots = spotIds.mapNotNull { spotId ->
            val spot = spotDb.findSpotById(spotId) ?: return@mapNotNull null
            val lat = spot.coordinates.lat
            val lon = spot.coordinates.lon
            
            try {
                // Fetch weather data (with caching); skip spot if unavailable
                val weather = OceanDataCache.getWeather(lat, lon, date)
                    ?: openMeteo.getWeather(lat, lon, date)?.also {
                        OceanDataCache.putWeather(lat, lon, date, it)
                    }
                    ?: return@mapNotNull null
                
                // Fetch ocean/swell data (with caching); skip spot if unavailable
                val ocean = OceanDataCache.getOcean(lat, lon, date)
                    ?: openMeteo.getMarineData(lat, lon, date)?.also {
                        OceanDataCache.putOcean(lat, lon, date, it)
                    }
                    ?: return@mapNotNull null
                
                // Fetch water quality
                val waterQuality = copernicus.getWaterQuality(lat, lon, date)
                
                // Get cached data for score
                val spotCache = SpotDataCache.get(spotId)
                val cachedSolunar = spotCache?.solunar?.value

                val effectiveChl = resolveChlorophyll(
                    waterQuality?.chlorophyllA, spotCache?.chlorophyll?.value, spotCache?.gibsChlorophyll?.value
                )
                
                // Calculate score ? prefer at-spot corrected swell over open-ocean
                val scoringWaveHeightM = spotCache?.swell?.value?.let {
                    (it.correctedHeightFt ?: it.heightFt) / 3.28084
                } ?: ocean.waveHeight
                val score = ShakaScorer.generateScore(
                    targetDate = date,
                    windSpeedKmh = weather.windSpeed,
                    waveHeightM = scoringWaveHeightM,
                    chlorophyllMgM3 = effectiveChl,
                    solunarDayRating = cachedSolunar?.dayRating,
                    moonPhase = cachedSolunar?.moonPhase
                )
                
                val sst = resolveSST(spotCache?.sst?.value, lat, lon)
                
                SpotSummary(
                    id = spot.id,
                    name = spot.name,
                    coordinates = spot.coordinates,
                    shakaScore = score.overall,
                    confidence = score.confidence,
                    conditions = buildSwellConditionFields(SpotDataCache.get(spotId)).let { scf ->
                        SpotConditions(
                            visibility = getVisibilityLabel(effectiveChl),
                            waterTemp = formatWaterTemp(sst),
                            swell = "${ocean.waveHeight.roundToInt()}-${(ocean.waveHeight + 1).roundToInt()}ft @ ${ocean.wavePeriod.roundToInt()}s",
                            wind = weather.windSpeed?.let { ws -> weather.windDirection?.let { wd -> SpotDataCache.formatWindLabel(SpotDataCache.kmhToKnots(ws), SpotDataCache.degreesToCardinal(wd.toDouble())) } } ?: "Unavailable",
                            tideState = "",
                            swellSource = "open-meteo",
                            swellCorrected = scf.swellCorrected,
                            secondarySwell = scf.secondarySwell,
                            secondarySwellCorrected = scf.secondarySwellCorrected,
                            exposureBearing = scf.exposureBearing,
                            exposureWidth = scf.exposureWidth,
                            bathymetryDepthM = scf.bathymetryDepthM,
                            swellHeightFt = SpotDataCache.metersToFeet(ocean.waveHeight).roundToInt().toDouble(),
                            swellPeriodSec = ocean.wavePeriod.roundToInt().toDouble(),
                            swellDirection = SpotDataCache.degreesToCardinal(ocean.waveDirection.toDouble()),
                            windSpeedKts = weather.windSpeed?.let { SpotDataCache.kmhToKnots(it) },
                            windDirectionCardinal = weather.windDirection?.let { SpotDataCache.degreesToCardinal(it.toDouble()) },
                            waterTempC = sst
                        )
                    },
                    gearRecommendations = emptyList(),
                    risks = emptyList(),
                    bestTimeOfDay = getBestTimeOfDay(SpotDataCache.get(spotId)?.solunar?.value?.moonPhase)
                )
            } catch (e: Exception) {
                logger.warn("Failed to fetch data for spot $spotId: ${e.message}")
                null
            }
        }
        
        return BatchSpotsResponse(
            spots = spots,
            date = date,
            fetchedAt = java.time.Instant.now().toString()
        )
    }
    
    /**
     * Get all unique regions for search autocomplete.
     * Includes center coordinates calculated from spot positions.
     */
    fun getAllRegions(): List<RegionInfo> {
        data class RegionData(
            val spotIds: MutableList<String> = mutableListOf(),
            var totalLat: Double = 0.0,
            var totalLon: Double = 0.0
        )
        
        val regionMap = mutableMapOf<String, RegionData>()
        
        spotDb.getAllSpots().forEach { spot ->
            val region = inferRegionFromSpotId(spot.id)
            val data = regionMap.getOrPut(region) { RegionData() }
            data.spotIds.add(spot.id)
            data.totalLat += spot.coordinates.lat
            data.totalLon += spot.coordinates.lon
        }
        
        return regionMap.map { (region, data) ->
            val count = data.spotIds.size
            RegionInfo(
                id = region,
                name = region.replaceFirstChar { it.uppercase() },
                spotCount = count,
                centerLat = if (count > 0) data.totalLat / count else 0.0,
                centerLon = if (count > 0) data.totalLon / count else 0.0
            )
        }.sortedBy { it.name }
    }
    
    /**
     * Get human-readable region name for a spot (public accessor).
     * Used by /spots/all endpoint for map markers.
     */
    fun getRegionForSpot(spotId: String): String {
        return inferSpecificRegionFromSpotId(spotId)
    }


    private fun getSeasonalMultiplier(spotId: String, date: String): Double {
        val month = java.time.LocalDate.parse(date).monthValue
        val hemisphere = getHemisphere(spotId)
        
        // Adjust for hemisphere - southern hemisphere seasons are opposite
        val adjustedMonth = if (hemisphere == "south") (month + 6 - 1) % 12 + 1 else month
        
        // Regional seasonal patterns
        return when {
            spotId.startsWith("hawaii") || spotId.startsWith("oahu") || spotId.startsWith("maui") ||
            spotId.startsWith("kauai") || spotId.startsWith("molokai") || spotId.startsWith("lanai") ||
            spotId.startsWith("bigisland") -> {
                // Hawaii: Best May-September (calm summer), decent year-round
                when (adjustedMonth) {
                    in 5..9 -> 1.4  // Peak summer
                    in 4..4, in 10..10 -> 1.2  // Shoulder
                    else -> 1.0  // Winter swells but still fishable
                }
            }
            spotId.contains("bahamas") || spotId.startsWith("exuma") || spotId.startsWith("andros") -> {
                // Bahamas: Best March-June for grouper, Nov-March for wahoo/tuna
                when (adjustedMonth) {
                    in 3..6 -> 1.4  // Grouper season
                    in 11..12, in 1..2 -> 1.3  // Pelagic season
                    else -> 1.1
                }
            }
            spotId.contains("mediterranean") || spotId.startsWith("italy") || spotId.startsWith("spain") ||
            spotId.startsWith("greece") || spotId.startsWith("croatia") || spotId.startsWith("france") -> {
                // Mediterranean: Best May-October
                when (adjustedMonth) {
                    in 6..9 -> 1.5  // Peak summer
                    in 5..5, in 10..10 -> 1.3  // Shoulder
                    else -> 0.8  // Winter - cold and rough
                }
            }
            else -> {
                // Default seasonal pattern
                when (adjustedMonth) {
                    in 5..9 -> 1.3
                    in 3..4, in 10..11 -> 1.1
                    else -> 0.9
                }
            }
        }
    }

    /**
     * Determine hemisphere from spot ID for seasonal adjustments.
     */
    private fun getHemisphere(spotId: String): String {
        return when {
            spotId.startsWith("aus-") || spotId.startsWith("nz-") || 
            spotId.startsWith("brazil-") || spotId.startsWith("sa-") ||
            spotId.startsWith("moz-") || spotId.startsWith("fiji-") -> "south"
            else -> "north"
        }
    }

    /**
     * Infer region from coordinates for community report lookup.
     */
    private fun inferRegionFromCoords(lat: Double, lon: Double): String {
        return when {
            lat in 18.0..23.0 && lon in -161.0..-154.0 -> "hawaii"
            lat in 23.0..28.0 && lon in -80.0..-72.0 -> "bahamas"
            lat in 24.0..26.0 && lon in -82.0..-80.0 -> "florida"
            lat in 32.0..42.0 && lon in -124.0..-117.0 -> "california"
            lat in 35.0..45.0 && lon in 5.0..20.0 -> "mediterranean"
            lat in -20.0..0.0 && lon in 115.0..155.0 -> "indonesia"
            lat in -45.0..-10.0 && lon in 110.0..155.0 -> "australia"
            else -> "worldwide"
        }
    }

    /**
     * Infer region from spot ID for community report lookup.
     */
    private fun inferRegionFromSpotId(spotId: String): String {
        return when {
            spotId.startsWith("oahu-") || spotId.startsWith("maui-") || 
            spotId.startsWith("bigisland-") || spotId.startsWith("kauai-") ||
            spotId.startsWith("molokai-") || spotId.startsWith("lanai-") -> "hawaii"
            spotId.startsWith("keys-") || spotId.startsWith("fl-") -> "florida"
            spotId.startsWith("cali-") -> "california"
            spotId.contains("bahamas") || spotId.startsWith("andros-") || 
            spotId.startsWith("exuma-") -> "bahamas"
            spotId.startsWith("italy-") || spotId.startsWith("sardinia-") ||
            spotId.startsWith("sicily-") -> "italy"
            spotId.startsWith("france-") || spotId.startsWith("corsica-") -> "france"
            spotId.startsWith("aus-") -> "australia"
            spotId.startsWith("tahiti-") || spotId.startsWith("fakarava-") ||
            spotId.startsWith("rangiroa-") || spotId.startsWith("moorea-") -> "tahiti"
            else -> "worldwide"
        }
    }
    
    /**
     * Infer country from spot ID for regulatory lookup.
     */
    private fun inferCountryFromSpotId(spotId: String): String {
        return when {
            spotId.startsWith("oahu-") || spotId.startsWith("maui-") || 
            spotId.startsWith("bigisland-") || spotId.startsWith("kauai-") ||
            spotId.startsWith("molokai-") || spotId.startsWith("lanai-") ||
            spotId.startsWith("keys-") || spotId.startsWith("fl-") ||
            spotId.startsWith("cali-") -> "USA"
            spotId.contains("bahamas") || spotId.startsWith("andros-") || 
            spotId.startsWith("exuma-") || spotId.startsWith("bimini-") ||
            spotId.startsWith("nassau-") || spotId.startsWith("eleuthera-") ||
            spotId.startsWith("abaco-") || spotId.startsWith("cat-") ||
            spotId.startsWith("berry-") || spotId.startsWith("rum-") ||
            spotId.startsWith("long-") || spotId.startsWith("grand-") ||
            spotId.startsWith("salvador-") || spotId.startsWith("conception-") ||
            spotId.startsWith("crooked-") || spotId.startsWith("acklins-") -> "Bahamas"
            spotId.startsWith("cayman-") -> "Cayman"
            spotId.startsWith("bvi-") || spotId.startsWith("tortola-") -> "BVI"
            spotId.startsWith("usvi-") || spotId.startsWith("stthomas-") ||
            spotId.startsWith("stcroix-") || spotId.startsWith("stjohn-") -> "USVI"
            spotId.startsWith("italy-") || spotId.startsWith("sardinia-") ||
            spotId.startsWith("sicily-") -> "Italy"
            spotId.startsWith("france-") || spotId.startsWith("corsica-") -> "France"
            spotId.startsWith("aus-") -> "Australia"
            spotId.startsWith("tahiti-") || spotId.startsWith("fakarava-") ||
            spotId.startsWith("rangiroa-") || spotId.startsWith("moorea-") ||
            spotId.startsWith("bora-") -> "FrenchPolynesia"
            spotId.startsWith("fiji-") -> "Fiji"
            spotId.startsWith("mexico-") || spotId.startsWith("cozumel-") ||
            spotId.startsWith("cancun-") || spotId.startsWith("guadalupe-") -> "Mexico"
            spotId.startsWith("bonaire-") -> "Bonaire"
            spotId.startsWith("curacao-") -> "Curacao"
            spotId.startsWith("aruba-") -> "Aruba"
            else -> "Unknown"
        }
    }
    
    /**
     * Infer specific region (state/island/area) from spot ID for regulatory lookup.
     */
    private fun inferSpecificRegionFromSpotId(spotId: String): String {
        return when {
            spotId.startsWith("oahu-") -> "Oahu"
            spotId.startsWith("maui-") -> "Maui"
            spotId.startsWith("bigisland-") -> "Bigisland"
            spotId.startsWith("kauai-") -> "Kauai"
            spotId.startsWith("molokai-") -> "Molokai"
            spotId.startsWith("lanai-") -> "Lanai"
            spotId.startsWith("keys-") -> "Keys"
            spotId.startsWith("fl-") -> "Florida"
            spotId.startsWith("cali-") -> "California"
            spotId.startsWith("andros-") -> "Andros"
            spotId.startsWith("exuma-") -> "Exuma"
            spotId.startsWith("bimini-") -> "Bimini"
            spotId.startsWith("nassau-") -> "Nassau"
            spotId.startsWith("cayman-") -> "Cayman"
            spotId.startsWith("bvi-") || spotId.startsWith("tortola-") -> "Bvi"
            spotId.startsWith("usvi-") || spotId.startsWith("stthomas-") ||
            spotId.startsWith("stcroix-") || spotId.startsWith("stjohn-") -> "Usvi"
            spotId.startsWith("sardinia-") -> "Sardinia"
            spotId.startsWith("sicily-") -> "Sicily"
            spotId.startsWith("corsica-") -> "Corsica"
            spotId.startsWith("aus-") -> "Aus"
            spotId.startsWith("tahiti-") -> "Tahiti"
            spotId.startsWith("moorea-") -> "Moorea"
            spotId.startsWith("bora-") -> "Bora"
            spotId.startsWith("rangiroa-") -> "Rangiroa"
            spotId.startsWith("fakarava-") -> "Fakarava"
            spotId.startsWith("fiji-") -> "Fiji"
            spotId.startsWith("cozumel-") -> "Cozumel"
            spotId.startsWith("cancun-") -> "Cancun"
            spotId.startsWith("bonaire-") -> "Bonaire"
            spotId.startsWith("curacao-") -> "Curacao"
            spotId.startsWith("aruba-") -> "Aruba"
            else -> spotId.substringBefore("-").replaceFirstChar { it.uppercase() }
        }
    }


    /**
     * Get regulatory information for a spot based on its region and country.
     * Combines regulatory links from JSON config with MPA data from cache.
     */
    private fun getRegulationInfo(spotId: String, region: String, country: String): RegulationInfo? {
        // Get MPA data from cache
        val cached = SpotDataCache.get(spotId)
        val mpaCache = cached?.mpa?.value
        val mpaChecked = cached?.mpa != null  // true when mpa_fetched_at is NOT NULL (fetch was attempted)
        
        // Build MPA status from cache
        val mpaStatus = mpaCache?.let {
            MPAStatus(
                isProtected = it.spearfishingStatus in 1..2,  // Prohibited or Restricted
                isInsideMPA = it.isInsideMPA,
                siteName = it.siteName,
                designation = it.designation,
                spearfishingStatus = it.spearfishingStatus,
                protectionLevel = it.protectionLevel,
                speciesOfConcern = it.speciesOfConcern,
                purpose = it.purpose,
                detailsUrl = it.detailsUrl
            )
        }
        
        // Get regulatory links from JSON config
        val links = regulatoryLinks?.jsonObject ?: return RegulationInfo(
            regulatoryAgency = "Local Fisheries Authority",
            regulationsUrl = "https://navigatormap.org/",
            mpaStatus = mpaStatus,
            mpaChecked = mpaChecked
        )
        
        // Region names can come from spot IDs ("Keys") or coordinate inference
        // ("French Polynesia"); strip spaces so both match JSON keys like "FrenchPolynesia".
        val normalizedRegion = region.replace(" ", "")

        // Try to find region-specific info
        // First try country -> region (e.g., USA -> Hawaii)
        val countryLinks = links[country]?.jsonObject
        val regionLinks = countryLinks?.get(normalizedRegion)?.jsonObject
            ?: countryLinks?.entries?.firstOrNull { (_, value) ->
                value is JsonObject && value["regions"]?.toString()?.contains(normalizedRegion, ignoreCase = true) == true
            }?.value?.jsonObject
        
        // Handle flat country structures (e.g., Bahamas) where regions array is directly on the country object
        if (regionLinks == null && countryLinks != null && 
            countryLinks["agency"] != null &&
            countryLinks["regions"]?.jsonArray?.any { 
                it.jsonPrimitive.content.contains(normalizedRegion, ignoreCase = true) 
            } == true) {
            return RegulationInfo(
                regulatoryAgency = countryLinks["agency"]?.jsonPrimitive?.content ?: "Fisheries Authority",
                regulationsUrl = countryLinks["url"]?.jsonPrimitive?.content ?: "https://navigatormap.org/",
                licensingUrl = countryLinks["licensingUrl"]?.jsonPrimitive?.content,
                licenseRequirement = countryLinks["licenseRequirement"]?.jsonPrimitive?.content,
                note = countryLinks["note"]?.jsonPrimitive?.content,
                mpaStatus = mpaStatus,
                mpaChecked = mpaChecked
            )
        }
        
        if (regionLinks != null) {
            return RegulationInfo(
                regulatoryAgency = regionLinks["agency"]?.jsonPrimitive?.content ?: "Fisheries Authority",
                regulationsUrl = regionLinks["url"]?.jsonPrimitive?.content ?: "https://navigatormap.org/",
                licensingUrl = regionLinks["licensingUrl"]?.jsonPrimitive?.content,
                licenseRequirement = regionLinks["licenseRequirement"]?.jsonPrimitive?.content,
                note = regionLinks["note"]?.jsonPrimitive?.content,
                mpaStatus = mpaStatus,
                mpaChecked = mpaChecked
            )
        }
        
        // Try direct region match in other sections (Caribbean, Pacific, etc.)
        for ((section, sectionData) in links) {
            if (section == "default") continue
            val sectionObj = try { sectionData.jsonObject } catch (e: Exception) { continue }
            
            // Check if this section directly matches the country.
            // Require an "agency" field so grouping sections (e.g. "Caribbean",
            // which only holds subsections) don't produce an empty match.
            if (section.equals(country, ignoreCase = true) && sectionObj["agency"] != null) {
                return RegulationInfo(
                    regulatoryAgency = sectionObj["agency"]?.jsonPrimitive?.content ?: "Fisheries Authority",
                    regulationsUrl = sectionObj["url"]?.jsonPrimitive?.content ?: "https://navigatormap.org/",
                    licensingUrl = sectionObj["licensingUrl"]?.jsonPrimitive?.content,
                    licenseRequirement = sectionObj["licenseRequirement"]?.jsonPrimitive?.content,
                    note = sectionObj["note"]?.jsonPrimitive?.content,
                    mpaStatus = mpaStatus,
                    mpaChecked = mpaChecked
                )
            }
            
            // Check subsections
            for ((subRegion, subData) in sectionObj) {
                val subObj = try { subData.jsonObject } catch (e: Exception) { continue }
                val regions = subObj["regions"]?.toString() ?: ""
                if (regions.contains(normalizedRegion, ignoreCase = true) || 
                    subRegion.equals(normalizedRegion, ignoreCase = true)) {
                    return RegulationInfo(
                        regulatoryAgency = subObj["agency"]?.jsonPrimitive?.content ?: "Fisheries Authority",
                        regulationsUrl = subObj["url"]?.jsonPrimitive?.content ?: "https://navigatormap.org/",
                        licensingUrl = subObj["licensingUrl"]?.jsonPrimitive?.content,
                        licenseRequirement = subObj["licenseRequirement"]?.jsonPrimitive?.content,
                        note = subObj["note"]?.jsonPrimitive?.content,
                        mpaStatus = mpaStatus,
                        mpaChecked = mpaChecked
                    )
                }
            }
        }
        
        // Fallback to default
        val defaultLinks = links["default"]?.jsonObject
        return RegulationInfo(
            regulatoryAgency = defaultLinks?.get("message")?.jsonPrimitive?.content ?: "Local Fisheries Authority",
            regulationsUrl = defaultLinks?.get("url")?.jsonPrimitive?.content ?: "https://navigatormap.org/",
            mpaStatus = mpaStatus,
            mpaChecked = mpaChecked
        )
    }
    
    /**
     * Determine best time of day based on moon phase.
     */
    private fun getBestTimeOfDay(moonPhase: String?): String {
        return when (moonPhase) {
            "new_moon" -> "First light (5:30am-8am) or dusk"
            "full_moon" -> "Early morning or late afternoon"
            else -> "6am-10am"
        }
    }

    /**
     * Public: resolve the visibility label from cached data only (no API calls).
     * Used by SpotRoutes for lightweight endpoints like /spots/all.
     */
    fun resolveVisibilityLabel(cached: SpotDataCache.SpotData?): String {
        val chl = resolveChlorophyll(null, cached?.chlorophyll?.value, cached?.gibsChlorophyll?.value)
        return getVisibilityLabel(chl)
    }

    /**
     * Resolve the best available chlorophyll value using the same fallback chain
     * as the Flutter SatelliteReadingsCard: fresh Copernicus ? cached Copernicus
     * value ? GIBS blended color estimate. The result drives BOTH the visibility
     * score and the visibility label.
     */
    private fun resolveChlorophyll(
        copernicusChl: Double?,
        // Cached chlorophyll is ALSO Copernicus (the old "Erddap" name was a
        // leftover from a source that was replaced).
        cachedChlorophyllMgM3: Double?,
        gibsData: SpotDataCache.GIBSSatelliteData?
    ): Double? {
        return copernicusChl
            ?: cachedChlorophyllMgM3
            ?: GibsColormap.estimateFromGibsColors(gibsData)
    }

    /**
     * Get visibility display string from chlorophyll concentration.
     * Uses the same thresholds as the scorer so the label matches the score.
     */
    private fun buildSwellConditionFields(cached: SpotDataCache.SpotData?): SwellConditionFields {
        val swell = cached?.swell?.value
        val exposure = cached?.exposure
        return SwellConditionFields(
            swellCorrected = swell?.correctedHeightFt?.let { "${it.roundToInt()}ft @ ${swell.periodSec.roundToInt()}s ${swell.direction}" },
            secondarySwell = swell?.secondaryHeightFt?.let { ht ->
                if (ht >= 0.5) "${ht.roundToInt()}ft @ ${swell.secondaryPeriodSec?.roundToInt() ?: 0}s ${swell.secondaryDirection ?: ""}" else null
            },
            secondarySwellCorrected = swell?.secondaryCorrectedHeightFt?.let { ht ->
                if (ht >= 0.5) "${ht.roundToInt()}ft @ ${swell.secondaryPeriodSec?.roundToInt() ?: 0}s ${swell.secondaryDirection ?: ""}" else null
            },
            exposureBearing = exposure?.bearing,
            exposureWidth = exposure?.width,
            bathymetryDepthM = exposure?.depthM
        )
    }

    private data class SwellConditionFields(
        val swellCorrected: String?,
        val secondarySwell: String?,
        val secondarySwellCorrected: String?,
        val exposureBearing: Int?,
        val exposureWidth: Int?,
        val bathymetryDepthM: Double?
    )

    private fun getVisibilityLabel(chl: Double?): String {
        if (chl == null) return "No satellite data"
        return when {
            chl < 0.1  -> "Crystal clear"
            chl < 0.3  -> "Blue water"
            chl < 0.5  -> "Slight haze"
            chl < 1.0  -> "Green tint"
            chl < 3.0  -> "Murky"
            chl < 5.0  -> "Can't see your fins"
            chl < 10.0 -> "Can't see your hand"
            else       -> "Zero vis"
        }
    }

    /**
     * Resolve water temp from real measurements only: cached spot SST, else
     * nearest-neighbor original within 25km. Returns null when nothing real
     * exists ? the climatology-estimate tier was removed (Q2?4: real value or
     * "Unavailable", never invented).
     */
    private fun resolveSST(cachedSST: Double?, lat: Double, lon: Double): Double? {
        cachedSST?.let { return it }
        return SpotDataCache.findNearestSST(lat, lon)?.value
    }

    private fun formatWaterTemp(sstCelsius: Double?): String {
        if (sstCelsius == null) return "Unavailable"
        val f = ((sstCelsius * 9.0 / 5) + 32).toInt()
        return "${sstCelsius.toInt()}?C / ${f}?F"
    }

    private fun generateGearRecs(waterTempC: Double?, depthM: Int): List<String> {
        val recs = mutableListOf<String>()

        recs += when {
            waterTempC == null -> "5mm+ wetsuit (water temp unavailable)"
            waterTempC >= 26 -> "Rashguard or 1mm suit"
            waterTempC >= 23 -> "3mm wetsuit"
            waterTempC >= 20 -> "5mm wetsuit"
            else -> "7mm wetsuit with hood"
        }

        // Speargun vs pole spear
        recs += if (depthM > 10) "Speargun (90-110cm)" else "Pole spear or Hawaiian sling"

        // Standard gear
        recs += "Mask and snorkel"
        recs += "Fins"
        recs += "Weight belt"
        recs += "Dive knife"
        recs += "Float and flag"
        recs += "Gloves"

        return recs
    }

    private fun generateRisks(weather: WeatherData?, ocean: OceanData?): List<String> {
        if (weather == null && ocean == null) {
            return listOf("Conditions data unavailable - check local sources before diving")
        }
        val risks = mutableListOf<String>()

        if ((weather?.windSpeed ?: 0.0) > 15) risks += "Strong winds expected"
        if (ocean != null && ocean.waveHeight > 1.5) risks += "Rough surf conditions"
        if ((weather?.precipitation ?: 0.0) > 2) risks += "Rain may reduce visibility"
        // Skip the cold-water risk when temp is unknown ? never score against a
        // fabricated default (previously a hardcoded 15?C tripped this).
        val waterTemp = ocean?.waterTemperature
        if (waterTemp != null && waterTemp < 20) risks += "Cold water - hypothermia risk"

        if (risks.isEmpty()) risks += "No significant risks identified"

        return risks
    }
    
    /**
     * Build water context with chlorophyll trends and SST at nearby points.
     * Allows fishermen to spot temperature breaks and plankton blooms.
     */
    private fun buildWaterContext(
        cached: SpotDataCache.SpotData?,
        waterQuality: WaterQuality,
        lat: Double,
        lon: Double
    ): WaterContext? {
        val chlorophyllContext = buildChlorophyllContext(cached, waterQuality)
        val sstNearby = buildSSTNearby(cached, lat, lon)
        
        // Return null if no data available
        if (chlorophyllContext == null && sstNearby.isNullOrEmpty()) {
            return null
        }
        
        return WaterContext(
            chlorophyll = chlorophyllContext,
            sstNearby = sstNearby
        )
    }
    
    /**
     * Build chlorophyll context with current value and trend.
     * Compares current reading to 7-day average from GIBS satellite data.
     */
    private fun buildChlorophyllContext(
        cached: SpotDataCache.SpotData?,
        waterQuality: WaterQuality
    ): ChlorophyllContext? {
        // Get current chlorophyll from NOAA ERDDAP (trusted source)
        // Note: We removed GIBS-derived chlorophyll values as they were unreliable in coastal areas
        val current = cached?.chlorophyll?.value ?: waterQuality.chlorophyllA ?: return null
        
        // Without historical data from multiple days, we use current as both values
        // In the future, we could store historical NOAA ERDDAP readings for trend analysis
        val avg7day = current
        
        // Cannot determine trend without historical data
        val trend = "stable"
        
        return ChlorophyllContext(
            current = current,
            avg7day = avg7day,
            trend = trend
        )
    }
    
    /**
     * Build SST readings at nearby points to detect temperature breaks.
     * Uses cached SST data or estimates from marine data.
     * 
     * Temperature breaks (where warm and cold water meet) are where fish congregate.
     */
    private fun buildSSTNearby(
        cached: SpotDataCache.SpotData?,
        lat: Double,
        lon: Double
    ): List<SSTReading>? {
        // Get SST at the spot
        val centerSST = cached?.sst?.value ?: return null
        
        // For now, we don't have nearby readings in cache
        // In a full implementation, we'd fetch SST at N/S/E/W points during prefetch
        // For now, return null - this will be populated when we add SST nearby to prefetch
        
        // TODO: Add SST nearby readings to prefetch jobs
        // This would involve:
        // 1. During prefetch, fetch SST at 4 points: 5nm N, S, E, W of spot
        // 2. Store in cache as part of CachedSpotData
        // 3. Return those readings here
        
        return null
    }

    // ============================================
    // USER SPOT METHODS
    // ============================================

    /**
     * Get detailed information for a user-created spot.
     * Similar to getSpotDetail but works with UserSpotRecord instead of in-memory spot.
     * 
     * @param userSpot The user spot record from the database
     * @param cacheId The cache ID for this spot (prefixed with "user-")
     * @param date The date for conditions lookup
     * @return SpotDetail with full conditions, or null on error
     */
    suspend fun getUserSpotDetail(
        userSpot: UserSpotRepository.UserSpotRecord,
        cacheId: String,
        date: String
    ): SpotDetail = coroutineScope {
        val lat = userSpot.coordinates.lat
        val lon = userSpot.coordinates.lon
        val region = userSpot.region
        
        val cached = SpotDataCache.get(cacheId)
        // Serve wind from the prefetched cache so the detail loads instantly; the
        // client fetches near-real-time wind after paint via /spots/{id}/wind/live.
        val effectiveWind = cached?.wind
        logger.debug("Building detail from cache for ${userSpot.name} (cache ${if (cached != null) "hit" else "miss"})")
        
        val weather: WeatherData? = if (cached?.wind != null) {
            WeatherData(
                temperature = null,
                windSpeed = cached.wind.value.speedKnots / 0.539957,  // knots back to km/h for the scorer
                windDirection = null,  // cache carries only the cardinal; do not fabricate 0 deg
                precipitation = null,
                cloudCover = null,
                visibility = null
            )
        } else {
            null
        }
        
        val ocean: OceanData? = if (cached?.swell != null) {
            val htM = (cached.swell.value.correctedHeightFt ?: cached.swell.value.heightFt) / 3.28084
            OceanData(
                waveHeight = htM,
                wavePeriod = cached.swell.value.periodSec,
                waveDirection = 0,
                waterTemperature = cached.sst?.value,
                swellHeight = htM,
                swellDirection = 0
            )
        } else {
            null
        }
        
        val waterQuality = WaterQuality(
            chlorophyllA = cached?.chlorophyll?.value,
            visibility = cached?.visibility?.value,
            seaSurfaceTemp = cached?.sst?.value,
            dataSource = if (cached?.tide != null) "Prefetched (updated ${cached.tide.ageString()})" else "Loading..."
        )
        
        // Derive the now-state on read from the persisted chart (no FES);
        // fall back to the cached snapshot only if today's chart is missing.
        val tideData = deriveTideData(cacheId, lon)
            ?: if (cached?.tide != null) {
                TideData(
                    currentHeight = cached.tide.value.currentHeight,
                    nextHighTide = cached.tide.value.nextHighTide,
                    nextLowTide = cached.tide.value.nextLowTide,
                    tideState = cached.tide.value.state,
                    nextHighTideTime = cached.tide.value.nextHighTideTime?.toEpochMilli(),
                    nextLowTideTime = cached.tide.value.nextLowTideTime?.toEpochMilli()
                )
            } else {
                TideData(0.0, "Loading...", "Loading...", "unknown")
            }
        
        // Forecast is lazy-loaded by the client via /forecast/{spotId} when user taps Forecast tab

        logger.info("User spot detail loaded from cache: ${userSpot.name}")

        val effectiveChl = resolveChlorophyll(
            waterQuality?.chlorophyllA, cached?.chlorophyll?.value, cached?.gibsChlorophyll?.value
        )

        val score = ShakaScorer.generateScore(
            targetDate = date,
            windSpeedKmh = weather?.windSpeed,
            waveHeightM = ocean?.waveHeight,
            chlorophyllMgM3 = effectiveChl,
            solunarDayRating = cached?.solunar?.value?.dayRating,
            moonPhase = cached?.solunar?.value?.moonPhase
        )
        
        val sst = resolveSST(cached?.sst?.value, lat, lon)
        
        // Data freshness from cache (wind-anchored; see getSpotSummaries).
        val dataUpdatedMinutesAgo = cached?.wind?.minutesSinceFetch()?.toInt()
        val satelliteDataDate = cached?.sst?.dataDateString()

        // Build satellite readings - colors for display, NOAA ERDDAP for actual chlorophyll
        val gibsReadings = if (cached?.gibsChlorophyll != null || cached?.chlorophyll != null) {
            val gibs = cached.gibsChlorophyll?.value
            GibsSatelliteReadings(
                paceTodayColor = gibs?.paceTodayColor,
                paceYesterdayColor = gibs?.paceYesterdayColor,
                noaa20TodayColor = gibs?.noaa20TodayColor,
                noaa20YesterdayColor = gibs?.noaa20YesterdayColor,
                noaa21TodayColor = gibs?.noaa21TodayColor,
                noaa21YesterdayColor = gibs?.noaa21YesterdayColor,
                sentinel3aTodayColor = gibs?.sentinel3aTodayColor,
                sentinel3aYesterdayColor = gibs?.sentinel3aYesterdayColor,
                sentinel3bTodayColor = gibs?.sentinel3bTodayColor,
                sentinel3bYesterdayColor = gibs?.sentinel3bYesterdayColor,
                // Observation times
                paceObservationTime = gibs?.paceObservationTime?.toString(),
                noaa20ObservationTime = gibs?.noaa20ObservationTime?.toString(),
                noaa21ObservationTime = gibs?.noaa21ObservationTime?.toString(),
                dataDate = gibs?.dataDate?.toString(),
                // ACTUAL measured chlorophyll from NOAA ERDDAP (the trusted source)
                chlorophyllMgM3 = cached.chlorophyll?.value,
                noaaErddapFetchTime = cached.chlorophyll?.fetchedAt?.toString()
            )
        } else null

        // MPA status is built inside getRegulationInfo from the same cache entry.
        val tideChart = loadTideChartData(cacheId, lon)

        SpotDetail(
            id = cacheId,
            name = userSpot.name,
            description = "Custom saved spot in ${userSpot.region}",
            coordinates = userSpot.coordinates,
            score = score,
            access = AccessInfo(
                directions = "User-saved location",
                parkingInfo = "Check locally",
                permitRequired = false
            ),
            conditions = SpotConditions(
                visibility = getVisibilityLabel(effectiveChl),
                waterTemp = formatWaterTemp(sst),
                swell = cached?.swell?.let { 
                    "${it.value.heightFt.roundToInt()}ft @ ${it.value.periodSec.roundToInt()}s ${it.value.direction}" 
                } ?: ocean?.let { "${it.waveHeight.roundToInt()}-${(it.waveHeight + 1).roundToInt()}ft @ ${it.wavePeriod.roundToInt()}s" }
                  ?: "Unavailable",
                wind = effectiveWind?.let { 
                    SpotDataCache.formatWindLabel(it.value.speedKnots, it.value.direction) 
                } ?: weather?.windSpeed?.let { ws -> weather.windDirection?.let { wd -> SpotDataCache.formatWindLabel(SpotDataCache.kmhToKnots(ws), SpotDataCache.degreesToCardinal(wd.toDouble())) } }
                  ?: "Unavailable",
                tideState = buildTideStateString(tideChart, tideData),
                dataUpdatedMinutesAgo = dataUpdatedMinutesAgo,
                satelliteDataDate = satelliteDataDate,
                swellSource = cached?.swell?.value?.source,
                swellCorrected = buildSwellConditionFields(cached).swellCorrected,
                secondarySwell = buildSwellConditionFields(cached).secondarySwell,
                secondarySwellCorrected = buildSwellConditionFields(cached).secondarySwellCorrected,
                exposureBearing = cached?.exposure?.bearing,
                exposureWidth = cached?.exposure?.width,
                bathymetryDepthM = cached?.exposure?.depthM,
                swellHeightFt = cached?.swell?.value?.let { (it.correctedHeightFt ?: it.heightFt).roundToInt().toDouble() }
                    ?: ocean?.let { SpotDataCache.metersToFeet(it.waveHeight).roundToInt().toDouble() },
                swellPeriodSec = cached?.swell?.value?.periodSec?.roundToInt()?.toDouble()
                    ?: ocean?.wavePeriod?.roundToInt()?.toDouble(),
                swellDirection = cached?.swell?.value?.direction
                    ?: ocean?.let { SpotDataCache.degreesToCardinal(it.waveDirection.toDouble()) },
                windSpeedKts = effectiveWind?.value?.speedKnots
                    ?: weather?.windSpeed?.let { SpotDataCache.kmhToKnots(it) },
                windDirectionCardinal = effectiveWind?.value?.direction
                    ?: weather?.windDirection?.let { SpotDataCache.degreesToCardinal(it.toDouble()) },
                waterTempC = sst,
                swellRetrievedAt = cached?.swell?.fetchedAt?.toEpochMilli(),
                windRetrievedAt = effectiveWind?.fetchedAt?.toEpochMilli(),
                windDirectionDeg = effectiveWind?.value?.directionDeg ?: weather?.windDirection,
                windValidAt = effectiveWind?.dataValidAt?.toEpochMilli(),
                windKind = if (effectiveWind != null || weather?.windSpeed != null) "hourlyForecast" else null,
                windSource = if (effectiveWind != null || weather?.windSpeed != null) "open-meteo" else null
            ),
            forecast = emptyList(),
            gearRecommendations = generateGearRecs(sst, 10).map { item ->
                GearItem(item = item, reason = "Recommended for conditions", essential = true)
            },
            risks = generateRisks(weather, ocean).map { risk ->
                RiskInfo(risk = risk, severity = "moderate", mitigation = "Check conditions before entry")
            },
            communityReports = emptyList(),
            bestTimeOfDay = getBestTimeOfDay(cached?.solunar?.value?.moonPhase),
            imageUrl = null,
            satelliteReadings = gibsReadings,
            regulations = run {
                // Same regulatory lookup as curated spots, but keyed on the spot's
                // coordinates (user spots have no meaningful spot-ID prefix).
                // Recomputed at read time so old rows benefit from mapping updates.
                val (regRegion, regCountry) = UserSpotRepository.inferRegionAndCountry(lat, lon)
                getRegulationInfo(cacheId, regRegion, regCountry)
            },
            tide = tideChart
        )
    }
    
    /**
     * Quick score calculation for user spot list view.
     * Uses cached data only - no API calls. Returns null if no cached data available.
     */
    fun getUserSpotScore(cacheId: String): Int? {
        val cached = SpotDataCache.get(cacheId) ?: return null
        
        // Require tide + swell + wind so the score reflects real data, not defaults
        if (cached.tide == null || cached.swell == null || cached.wind == null) return null
        
        val windSpeedKmh = cached.wind.value.speedKnots / 0.539957
        val waveHeightM = (cached.swell.value.correctedHeightFt ?: cached.swell.value.heightFt) / 3.28084
        
        val effectiveChl = resolveChlorophyll(null, cached.chlorophyll?.value, cached.gibsChlorophyll?.value)
        
        val today = LocalDate.now().toString()
        val score = ShakaScorer.generateScore(
            targetDate = today,
            windSpeedKmh = windSpeedKmh,
            waveHeightM = waveHeightM,
            chlorophyllMgM3 = effectiveChl,
            solunarDayRating = cached.solunar?.value?.dayRating,
            moonPhase = cached.solunar?.value?.moonPhase
        )
        
        return score.overall
    }

    /**
     * Prefetch all data for a single spot and save to cache.
     * Used after creating a new user spot to immediately populate the cache.
     * 
     * If a prefetch for this spot is already in flight, awaits the existing
     * one instead of starting a duplicate. This prevents the background
     * GlobalScope.launch prefetch and getUserSpotDetail from both running
     * the same expensive external API calls simultaneously.
     * 
     * @param spotId The cache ID for the spot
     * @param lat Latitude
     * @param lon Longitude
     */
    suspend fun prefetchSingleSpot(spotId: String, lat: Double, lon: Double) {
        SpotDataCache.registerSpotCoordinates(spotId, lat, lon)

        val existing = inFlightPrefetches[spotId]
        if (existing != null && existing.isActive) {
            logger.info("Prefetch already in flight for $spotId, awaiting existing")
            existing.await()
            return
        }

        val deferred = CompletableDeferred<Unit>()
        inFlightPrefetches[spotId] = deferred
        try {
            doPrefetchSingleSpot(spotId, lat, lon)
            deferred.complete(Unit)
        } catch (e: Exception) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            inFlightPrefetches.remove(spotId)
        }
    }

    private suspend fun doPrefetchSingleSpot(spotId: String, lat: Double, lon: Double) = coroutineScope {
        val localToday = spotLocalDate(lon)
        val today = localToday.toString()
        val now = Instant.now()
        
        logger.info("Prefetching data for spot $spotId at ($lat, $lon) localDate=$today")
        
        SpotDataCache.ensureRowExists(spotId)
        
        // Launch all fetches in parallel, writing to SpotDataCache as each completes.
        // This lets getUserSpotScore() return a score as soon as tide arrives (~2s)
        // instead of waiting for the slowest fetch (GIBS: up to 30s).
        
        val tideDeferred = async {
            withTimeoutOrNull(15000) {
                try {
                    val fesClient = tidesClient as? com.shaka.data.client.FES2022TideClient
                    val result = fesClient?.getChartWithSummary(lat, lon, today)
                    if (result != null) {
                        val (chartData, summaryData) = result

                        SpotDataCache.updateTide(spotId, SpotDataCache.CachedValue(
                            value = SpotDataCache.TideInfo(
                                state = summaryData.tideState, nextHighTide = summaryData.nextHighTide,
                                nextLowTide = summaryData.nextLowTide, currentHeight = summaryData.currentHeight,
                                nextHighTideTime = summaryData.nextHighTideTime?.let { java.time.Instant.ofEpochMilli(it) },
                                nextLowTideTime = summaryData.nextLowTideTime?.let { java.time.Instant.ofEpochMilli(it) }
                            ), fetchedAt = now
                        ))

                        val jsonEncoder = Json { ignoreUnknownKeys = true }
                        val localDate = chartData.localDate.ifEmpty { today }
                        SpotDataCache.upsertTideDay(SpotDataCache.TideDayRow(
                            spotId = spotId,
                            localDate = localDate,
                            provider = chartData.provider,
                            stationId = chartData.stationId,
                            stationName = chartData.stationName,
                            stationDistanceMi = chartData.stationDistanceMi,
                            timezoneId = chartData.timezoneId,
                            datum = chartData.datum,
                            pointsJson = jsonEncoder.encodeToString(ListSerializer(TidePoint.serializer()), chartData.points),
                            extremesJson = jsonEncoder.encodeToString(ListSerializer(TideExtreme.serializer()), chartData.extremes),
                            fetchedAt = now
                        ))
                        logger.info("Tide chart + summary persisted for $spotId (provider=${chartData.provider}, localDate=$localDate)")

                        // Fire-and-forget: materialize a full year so this new
                        // spot becomes a precomputed citizen like the catalog.
                        // Warm-minimal: today's chart is already persisted above
                        // for instant display; the year fills in the background
                        // and never blocks spot creation / scoring.
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                materializeTideYearForSpot(fesClient, spotId, lat, lon)
                            } catch (e: Exception) {
                                logger.warn("Full-year tide backfill failed for new spot $spotId: ${e.message}")
                            }
                        }

                        summaryData
                    } else {
                        tidesClient.getTideData(lat, lon, today)?.also { data ->
                            SpotDataCache.updateTide(spotId, SpotDataCache.CachedValue(
                                value = SpotDataCache.TideInfo(
                                    state = data.tideState, nextHighTide = data.nextHighTide,
                                    nextLowTide = data.nextLowTide, currentHeight = data.currentHeight,
                                    nextHighTideTime = data.nextHighTideTime?.let { java.time.Instant.ofEpochMilli(it) },
                                    nextLowTideTime = data.nextLowTideTime?.let { java.time.Instant.ofEpochMilli(it) }
                                ), fetchedAt = now
                            ))
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Tide fetch failed for $spotId: ${e.message}")
                    null
                }
            }
        }

        val exposureDeferred = async {
            val existing = SpotDataCache.get(spotId)?.exposure
            if (existing != null && existing.landDistances != null) {
                if (existing.depthM == null || existing.depthSource != "ncei") {
                    try {
                        val dr = withTimeoutOrNull(15000) { bathymetryClient.fetchDepthOnly(lat, lon) }
                        if (dr != null) {
                            val updated = existing.copy(depthM = dr.depthM, depthSource = dr.source)
                            SpotDataCache.updateExposure(spotId, updated)
                            return@async updated
                        }
                    } catch (e: Exception) {
                        logger.warn("Depth-only refresh failed for $spotId: ${e.message}")
                    }
                }
                return@async existing
            }
            try {
                val result = withTimeoutOrNull(30000) { bathymetryClient.computeExposure(lat, lon) }
                if (result != null) {
                    val info = SpotDataCache.ExposureInfo(
                        result.bearing, result.width, result.depthM,
                        result.directional.landDistanceKm, result.depthSource
                    )
                    SpotDataCache.updateExposure(spotId, info)
                    info
                } else null
            } catch (e: Exception) {
                logger.warn("Exposure compute failed for $spotId: ${e.message}")
                null
            }
        }
        
        val weatherDeferred = async {
            val data = withTimeoutOrNull(10000) {
                try {
                    val ocean = openMeteo.getMarineData(lat, lon, today)
                    val weather = openMeteo.getWeather(lat, lon, today)
                    if (ocean != null && weather != null) ocean to weather else null
                }
                catch (e: Exception) { logger.warn("Weather fetch failed for $spotId: ${e.message}"); null }
            }
            if (data != null) {
                val (ocean, weather) = data
                val exposure = exposureDeferred.await()
                
                // Option D: Open-Meteo primary, buoy only at < 1.5nm
                val buoyMatch = SpotDataCache.findNearestBuoyReading(lat, lon)
                val rawHeightFt: Double
                val rawDirectionDeg: Double
                val swellSource: String
                val periodSec: Double
                val directionCardinal: String
                val usedBuoy: Boolean
                
                if (buoyMatch != null) {
                    rawHeightFt = SpotDataCache.metersToFeet(buoyMatch.reading.waveHeightM!!)
                    periodSec = buoyMatch.reading.dominantPeriodSec ?: ocean.wavePeriod
                    rawDirectionDeg = (buoyMatch.reading.meanDirection ?: ocean.waveDirection).toDouble()
                    directionCardinal = SpotDataCache.degreesToCardinal(rawDirectionDeg)
                    swellSource = "ndbc-${buoyMatch.station.stationId}"
                    usedBuoy = true
                    logger.info("Using buoy ${buoyMatch.station.stationId} data for spot $spotId (${buoyMatch.reading.waveHeightM}m, ${buoyMatch.distanceNm.toInt()}nm away)")
                } else {
                    rawHeightFt = SpotDataCache.metersToFeet(ocean.swellHeight)
                    periodSec = if (ocean.swellPeriod > 0) ocean.swellPeriod else ocean.wavePeriod
                    rawDirectionDeg = ocean.swellDirection.toDouble()
                    directionCardinal = SpotDataCache.degreesToCardinal(rawDirectionDeg)
                    swellSource = "open-meteo"
                    usedBuoy = false
                }

                // ocean.swellHeight is the legacy non-null field, which falls back
                // to 0.5m when the provider omits swell. That placeholder is fine
                // for the display pipeline but must be flagged, or a habitat model
                // downstream would score a fabricated 0.5m swell as a reading.
                val swellWasMeasured = usedBuoy || ocean.rawSwellHeightM != null
                if (!swellWasMeasured) {
                    logger.warn(
                        "Spot $spotId: provider returned no swell height; caching the " +
                            "0.5m display placeholder as unmeasured so PFZ will not score it"
                    )
                }
                
                // Attenuation only for model data; buoy at < 1.5nm already reflects local conditions
                val ld = exposure?.landDistances
                val correctedHt = if (ld != null && !usedBuoy) {
                    SpotDataCache.attenuateSwell(
                        rawHeightFt, rawDirectionDeg, ld,
                        swellPeriodSec = periodSec,
                        totalWaveHeightM = ocean.waveHeight,
                        swellHeightM = ocean.swellHeight,
                        // Internal attenuation input only: missing wind = no
                        // wind-chop term (conservative), never shown to users.
                        windSpeedKmh = weather.windSpeed ?: 0.0,
                        windDirectionDeg = (weather.windDirection ?: 0).toDouble()
                    )
                } else null
                
                val secHtRaw = ocean.secondarySwellHeight?.let { SpotDataCache.metersToFeet(it) }
                val secPeriod = ocean.secondarySwellPeriod
                val secDirDeg = ocean.secondarySwellDirection?.toDouble()
                val secDirCardinal = secDirDeg?.let { SpotDataCache.degreesToCardinal(it) }
                val secCorrHt = if (ld != null && secHtRaw != null && secDirDeg != null) {
                    SpotDataCache.attenuateSwell(secHtRaw, secDirDeg, ld)
                } else null
                
                val swellInfo = SpotDataCache.SwellInfo(
                    heightFt = rawHeightFt,
                    periodSec = periodSec,
                    direction = directionCardinal,
                    swellHeightFt = rawHeightFt,
                    source = swellSource,
                    correctedHeightFt = correctedHt,
                    secondaryHeightFt = secHtRaw,
                    secondaryPeriodSec = secPeriod,
                    secondaryDirection = secDirCardinal,
                    secondaryCorrectedHeightFt = secCorrHt,
                    measured = swellWasMeasured
                )
                
                SpotDataCache.updateSwell(spotId, SpotDataCache.CachedValue(value = swellInfo, fetchedAt = now))
                SpotDataCache.updateSwellSource(spotId, swellInfo.source)
                SpotDataCache.updateCorrectedSwell(spotId, correctedHt, secHtRaw, secPeriod, secDirCardinal, secCorrHt)
                // Only update cached wind when the sample is complete; a gap
                // keeps the last-known value instead of writing a fabricated 0
                // (plan 15: provider outages must not erase or invent data).
                val ws = weather.windSpeed
                val wd = weather.windDirection
                if (ws != null && wd != null) {
                    SpotDataCache.updateWind(spotId, SpotDataCache.CachedValue(
                        value = SpotDataCache.WindInfo(
                            speedKnots = SpotDataCache.kmhToKnots(ws),
                            direction = SpotDataCache.degreesToCardinal(wd.toDouble()),
                            directionDeg = wd
                        ), fetchedAt = now
                    ))
                }
            }
            data
        }
        
        val sstDeferred = async {
            val data = withTimeoutOrNull(3000) {
                try { noaaClient.getSeaSurfaceTemperature(lat, lon, today) }
                catch (e: Exception) { logger.warn("NOAA SST fetch failed for $spotId: ${e.message}"); null }
            }
            // Failure/no-data keeps any last-known value (plan 15: a provider
            // outage must not erase data).
            if (data != null) {
                SpotDataCache.updateSST(spotId,
                    SpotDataCache.CachedValue(value = data, fetchedAt = now),
                    source = "satellite")
            }
            data
        }
        
        val satelliteDeferred = async {
            val data = withTimeoutOrNull(5000) {
                try { copernicus.getWaterQuality(lat, lon, today) }
                catch (e: Exception) { logger.warn("Satellite fetch failed for $spotId: ${e.message}"); null }
            }
            if (data != null) {
                data.visibility?.let { vis ->
                    SpotDataCache.updateVisibility(spotId, SpotDataCache.CachedValue(value = vis, fetchedAt = now))
                }
                data.chlorophyllA?.let { chl ->
                    SpotDataCache.updateChlorophyll(spotId, SpotDataCache.CachedValue(value = chl, fetchedAt = now))
                }
            }
            data
        }
        
        val gibsDeferred = async {
            val data = withTimeoutOrNull(30000) {
                try { GIBSClient.getAllSatelliteColors(lat, lon) }
                catch (e: Exception) { logger.warn("GIBS fetch failed for $spotId: ${e.message}"); null }
            }
            if (data != null) {
                SpotDataCache.updateGIBSChlorophyll(spotId, SpotDataCache.CachedValue(
                    value = SpotDataCache.GIBSSatelliteData(
                        paceTodayColor = data.paceTodayColor, paceYesterdayColor = data.paceYesterdayColor,
                        noaa20TodayColor = data.noaa20TodayColor, noaa20YesterdayColor = data.noaa20YesterdayColor,
                        noaa21TodayColor = data.noaa21TodayColor, noaa21YesterdayColor = data.noaa21YesterdayColor,
                        sentinel3aTodayColor = data.sentinel3aTodayColor, sentinel3aYesterdayColor = data.sentinel3aYesterdayColor,
                        sentinel3bTodayColor = data.sentinel3bTodayColor, sentinel3bYesterdayColor = data.sentinel3bYesterdayColor,
                        dataDate = data.dataDate, paceObservationTime = data.paceObservationTime,
                        noaa20ObservationTime = data.noaa20ObservationTime, noaa21ObservationTime = data.noaa21ObservationTime
                    ), fetchedAt = now
                ))
            }
            data
        }
        
        val mpaDeferred = async {
            withTimeoutOrNull(8000) {
                val exactMPA = try {
                    protectedSeasClient.getMPAStatusExact(lat, lon)
                } catch (e: Exception) {
                    logger.warn("Exact MPA check failed for $spotId: ${e.message}")
                    null
                }
                
                val bufferMPA = if (exactMPA == null) {
                    try {
                        protectedSeasClient.getMPAStatus(lat, lon)
                    } catch (e: Exception) {
                        logger.warn("Buffer MPA check failed for $spotId: ${e.message}")
                        null
                    }
                } else null
                
                val isInsideMPA = exactMPA != null
                val mpaData = exactMPA ?: bufferMPA
                
                val mpaCacheInfo = mpaData?.let {
                    SpotDataCache.MPACacheInfo(
                        siteName = it.siteName, designation = it.designation,
                        spearfishingStatus = it.spearfishingStatus, protectionLevel = it.protectionLevel,
                        speciesOfConcern = it.speciesOfConcern, purpose = it.purpose,
                        detailsUrl = it.detailsUrl, isInsideMPA = isInsideMPA
                    )
                }
                SpotDataCache.updateMPA(spotId, SpotDataCache.CachedValue(value = mpaCacheInfo, fetchedAt = now))
                mpaData
            }
        }
        
        // Await all results (cache writes already happened inside each async block)
        val tideData = tideDeferred.await()
        val weatherData = weatherDeferred.await()
        val satelliteData = satelliteDeferred.await()
        val sstData = sstDeferred.await()
        val gibsData = gibsDeferred.await()
        val mpaData = mpaDeferred.await()
        
        SpotDataCache.saveToDatabase(spotId)
        
        logger.info("Prefetch complete for spot $spotId - tide:${tideData != null}, weather:${weatherData != null}, satellite:${satelliteData != null}, gibs:${gibsData != null}, mpa:${mpaData != null}")
    }

}
