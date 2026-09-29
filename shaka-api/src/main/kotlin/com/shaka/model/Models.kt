package com.shaka.model

import kotlinx.serialization.Serializable

@Serializable
data class Coordinates(
    val lat: Double,
    val lon: Double
)

@Serializable
data class SpotConditions(
    val visibility: String,
    val waterTemp: String,
    val swell: String,
    val wind: String,
    val tideState: String = "",
    val dataUpdatedMinutesAgo: Int? = null,
    val satelliteDataDate: String? = null,
    val swellSource: String? = null,
    val swellCorrected: String? = null,
    val secondarySwell: String? = null,
    val secondarySwellCorrected: String? = null,
    val exposureBearing: Int? = null,
    val exposureWidth: Int? = null,
    val bathymetryDepthM: Double? = null,
    // Raw numeric fields for client-side unit conversion (SI/Imperial)
    val swellHeightFt: Double? = null,
    val swellPeriodSec: Double? = null,
    val swellDirection: String? = null,
    val windSpeedKts: Double? = null,
    val windDirectionCardinal: String? = null,
    val waterTempC: Double? = null,
    // Actual retrieval timestamps (epoch millis) for the Data Sources flyout.
    val swellRetrievedAt: Long? = null,
    val windRetrievedAt: Long? = null,
    // ---- Numeric wind contract (additive on /v1) ----
    // Clients should prefer these over the preformatted `wind` string and the
    // string-only cardinal (both kept for released apps; deprecated).
    /** Meteorological FROM bearing, degrees in [0, 360). */
    val windDirectionDeg: Int? = null,
    /** Epoch millis of the model hour this wind value represents (NOT fetch time). */
    val windValidAt: Long? = null,
    /** Reading kind: "live" | "hourlyForecast" | "dailySummary". */
    val windKind: String? = null,
    /** Upstream source id, e.g. "open-meteo". */
    val windSource: String? = null
)

@Serializable
data class ShakaScore(
    val overall: Int,
    val confidence: Int,
    val breakdown: ScoreBreakdown
)

@Serializable
data class ScoreBreakdown(
    val visibility: Int,
    val weather: Int,
    val swell: Int,
    val solunar: Int
)

@Serializable
data class SpotSummary(
    val id: String,
    val name: String,
    val coordinates: Coordinates,
    val shakaScore: Int,
    val confidence: Int,
    val conditions: SpotConditions,
    val gearRecommendations: List<String>,
    val risks: List<String>,
    val bestTimeOfDay: String,
    val satelliteReadings: GibsSatelliteReadings? = null  // Include cached GIBS data
)

@Serializable
data class SpotDetail(
    val id: String,
    val name: String,
    val description: String,
    val coordinates: Coordinates,
    val score: ShakaScore,
    val access: AccessInfo,
    val conditions: SpotConditions,
    val forecast: List<DayForecast>,
    val gearRecommendations: List<GearItem>,
    val risks: List<RiskInfo>,
    val communityReports: List<CommunityReport>,
    val bestTimeOfDay: String,
    val imageUrl: String? = null,
    val satelliteReadings: GibsSatelliteReadings? = null,
    val regulations: RegulationInfo? = null,
    // Raw intel data for fishermen to interpret
    val solunar: SolunarData? = null,         // Moon phase + feeding periods
    val waterContext: WaterContext? = null,   // Chlorophyll trend + SST breaks
    val tide: TideChartData? = null           // Structured tide chart data for today
)

@Serializable
data class RegulationInfo(
    val regulatoryAgency: String,           // e.g., "DLNR Division of Aquatic Resources"
    val regulationsUrl: String,             // Link to official regulations page
    val licensingUrl: String? = null,       // Link to licensing page (if separate)
    val licenseRequirement: String? = null, // "required" | "conditional" | "not_required" | "prohibited"; null = unknown
    val note: String? = null,               // e.g., "Spearfishing prohibited"
    val mpaStatus: MPAStatus? = null,       // From ProtectedSeas API
    val mpaChecked: Boolean = false         // true when mpa_fetched_at is NOT NULL in cache (MPA check was attempted)
)

@Serializable
data class MPAStatus(
    val isProtected: Boolean,
    val isInsideMPA: Boolean = false,       // True if spot is inside MPA boundary (not just nearby)
    val siteName: String? = null,           // e.g., "Hanauma Bay MLCD"
    val designation: String? = null,        // e.g., "Marine Life Conservation District"
    val spearfishingStatus: Int,            // 0=Allowed, 1=Prohibited, 2=Restricted, 3=Unknown
    val protectionLevel: Int,               // 1-5 Level of Fishing Protection
    val speciesOfConcern: String? = null,   // Protected species from ProtectedSeas
    val purpose: String? = null,            // Description of why area is protected
    val detailsUrl: String? = null          // navigator_link to full details
)

/**
 * Satellite imagery data for a spot.
 * 
 * IMPORTANT: The color fields are for DISPLAY ONLY - they show what the satellite captured
 * but may include sediment, kelp, or bottom reflectance in coastal areas.
 * 
 * For actual chlorophyll measurements, use chlorophyllMgM3 (Copernicus Marine).
 */
@Serializable
data class GibsSatelliteReadings(
    // Satellite imagery colors (display only - may include sediment/kelp contamination)
    val paceTodayColor: String? = null,            // RGB hex "#RRGGBB" from PACE satellite
    val paceYesterdayColor: String? = null,
    val noaa20TodayColor: String? = null,          // RGB hex from NOAA-20 VIIRS
    val noaa20YesterdayColor: String? = null,
    val noaa21TodayColor: String? = null,          // RGB hex from NOAA-21 VIIRS
    val noaa21YesterdayColor: String? = null,
    val sentinel3aTodayColor: String? = null,      // RGB hex from Sentinel-3A OLCI
    val sentinel3aYesterdayColor: String? = null,
    val sentinel3bTodayColor: String? = null,      // RGB hex from Sentinel-3B OLCI
    val sentinel3bYesterdayColor: String? = null,
    // Observation timestamps (when the satellite passed over this location)
    val paceObservationTime: String? = null,       // ISO 8601 timestamp
    val noaa20ObservationTime: String? = null,
    val noaa21ObservationTime: String? = null,
    val dataDate: String? = null,                  // The date that "today" refers to
    // Measured chlorophyll-a in mg/m³. Sourced from Copernicus Marine WMTS
    // (the removed "noaaErddapChlorophyll" field name was always misleading).
    val chlorophyllMgM3: Double? = null,
    val noaaErddapFetchTime: String? = null        // When we fetched this data
)

@Serializable
data class AccessInfo(
    val directions: String,
    val parkingInfo: String,
    val permitRequired: Boolean = false
)

@Serializable
data class DayForecast(
    val date: String,
    val shakaScore: Int,
    val confidence: Int,
    val conditions: SpotConditions
)

@Serializable
data class GearItem(
    val item: String,
    val reason: String,
    val essential: Boolean = false
)

@Serializable
data class RiskInfo(
    val risk: String,
    val severity: String,
    val mitigation: String
)

@Serializable
data class CommunityReport(
    val source: String,
    val date: String,
    val summary: String,
    val url: String? = null
)

@Serializable
data class SearchRequest(
    val lat: Double,
    val lon: Double,
    val radiusKm: Int = 50,
    val date: String
)

@Serializable
data class SearchResponse(
    val spots: List<SpotSummary>,
    val searchCenter: Coordinates,
    val radiusKm: Int,
    val date: String
)

@Serializable
data class SpotSearchResult(
    val id: String,
    val name: String,
    val region: String,
    val coordinates: Coordinates,
    val shakaScore: Int = 0
)

@Serializable
data class BatchSpotsResponse(
    val spots: List<SpotSummary>,
    val date: String,
    val fetchedAt: String
)

@Serializable
data class RegionInfo(
    val id: String,
    val name: String,
    val spotCount: Int,
    val centerLat: Double = 0.0,
    val centerLon: Double = 0.0
)

/**
 * Internal weather snapshot. Every field is nullable: null = genuinely
 * unavailable. Never substitute fabricated defaults (25°C / 10 km/h / 0° /
 * 50% cloud) — they rendered identical "real-looking" conditions across every
 * spot during the Jun 2026 outage and made missing wind look like ~5.4 kts.
 */
@Serializable
data class WeatherData(
    val temperature: Double?,
    val windSpeed: Double?,      // km/h
    val windDirection: Int?,     // degrees, meteorological FROM
    val precipitation: Double?,
    val cloudCover: Int?,
    val visibility: Double?
)

@Serializable
data class OceanData(
    val waveHeight: Double,
    val wavePeriod: Double,
    val waveDirection: Int,
    // Null when no real measurement exists (Q2-4: never a fabricated default).
    val waterTemperature: Double?,
    val swellHeight: Double,
    val swellDirection: Int,
    val swellPeriod: Double = 0.0,
    val rawSST: Double? = null,
    val secondarySwellHeight: Double? = null,
    val secondarySwellDirection: Int? = null,
    val secondarySwellPeriod: Double? = null,

    // ---------------------------------------------------------------
    // HONEST MEASUREMENTS
    //
    // The legacy fields above (waveHeight, wavePeriod, swellHeight, ...) are
    // non-null because the long-standing SoCal pipeline reads them directly in
    // ~40 call sites, and changing their type is a separate refactor. They
    // still carry historical fallback defaults (1.0 m wave, 0.5 m swell, ...)
    // applied by OpenMeteoClient when the upstream value is absent.
    //
    // Those defaults are fine for a qualitative "is it rough?" UI string, but
    // they are NOT acceptable as evidence in a habitat model: a fabricated
    // 0.5 m swell would read as a perfect, calm sea to a species profile.
    //
    // The fields below carry the true upstream value or null. Anything doing
    // evidence-based scoring (currently com.shaka.pfz) MUST use these and must
    // treat null as "unknown", never as a default.
    val rawWaveHeightM: Double? = null,
    val rawSwellHeightM: Double? = null,
    val rawSwellPeriodSec: Double? = null,

    /**
     * Ocean surface current speed in km/h.
     *
     * Open-Meteo's `ocean_current_velocity` was requested on the single-date
     * marine path but never mapped, and was not requested at all on the
     * date-range path, so this was structurally always null.
     */
    val oceanCurrentVelocityKmh: Double? = null
)

@Serializable
data class TideData(
    val currentHeight: Double,
    val nextHighTide: String,
    val nextLowTide: String,
    val tideState: String,
    val nextHighTideTime: Long? = null,  // Epoch millis for next high tide
    val nextLowTideTime: Long? = null    // Epoch millis for next low tide
)

@Serializable
data class TidePoint(
    val epochMs: Long,
    val heightFt: Double
)

@Serializable
data class TideExtreme(
    val epochMs: Long,
    val heightFt: Double,
    val type: String  // "H" or "L"
)

@Serializable
data class TideChartData(
    val provider: String,
    val stationId: String,
    val stationName: String,
    val stationDistanceMi: Double,
    val datum: String,
    val timezoneId: String,
    val points: List<TidePoint>,
    val extremes: List<TideExtreme>,
    val currentHeightFt: Double? = null,
    val currentStage: String? = null,
    val available: Boolean = true,
    val localDate: String = ""
)

/**
 * One hourly swell sample. Absolute time (epochMs) makes "now" selection
 * timezone-agnostic, exactly like TidePoint. correctedHeightFt is the
 * exposure-attenuated value precomputed for that hour.
 */
@Serializable
data class SwellHourlyPoint(
    val epochMs: Long,
    val heightFt: Double,
    val periodSec: Double,
    val directionDeg: Int,
    val correctedHeightFt: Double? = null,
    val secondaryHeightFt: Double? = null,
    val secondaryPeriodSec: Double? = null,
    val secondaryDirectionDeg: Int? = null,
    val secondaryCorrectedHeightFt: Double? = null
)

/**
 * One hourly wind sample. Absolute time (epochMs) for tz-agnostic "now"
 * selection, mirroring SwellHourlyPoint.
 */
@Serializable
data class WindHourlyPoint(
    val epochMs: Long,
    val speedKts: Double,
    val directionDeg: Int,
    val gustKts: Double? = null
)

/**
 * One spot-local day's worth of hourly swell + wind points. Days are grouped
 * server-side by the spot's timezone so the client never computes date
 * boundaries; days[0] is always the spot-local "today".
 */
@Serializable
data class SpotHourlyDay(
    val localDate: String,
    val swell: List<SwellHourlyPoint>,
    val wind: List<WindHourlyPoint>
)

/**
 * Full hourly swell + wind curves for a spot (intraday chart / "changes through
 * the day"), grouped by spot-local date. epochMs per point keeps each point
 * timezone-agnostic for "now" selection on the client.
 */
@Serializable
data class SpotHourlyResponse(
    val spotId: String,
    val timezoneId: String?,
    val days: List<SpotHourlyDay>,
    /**
     * When the underlying hourly series was fetched from Open-Meteo (epochMs).
     * Exposed for the synthetic monitor's probe-owned recency anchor
     * (docs/synthetic-monitor-design.md): the probe asserts this is < ~30h old
     * without trusting any backend threshold. Survives restarts (persisted as
     * fetched_at in spot_swell_hourly/spot_wind_hourly).
     */
    val generatedAt: Long? = null,
    /**
     * Spot's current UTC offset in minutes (additive; DST-aware, resolved at
     * request time). The Flutter app has no IANA tz database, so it needs this
     * to render chart axes and timestamps in SPOT-local time instead of
     * device-local time.
     */
    val utcOffsetMinutes: Int? = null,
    /** Short zone label for display, e.g. "HST", "PDT", "GMT+2". */
    val timezoneAbbr: String? = null
)

/**
 * Near-real-time wind for a spot's detail screen, fetched by the client AFTER
 * first paint (GET /spots/{id}/wind/live) so the detail load itself stays
 * instant. retrievedAt is epoch millis of the underlying Open-Meteo `current`
 * reading (or its bucket-cache timestamp).
 */
@Serializable
data class LiveWindResponse(
    val windSpeedKts: Double,
    val windDirectionCardinal: String,
    val gustKts: Double?,
    val retrievedAt: Long,
    // Numeric wind contract (additive): FROM bearing + kind/source labels.
    val windDirectionDeg: Int? = null,
    val windKind: String? = null,    // always "live" on this endpoint
    val windSource: String? = null   // "open-meteo"
)

/**
 * Multi-day tide chart curves for a spot, one TideChartData per spot-local day.
 * days[0] is the spot-local "today" (the only entry with currentHeightFt /
 * currentStage populated).
 */
@Serializable
data class SpotTideRangeResponse(
    val spotId: String,
    val timezoneId: String?,
    val days: List<TideChartData>
)

@Serializable
data class WaterQuality(
    val chlorophyllA: Double?,        // mg/m³ - algae indicator (0.1-0.5 clear, 1-5 productive, >10 bloom)
    val visibility: Double?,          // meters - Copernicus ZSD (kept for display, not used in scorer)
    val seaSurfaceTemp: Double?,      // °C - from NOAA or Copernicus
    val dataSource: String,           // Source attribution
    val chlorophyllCategory: String = categorizeChlorophyll(chlorophyllA),
    val visibilityCategory: String = categorizeVisibility(visibility)
) {
    companion object {
        fun categorizeChlorophyll(chl: Double?): String = when {
            chl == null -> "unknown"
            chl < 0.3 -> "excellent"     // Very clear, oligotrophic
            chl < 0.5 -> "good"          // Clear tropical/subtropical
            chl < 1.0 -> "moderate"      // Slightly productive
            chl < 3.0 -> "productive"    // Upwelling/coastal enrichment
            chl < 10.0 -> "high"         // Very productive
            else -> "bloom"              // Algae bloom conditions
        }
        
        fun categorizeVisibility(vis: Double?): String = when {
            vis == null -> "unknown"
            vis >= 30.0 -> "exceptional" // Crystal clear (30m+)
            vis >= 20.0 -> "excellent"   // Very good (20-30m)
            vis >= 15.0 -> "good"        // Good (15-20m)
            vis >= 10.0 -> "moderate"    // Average (10-15m)
            vis >= 5.0 -> "fair"         // Below average (5-10m)
            else -> "poor"               // Poor (<5m)
        }
    }
}

// ============================================
// FISHING INTEL MODELS
// Raw data for fishermen to interpret - no scores!
// ============================================

/**
 * Solunar data - moon phase and feeding periods.
 * Fishermen have used solunar tables for decades.
 */
@Serializable
data class SolunarData(
    val moonPhase: String,             // "waning_gibbous", "full_moon", etc.
    val illumination: Int,             // 0-100 percent
    val majorPeriods: List<TimePeriod>,// ~2hr periods around moon overhead/underfoot
    val minorPeriods: List<TimePeriod>,// ~1hr periods around moonrise/moonset
    val dayRating: Int? = null,        // 0-100 overall day rating (optional)
    val hourlyRating: Map<String, Int>? = null // Hour-by-hour rating (optional)
)

/**
 * A time period (start to end).
 */
@Serializable
data class TimePeriod(
    val start: String,                 // "14:34" (24hr format)
    val end: String                    // "16:34"
)

/**
 * Enhanced water context with trends and nearby readings.
 * Allows fishermen to spot temperature breaks and chlorophyll spikes.
 */
@Serializable
data class WaterContext(
    val chlorophyll: ChlorophyllContext?,
    val sstNearby: List<SSTReading>?   // readings at N/S/E/W for break detection
)

/**
 * Chlorophyll with 7-day trend.
 * Rising chlorophyll = plankton bloom = bait = fish!
 */
@Serializable
data class ChlorophyllContext(
    val current: Double,               // 0.42 mg/m³
    val avg7day: Double,               // 0.15 mg/m³
    val trend: String                  // "rising", "falling", "stable"
)

/**
 * SST reading at a nearby point.
 * Temperature breaks are where fish congregate.
 */
@Serializable
data class SSTReading(
    val direction: String,             // "E", "W", "N", "S"
    val distanceNm: Int,               // 5
    val tempC: Double                  // 24.5
)

// ============================================
// USER SPOTS MODELS
// ============================================

@Serializable
data class CreateUserSpotRequest(
    val name: String,
    val latitude: Double,
    val longitude: Double
)

@Serializable
data class UserSpotResponse(
    val id: String,
    val name: String,
    val coordinates: Coordinates,
    val region: String,
    val country: String,
    val createdAt: String,
    val isUserSpot: Boolean = true,  // Always true, used by frontend to show "Saved" badge
    val shakaScore: Int? = null,  // Latest score from cache (null if not yet calculated)
    val visibility: String? = null, // e.g. "Murky", "Blue water" (from cache)
    val swell: String? = null,     // e.g. "3ft @ 12s NW" (from cache)
    val wind: String? = null,      // e.g. "8 kts NE" (from cache)
    val waterTemp: String? = null,  // e.g. "24°C / 75°F" (from cache)
    // Raw numeric fields for client-side unit conversion
    val waterTempC: Double? = null,
    val swellHeightFt: Double? = null,
    val windSpeedKts: Double? = null
)

@Serializable
data class UserSpotListResponse(
    val spots: List<UserSpotResponse>,
    val count: Int,
    val limit: Int = 100
)

@Serializable
data class UserSpotDetailResponse(
    val spot: SpotDetail,
    val isUserSpot: Boolean = true
)

// ============================================
// ALL SPOTS (MAP MARKERS) MODELS
// Lightweight data for displaying all spots on map
// ============================================

/**
 * Lightweight spot data for map markers.
 * Contains only what's needed to display a marker - no conditions, fish, etc.
 */
@Serializable
data class SpotMapMarker(
    val id: String,
    val name: String,
    val coordinates: Coordinates,
    val region: String,
    val shakaScore: Int?,  // From cache, null if not yet calculated
    // Condition fields from cache (nullable - may not have data)
    val visibility: String? = null,  // "Murky", "Blue water", etc.
    val swell: String? = null,       // "3ft @ 12s NW"
    val wind: String? = null,        // "8 kts NE"
    val waterTemp: String? = null,   // "24°C / 75°F"
    // Raw numeric fields for client-side unit conversion
    val waterTempC: Double? = null,
    val swellHeightFt: Double? = null,
    val windSpeedKts: Double? = null
)

@Serializable
data class AllSpotsResponse(
    val spots: List<SpotMapMarker>,
    val count: Int
)

// ============================================
// HEALTH / ADMIN RESPONSE MODELS
// Typed responses to avoid Map<String, Any> serialization failures
// ============================================

@Serializable
data class OceanCacheStats(
    val hits: Long,
    val misses: Long,
    val hitRate: String,
    val waterQualityEntries: Int,
    val tideEntries: Int,
    val weatherEntries: Int,
    val oceanEntries: Int
)

@Serializable
data class DetailedHealthResponse(
    val status: String,
    val service: String,
    val services: Map<String, com.shaka.service.HealthService.ServiceStatus>,
    val realtimeSatelliteAvailable: Boolean,
    val cache: OceanCacheStats,
    val timestamp: String
)

@Serializable
data class WaterClarityVisibility(
    val meters: Double?,
    val category: String?
)

@Serializable
data class WaterClarityChlorophyll(
    val value: Double?,
    val unit: String = "mg/m³",
    val category: String?
)

@Serializable
data class WaterClaritySST(
    val celsius: Double?,
    val fahrenheit: Double?
)

@Serializable
data class WaterClarityData(
    val visibility: WaterClarityVisibility,
    val chlorophyll: WaterClarityChlorophyll,
    val seaSurfaceTemp: WaterClaritySST
)

@Serializable
data class RealtimeWaterQualityResponse(
    val spotId: String,
    val spotName: String,
    val date: String,
    val dataSource: String,
    val waterClarity: WaterClarityData,
    val note: String
)
