package com.shaka.pfz

import com.shaka.data.client.BathymetryClient
import com.shaka.data.client.CopernicusField
import com.shaka.data.client.CopernicusGrid
import com.shaka.data.client.CopernicusGridClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Spatial analysis behind the PFZ front-finder.
 *
 * Every analysis is produced from real [CopernicusGrid]s fetched through a
 * [PfzGridSource]. It computes, for one spot and date:
 *  - the strongest SST and chlorophyll gradients inside the box (°C/km, mg/m3/km),
 *  - whether those gradients cross the front detection thresholds ([PfzFactors]
 *    SST_FRONT_C_PER_KM / CHL_FRONT_MG_M3_PER_KM) and, when they do, the
 *    distance from the spot to the nearest front cell and the SST/CHL
 *    coincidence tier,
 *  - the SST anomaly at the spot cell,
 *  - the bathymetric slope from a centre + N/E/S/W probe pattern.
 *
 * Honesty contract: a grid that fails to fetch contributes null, never a
 * guess. A timeout on the whole analysis yields a fully-null [PfzSpatialGrid]
 * and the caller reports the corridor factors as missing.
 */
class PfzGridService(
    private val source: PfzGridSource = CopernicusGridSource()
) {
    suspend fun analyze(lat: Double, lon: Double, date: LocalDate): PfzSpatialGrid =
        withTimeoutOrNull(ANALYZE_TIMEOUT_MS) {
            // One batched call for the surface corridor, one for the benthic and
            // mesoscale fields. Batching matters: `uo` and `vo` are two variables
            // in one subset request, and the two datasets behind them are two
            // subprocess runs, not four.
            val surface = coroutineScope {
                val corridor = async {
                    source.fetchGrids(FRONT_FIELDS, lat, lon, date)
                }
                val benthic = async {
                    source.fetchGrids(BENTHIC_FIELDS, lat, lon, date)
                }
                val sshMonthly = async {
                    source.fetchGrids(listOf(CopernicusField.SSH_MONTHLY), lat, lon, date)
                }
                Triple(corridor.await(), benthic.await(), sshMonthly.await())
            }
            val (frontGrids, benthicGrids, monthlyGrids) = surface
            val sstGrid = frontGrids[CopernicusField.SST]
            val sstaGrid = frontGrids[CopernicusField.SSTA]
            val chlGrid = frontGrids[CopernicusField.CHL]

            val offsets = listOf(
                0.0 to 0.0,
                0.05 to 0.0, -0.05 to 0.0,
                0.0 to 0.05, 0.0 to -0.05
            )
            val probes = coroutineScope {
                offsets.map { (dLat, dLon) ->
                    async {
                        val pLat = lat + dLat
                        val pLon = lon + dLon
                        DepthProbe(pLat, pLon, source.depthM(pLat, pLon))
                    }
                }.awaitAll()
            }

            val sstEdges = sstGrid?.let { edgeGradients(it) }
            val chlEdges = chlGrid?.let { edgeGradients(it) }

            val sstGradient = sstEdges?.maxOfOrNull { it.magCPerKm }
            val chlGradient = chlEdges?.maxOfOrNull { it.magPerKm }

            val sstFront = sstEdges != null && (sstGradient ?: 0.0) >= PfzFactors.SST_FRONT_C_PER_KM
            val chlFront = chlEdges != null &&
                (chlGradient ?: 0.0) >= PfzFactors.CHL_FRONT_MG_M3_PER_KM

            val coincidence = when {
                sstGrid == null && chlGrid == null -> null
                sstFront && chlFront -> FrontCoincidence.COINCIDENT
                sstFront -> FrontCoincidence.SST_ONLY
                chlFront -> FrontCoincidence.CHL_ONLY
                else -> FrontCoincidence.NONE
            }

            val frontKm = when {
                sstFront -> nearestFrontKm(sstEdges!!.filter { it.magCPerKm >= PfzFactors.SST_FRONT_C_PER_KM }, lat, lon)
                chlFront -> nearestFrontKm(chlEdges!!.filter { it.magPerKm >= PfzFactors.CHL_FRONT_MG_M3_PER_KM }, lat, lon)
                else -> null
            }

            // dSST30 needs a second SST analysis a month back. Two real model
            // fields differenced by us; null if either is absent, and never a
            // filled-in value, because "no trend" and "no warming" are different.
            val sstWarming = sstWarmingC(sstGrid, lat, lon, date)

            PfzSpatialGrid(
                sstGradientCkm = sstGradient,
                chlaGradientMgM3km = chlGradient,
                sstAnomalyC = sstaGrid?.let { nearestCellValue(it, lat, lon) },
                depthGradientMperKm = depthGradientMperKm(probes),
                frontKm = frontKm,
                frontCoincidence = coincidence,
                dataDate = sstGrid?.dataDate ?: sstaGrid?.dataDate ?: chlGrid?.dataDate,
                bottomTempC = benthicGrids[CopernicusField.BOTTOM_TEMP]
                    ?.let { nearestCellValue(it, lat, lon) },
                bottomSalinityPsu = benthicGrids[CopernicusField.BOTTOM_SALINITY]
                    ?.let { nearestCellValue(it, lat, lon) },
                bottomCurrentMs = bottomCurrentMs(benthicGrids, lat, lon),
                mldM = benthicGrids[CopernicusField.MLD]?.let { nearestCellValue(it, lat, lon) },
                sshAnomalyM = sshAnomalyM(benthicGrids, monthlyGrids, lat, lon),
                sstWarmingC = sstWarming
            )
        } ?: PfzSpatialGrid.missing()

    /**
     * Zone analysis: partition the resolved box into candidate fishing zones
     * and produce, for each, the corridor fields plus a polygon.
     *
     * A zone is one *connected patch* of the box-cell lattice sharing the same
     * thermal-core class (positive/negative/neutral/unknown SST anomaly),
     * split where a detected front edge crosses the lattice. Front edges are
     * hard patch boundaries; the front *tier* itself is kept for scoring, not
     * fencing, so a near-threshold wobble shrinks a patch's score instead of
     * shredding the shape. The polygon is that patch's outline, so its shape
     * follows the resolved oceanographic structure instead of a fixed grid
     * stride. Meteo (wind/swell) and gridded bathymetry are not available for
     * arbitrary cells, so they neither shape the patch nor are silently
     * guessed; depth is still a single-point reading at a sample cell inside
     * each zone.
     *
     * At most [MAX_ZONES] patches are returned, the strongest by gradient
     * magnitude then size. Ranking by species is the caller's job (it needs the
     * profile); this returns every chosen patch with its measured corridor,
     * honestly null where the grid could not resolve.
     */
    suspend fun analyzeZones(lat: Double, lon: Double, requested: LocalDate): PfzZoneAnalysis {
        val analysis = withTimeoutOrNull(ANALYZE_TIMEOUT_MS) {
            val (frontGrids, benthicGrids, monthlyGrids) = coroutineScope {
                // Same three batches as the spot corridor: front fields, benthic
                // plus daily SSH, and the monthly mean the anomaly is differenced
                // against. One request per dataset, not one per factor.
                val front = async { source.fetchGrids(FRONT_FIELDS, lat, lon, requested) }
                val benthic = async { source.fetchGrids(BENTHIC_FIELDS, lat, lon, requested) }
                val monthly = async { source.fetchGrids(listOf(CopernicusField.SSH_MONTHLY), lat, lon, requested) }
                Triple(front.await(), benthic.await(), monthly.await())
            }
            val sstGrid = frontGrids[CopernicusField.SST]
            val sstaGrid = frontGrids[CopernicusField.SSTA]
            val chlGrid = frontGrids[CopernicusField.CHL]

            val anchor = sstGrid ?: chlGrid ?: sstaGrid
                ?: return@withTimeoutOrNull PfzZoneAnalysis.Empty(
                    PfzZoneEmptyReason.NO_GRID, pointResolved = false
                )
            // Whether the caller's own cell carries data is a different question
            // from whether any grid arrived. A beach or a coastal town resolves
            // nothing at the point while the open water 10 km off is perfectly
            // measurable, and reporting those two the same way throws away the
            // only fact the caller can actually act on.
            val pointResolved = nearestCellValue(anchor, lat, lon) != null
            val sstEdges = sstGrid?.let { edgeGradients(it) }
            val chlEdges = chlGrid?.let { edgeGradients(it) }
            val analyzed = anchor.dataDate
            val forecastDay = analyzed?.let {
                ChronoUnit.DAYS.between(it, requested).toInt().coerceAtLeast(0)
            } ?: 0

            val cells = buildList<Cell> {
                for (i in 0 until anchor.lats.size - 1) {
                    for (j in 0 until anchor.lons.size - 1) {
                        add(
                            Cell(
                                i = i,
                                j = j,
                                lat = (anchor.lats[i] + anchor.lats[i + 1]) / 2,
                                lon = (anchor.lons[j] + anchor.lons[j + 1]) / 2
                            )
                        )
                    }
                }
            }
            if (cells.isEmpty()) {
                return@withTimeoutOrNull PfzZoneAnalysis.Empty(
                    PfzZoneEmptyReason.NO_PATCHES, pointResolved
                )
            }

            val signatures = cells.associateWith { cellSignature(it, sstEdges, chlEdges, sstaGrid) }
            val cellMag = cells.associateWith {
                maxOf(
                    nearestEdgeMag(sstEdges, it.lat, it.lon) ?: 0.0,
                    nearestEdgeMag(chlEdges, it.lat, it.lon) ?: 0.0
                )
            }
            val latBarriers = barrierAtLat(anchor.lats, sstGrid, chlGrid)
            val lonBarriers = barrierAtLon(anchor.lons, sstGrid, chlGrid)
            val patches = connectedPatches(cells, signatures, cellMag, latBarriers, lonBarriers)

            val chosen = patches
                .sortedWith(
                    compareByDescending<Patch> { it.maxGradient }
                        .thenByDescending { it.cells.size }
                        .thenBy { it.cells.minOf { c -> c.lat } }
                )
                .take(MAX_ZONES)
                .map { patch ->
                    val centroidLat = patch.cells.map { it.lat }.average()
                    val centroidLon = patch.cells.map { it.lon }.average()
                    val representative = patch.cells.minByOrNull {
                        approxKm(centroidLat, centroidLon, it.lat, it.lon)
                    }!!
                    val (outer, holes) = polygonOf(patch.cells, anchor.lats, anchor.lons)
                    zoneDatum(
                        representative.lat, representative.lon,
                        sstGrid, sstEdges, chlEdges, chlGrid, sstaGrid, benthicGrids, monthlyGrids,
                        requested, analyzed, forecastDay
                    ).copy(
                        lat = centroidLat,
                        lon = centroidLon,
                        cellLat = representative.lat,
                        cellLon = representative.lon,
                        polygon = outer,
                        holes = holes
                    )
                }

            if (chosen.isEmpty()) {
                PfzZoneAnalysis.Empty(PfzZoneEmptyReason.NO_PATCHES, pointResolved)
            } else {
                PfzZoneAnalysis.Resolved(chosen, pointResolved)
            }
        }
        // A deadline that expired is not an absence of data. This used to
        // collapse into the same emptyList() as a genuinely featureless sea, so
        // a cold fetch that ran long was reported to the caller as "no zones
        // today" — indistinguishable from a real no-data day, and therefore
        // impossible to tell apart from one in production.
        return analysis ?: PfzZoneAnalysis.Empty(PfzZoneEmptyReason.TIMED_OUT, pointResolved = false)
    }

    // ---------------------------------------------------------------- outcome

    /**
     * Why a zone analysis produced nothing.
     *
     * These are four different facts and the caller has to be able to tell them
     * apart: one is worth retrying, one is a misconfiguration, one is the
     * caller's own coordinates being wrong, and one is an honest empty day.
     */
    enum class PfzZoneEmptyReason {
        /** The analysis deadline expired before the datasets finished arriving. */
        TIMED_OUT,

        /** No SST/SSTA/CHL grid resolved for this area at all. */
        NO_GRID,

        /** Grids arrived, but none survived the patch fencing. */
        NO_PATCHES
    }

    /**
     * Outcome of [analyzeZones].
     *
     * [pointResolved] travels with the result rather than being derived from
     * [zones] because it is independent: a masked point can still sit inside a
     * window that yields perfectly good offshore zones, which is exactly the
     * case that needs saying out loud.
     */
    sealed interface PfzZoneAnalysis {
        val zones: List<PfzZoneDatum>
        val pointResolved: Boolean

        data class Resolved(
            override val zones: List<PfzZoneDatum>,
            override val pointResolved: Boolean
        ) : PfzZoneAnalysis

        data class Empty(
            val reason: PfzZoneEmptyReason,
            override val pointResolved: Boolean
        ) : PfzZoneAnalysis {
            override val zones: List<PfzZoneDatum> = emptyList()
        }
    }

    // ---------------------------------------------------------------- maths

    /** Which thermal-core band a cell belongs to for patch fencing. */
    private enum class AnomalyBand { POS, NEG, NEUTRAL, UNKNOWN }

    private data class Cell(val i: Int, val j: Int, val lat: Double, val lon: Double)

    private data class Patch(val cells: Set<Cell>, val maxGradient: Double)

    private data class GridPoint(val r: Int, val c: Int)

    /**
     * The thermal-core signature that decides patch membership. Fronts cut on
     * the barrier edges below; only the anomaly band fences here, because the
     * coincidence tier wobbles at the gradient threshold and would shred the
     * shape into single-cell speckle.
     */
    private fun cellSignature(
        cell: Cell,
        sstEdges: List<EdgeGradient>?,
        chlEdges: List<EdgeGradient>?,
        sstaGrid: CopernicusGrid?
    ): AnomalyBand {
        val anomaly = sstaGrid?.let { nearestCellValue(it, cell.lat, cell.lon) }
        return when {
            anomaly == null -> AnomalyBand.UNKNOWN
            anomaly >= ANOMALY_CORE_C -> AnomalyBand.POS
            anomaly <= -ANOMALY_CORE_C -> AnomalyBand.NEG
            else -> AnomalyBand.NEUTRAL
        }
    }

    /**
     * Boundary fences for the anchor lattice: an anchor row band becomes a
     * hard patch boundary when a *decisive* front (over the barrier
     * thresholds, which are stronger than the front-detection thresholds used
     * for scoring) crosses it in the SST or CHL grid. A detected front still
     * moves the score; only an unambiguous frontal core is a hard patch
     * boundary, otherwise per-cell gradient wobble near the detection threshold
     * would shred every polygon into single-cell speckle.
     *
     * Each grid is evaluated on its own lattice and routed onto the anchor
     * bands by latitude, because the products resolve at different spacings
     * and footprints (SST/SSTA 1/120 deg, CHL ~1 km) and never share row
     * indices.
     */
    private fun barrierAtLat(
        lats: List<Double>,
        sst: CopernicusGrid?,
        chl: CopernicusGrid?
    ): List<Boolean> {
        val out = MutableList((lats.size - 1).coerceAtLeast(0)) { false }
        sst?.let { fenceAtLat(it, out, lats, BARRIER_SST_FRONT_C_PER_KM) }
        chl?.let { fenceAtLat(it, out, lats, BARRIER_CHL_FRONT_MG_M3_PER_KM) }
        return out
    }

    private fun fenceAtLat(
        g: CopernicusGrid,
        out: MutableList<Boolean>,
        anchorLats: List<Double>,
        threshold: Double
    ) {
        for (i in 1 until g.lats.size) {
            val dLatKm = (g.lats[i] - g.lats[i - 1]) * KM_PER_DEG_LAT
            if (dLatKm <= 0.0) continue
            val front = (0 until g.lons.size).any { j ->
                val a = g.values[i][j]
                val b = g.values[i - 1][j]
                !a.isNaN() && !b.isNaN() && abs(a - b) / dLatKm >= threshold
            }
            if (!front) continue
            val midLat = (g.lats[i - 1] + g.lats[i]) / 2
            val r = bandIndexOf(anchorLats, midLat)
            if (r in out.indices) out[r] = true
        }
    }

    /** Longitude twin of [barrierAtLat] (cos-latitude corrected). */
    private fun barrierAtLon(
        lons: List<Double>,
        sst: CopernicusGrid?,
        chl: CopernicusGrid?
    ): List<Boolean> {
        val out = MutableList((lons.size - 1).coerceAtLeast(0)) { false }
        sst?.let { fenceAtLon(it, out, lons, BARRIER_SST_FRONT_C_PER_KM) }
        chl?.let { fenceAtLon(it, out, lons, BARRIER_CHL_FRONT_MG_M3_PER_KM) }
        return out
    }

    private fun fenceAtLon(
        g: CopernicusGrid,
        out: MutableList<Boolean>,
        anchorLons: List<Double>,
        threshold: Double
    ) {
        for (c in 1 until g.lons.size) {
            val front = (0 until g.lats.size).any { i ->
                val a = g.values[i][c]
                val b = g.values[i][c - 1]
                if (a.isNaN() || b.isNaN()) false
                else {
                    val cosLat = cos(PI * g.lats[i] / 180.0)
                    val dLonKm = (g.lons[c] - g.lons[c - 1]) * KM_PER_DEG_LON * cosLat
                    dLonKm > 0.0 && abs(a - b) / dLonKm >= threshold
                }
            }
            if (!front) continue
            val midLon = (g.lons[c - 1] + g.lons[c]) / 2
            val r = bandIndexOf(anchorLons, midLon)
            if (r in out.indices) out[r] = true
        }
    }

    /** Anchor band index whose span [values[r], values[r + 1]] contains [mid]. */
    private fun bandIndexOf(values: List<Double>, mid: Double): Int {
        for (r in 0 until values.size - 1) {
            val lo = minOf(values[r], values[r + 1])
            val hi = maxOf(values[r], values[r + 1])
            if (mid >= lo && mid <= hi) return r
        }
        return -1
    }

    /**
     * Flood-fill the lattice into 4-connected patches of cells with the same
     * [CellSignature], never crossing a front-lattice barrier.
     */
    private fun connectedPatches(
        cells: List<Cell>,
        signatures: Map<Cell, AnomalyBand>,
        cellMag: Map<Cell, Double>,
        latBarriers: List<Boolean>,
        lonBarriers: List<Boolean>
    ): List<Patch> {
        val byIndex = cells.associateBy { it.i to it.j }
        val visited = mutableSetOf<Cell>()
        val patches = mutableListOf<Patch>()

        for (start in cells.sortedWith(compareBy({ it.i }, { it.j }))) {
            if (start in visited) continue
            val queue = ArrayDeque<Cell>().apply { addLast(start) }
            visited += start
            val members = linkedSetOf(start)
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                val sig = signatures.getValue(c)
                val neighbors = buildList {
                    // North: shares the lat boundary between rows c.i and c.i+1.
                    byIndex[c.i + 1 to c.j]?.takeIf { !latBarriers.getOrElse(c.i) { true } }?.let(::add)
                    // South: shares the lat boundary between rows c.i-1 and c.i.
                    byIndex[c.i - 1 to c.j]?.takeIf { c.i - 1 >= 0 && !latBarriers.getOrElse(c.i - 1) { true } }?.let(::add)
                    // East: shares the lon boundary between cols c.j and c.j+1.
                    byIndex[c.i to c.j + 1]?.takeIf { !lonBarriers.getOrElse(c.j) { true } }?.let(::add)
                    // West: shares the lon boundary between cols c.j-1 and c.j.
                    byIndex[c.i to c.j - 1]?.takeIf { c.j - 1 >= 0 && !lonBarriers.getOrElse(c.j - 1) { true } }?.let(::add)
                }
                for (n in neighbors) {
                    if (n in members || n in visited) continue
                    if (signatures.getValue(n) == sig) {
                        queue.addLast(n)
                        members += n
                        visited += n
                    }
                }
            }
            patches += Patch(members, members.maxOf { cellMag.getValue(it) })
        }
        return patches
    }

    /**
     * Outline of a patch as outer ring + interior rings. The ring points are
     * lattice corners, so a patch spanning an entire front-free box outlines
     * exactly its real extent; a hole in the patch becomes its own ring.
     */
    private fun polygonOf(
        patch: Set<Cell>,
        lats: List<Double>,
        lons: List<Double>
    ): Pair<List<PfzPoint>, List<List<PfzPoint>>> {
        val member = patch.mapTo(java.util.LinkedHashSet()) { it.i to it.j }
        fun inPatch(i: Int, j: Int): Boolean =
            i >= 0 && j >= 0 && i < lats.size - 1 && j < lons.size - 1 && (i to j) in member

        val edges = mutableListOf<Pair<GridPoint, GridPoint>>()
        for (ll in 0 until lats.size) {
            for (c in 0 until lons.size - 1) {
                if (inPatch(ll - 1, c) != inPatch(ll, c)) {
                    edges += GridPoint(ll, c) to GridPoint(ll, c + 1)
                }
            }
        }
        for (lc in 0 until lons.size) {
            for (i in 0 until lats.size - 1) {
                if (inPatch(i, lc - 1) != inPatch(i, lc)) {
                    edges += GridPoint(i, lc) to GridPoint(i + 1, lc)
                }
            }
        }

        val rings = traceRings(edges).sortedByDescending { it.size }
        if (rings.isEmpty()) return emptyList<PfzPoint>() to emptyList<List<PfzPoint>>()
        val outer = rings.first().map { PfzPoint(lats[it.r], lons[it.c]) }
        val holes = rings.drop(1).map { ring -> ring.map { PfzPoint(lats[it.r], lons[it.c]) } }
        return outer to holes
    }

    /**
     * Close the boundary-edge graph into simple rings by left-wall walking: at
     * each lattice point keep the patch on the left (smallest counter-clockwise
     * turn from the arrival direction), which traces an exterior ring or a hole
     * ring without crossing.
     */
    private fun traceRings(edges: List<Pair<GridPoint, GridPoint>>): List<List<GridPoint>> {
        if (edges.isEmpty()) return emptyList()
        val adj = mutableMapOf<GridPoint, MutableList<GridPoint>>()
        for ((a, b) in edges) {
            adj.getOrPut(a) { mutableListOf() }.add(b)
            adj.getOrPut(b) { mutableListOf() }.add(a)
        }
        val used = mutableSetOf<Pair<GridPoint, GridPoint>>()
        val rings = mutableListOf<List<GridPoint>>()

        for ((a, b) in edges) {
            if (a to b in used || b to a in used) continue
            val ring = mutableListOf(a, b)
            used += a to b
            var prev = a
            var cur = b
            var guard = 0
            while (cur != a) {
                val candidates = adj[cur].orEmpty().filter { it != prev && (cur to it) !in used }
                if (candidates.isEmpty()) return rings
                val next = pickLeftmost(prev, cur, candidates)
                used += cur to next
                ring += next
                prev = cur
                cur = next
                if (++guard > edges.size * 2) return rings
            }
            rings += ring
        }
        return rings
    }

    /** Among boundary candidates at a point, take the tightest left (CCW) turn. */
    private fun pickLeftmost(
        prev: GridPoint,
        cur: GridPoint,
        candidates: List<GridPoint>
    ): GridPoint {
        val dr = cur.r - prev.r
        val dc = cur.c - prev.c
        return candidates.minByOrNull { n ->
            val nr = n.r - cur.r
            val nc = n.c - cur.c
            val angle = Math.toDegrees(atan2((dr * nc - dc * nr).toDouble(), (dr * nr + dc * nc).toDouble()))
            if (angle < 0.0) angle + 360.0 else angle
        }!!
    }

    /**
     * Corridor fields for one zone centre.
     *
     * [benthic] and [monthly] carry the same products the spot corridor uses, so
     * a species with a required benthic or mesoscale factor (hake, red shrimp,
     * bluefin) scores against real measurements offshore too, instead of being
     * refused for lack of a factor that was simply never fetched here.
     */
    private suspend fun zoneDatum(
        lat: Double,
        lon: Double,
        sstGrid: CopernicusGrid?,
        sstEdges: List<EdgeGradient>?,
        chlEdges: List<EdgeGradient>?,
        chlGrid: CopernicusGrid?,
        sstaGrid: CopernicusGrid?,
        benthic: Map<CopernicusField, CopernicusGrid>,
        monthly: Map<CopernicusField, CopernicusGrid>,
        date: LocalDate,
        dataDate: LocalDate?,
        forecastDay: Int
    ): PfzZoneDatum {
        val sstGradient = nearestEdgeMag(sstEdges, lat, lon)
        val chlGradient = nearestEdgeMag(chlEdges, lat, lon)

        val sstFront = sstEdges != null && (sstGradient ?: 0.0) >= PfzFactors.SST_FRONT_C_PER_KM
        val chlFront = chlEdges != null && (chlGradient ?: 0.0) >= PfzFactors.CHL_FRONT_MG_M3_PER_KM
        val coincidence = when {
            sstEdges == null && chlEdges == null -> null
            sstFront && chlFront -> FrontCoincidence.COINCIDENT
            sstFront -> FrontCoincidence.SST_ONLY
            chlFront -> FrontCoincidence.CHL_ONLY
            else -> FrontCoincidence.NONE
        }

        val frontEdges = buildList {
            sstEdges?.filter { it.magPerKm >= PfzFactors.SST_FRONT_C_PER_KM }?.let { addAll(it) }
            chlEdges?.filter { it.magPerKm >= PfzFactors.CHL_FRONT_MG_M3_PER_KM }?.let { addAll(it) }
        }
        val frontKm = frontEdges.minOfOrNull { approxKm(lat, lon, it.latMid, it.lonMid) }

        return PfzZoneDatum(
            lat = lat,
            lon = lon,
            sstGradientCkm = sstGradient,
            chlaGradientMgM3km = chlGradient,
            sstAnomalyC = sstaGrid?.let { nearestCellValue(it, lat, lon) },
            waterTempC = sstGrid?.let { nearestCellValue(it, lat, lon) },
            // The CHL grid is already loaded for its gradient, so the concentration
            // at the zone centre costs nothing extra. Bluefin spawning requires
            // it, so leaving it out would refuse that mode offshore while the
            // measurement sat unused in memory.
            chlorophyllMgM3 = chlGrid?.let { nearestCellValue(it, lat, lon) },
            bottomTempC = benthic[CopernicusField.BOTTOM_TEMP]?.let { nearestCellValue(it, lat, lon) },
            bottomSalinityPsu = benthic[CopernicusField.BOTTOM_SALINITY]
                ?.let { nearestCellValue(it, lat, lon) },
            bottomCurrentMs = bottomCurrentMs(benthic, lat, lon),
            mldM = benthic[CopernicusField.MLD]?.let { nearestCellValue(it, lat, lon) },
            sshAnomalyM = sshAnomalyM(benthic, monthly, lat, lon),
            sstWarmingC = sstWarmingC(sstGrid, lat, lon, date),
            depthM = source.depthM(lat, lon),
            depthGradientMperKm = null,
            frontKm = frontKm,
            frontCoincidence = coincidence,
            dataDate = dataDate,
            forecastDay = forecastDay
        )
    }

    /**
     * DERIVED dSST30: [sstGrid] minus SST [SST_TREND_DAYS] days earlier at the
     * same cell, both real model fields differenced here.
     *
     * Null whenever either leg is absent, and never defaulted to 0.0: "the grid
     * did not resolve" and "the sea did not warm" are different answers, and
     * bluefin spawning makes this factor REQUIRED precisely because it is the
     * cue the stock responds to.
     */
    private suspend fun sstWarmingC(
        sstGrid: CopernicusGrid?,
        lat: Double,
        lon: Double,
        date: LocalDate
    ): Double? {
        if (sstGrid == null) return null
        val then = withTimeoutOrNull(TREND_TIMEOUT_MS) {
            source.fetchGrids(
                listOf(CopernicusField.SST), lat, lon, date.minusDays(SST_TREND_DAYS)
            )
        }?.get(CopernicusField.SST) ?: return null
        val now = nearestCellValue(sstGrid, lat, lon) ?: return null
        val before = nearestCellValue(then, lat, lon) ?: return null
        return now - before
    }

    /** Strongest gradient at the edge cell closest to (lat, lon). */
    private fun nearestEdgeMag(edges: List<EdgeGradient>?, lat: Double, lon: Double): Double? {
        if (edges.isNullOrEmpty()) return null
        return edges.minBy { approxKm(lat, lon, it.latMid, it.lonMid) }.magPerKm
    }

    /** Per-cell edge gradient magnitude in natural units per km. */
    private fun edgeGradients(grid: CopernicusGrid): List<EdgeGradient> {
        val out = mutableListOf<EdgeGradient>()
        for (i in 1 until grid.lats.size) {
            val dLatKm = (grid.lats[i] - grid.lats[i - 1]) * KM_PER_DEG_LAT
            val latRad = PI * grid.lats[i] / 180.0
            for (j in 1 until grid.lons.size) {
                val dLonKm = (grid.lons[j] - grid.lons[j - 1]) * KM_PER_DEG_LON * cos(latRad)
                if (dLonKm <= 0.0) continue

                val dLatVal = grid.values[i][j] - grid.values[i - 1][j]
                val dLonVal = grid.values[i][j] - grid.values[i][j - 1]
                if (dLatVal.isNaN() || dLonVal.isNaN()) continue

                val mag = sqrt(
                    (dLatVal / dLatKm) * (dLatVal / dLatKm) +
                        (dLonVal / dLonKm) * (dLonVal / dLonKm)
                )
                // Emit zero-gradient edges too: a grid that resolved but is flat
                // is a measured "no front", not an unknown.
                out += EdgeGradient(
                    magCPerKm = mag,
                    magPerKm = mag,
                    latMid = (grid.lats[i] + grid.lats[i - 1]) / 2,
                    lonMid = (grid.lons[j] + grid.lons[j - 1]) / 2
                )
            }
        }
        return out
    }

    private fun nearestFrontKm(frontEdges: List<EdgeGradient>, lat: Double, lon: Double): Double =
        frontEdges.minOf { approxKm(lat, lon, it.latMid, it.lonMid) }

    private fun nearestCellValue(grid: CopernicusGrid, lat: Double, lon: Double): Double? {
        val cosLat = cos(PI * lat / 180.0)
        var bestI = -1
        var bestJ = -1
        var best = Double.MAX_VALUE
        for (i in grid.lats.indices) {
            val dLat = grid.lats[i] - lat
            for (j in grid.lons.indices) {
                val dLon = (grid.lons[j] - lon) * cosLat
                val d = dLat * dLat + dLon * dLon
                if (d < best) {
                    best = d
                    bestI = i
                    bestJ = j
                }
            }
        }
        if (bestI < 0 || bestJ < 0) return null
        val v = grid.values[bestI][bestJ]
        return if (v.isNaN()) null else v
    }

    /**
     * Bottom current speed, m/s, as the magnitude of the `uo`/`vo` pair at the
     * same cell.
     *
     * Both components must resolve at the cell. Taking the magnitude of a
     * resolved zonal component against a masked meridional one would report a
     * spurious weak current, and a demersal profile that gates on this would
     * then pass on half a measurement.
     */
    private fun bottomCurrentMs(
        grids: Map<CopernicusField, CopernicusGrid>,
        lat: Double,
        lon: Double
    ): Double? {
        val u = grids[CopernicusField.CURRENT_U]?.let { nearestCellValue(it, lat, lon) } ?: return null
        val v = grids[CopernicusField.CURRENT_V]?.let { nearestCellValue(it, lat, lon) } ?: return null
        return sqrt(u * u + v * v)
    }

    /**
     * Sea surface height anomaly, DERIVED as daily `zos` minus the monthly-mean
     * `zos` of the same product.
     *
     * Derived rather than read, because this product's static dataset publishes
     * no mean dynamic topography (`mdt`) - verified with `copernicusmarine
     * describe`, which lists only `deptho`, `deptho_lev` and `mask`. The two
     * fields are both real model output and the differencing is ours, so the
     * factor is marked derived tier in the profiles.
     *
     * Both cells must resolve. One sided, this would be the raw daily sea level,
     * whose units are metres of geoid height and whose mean is not zero.
     */
    private fun sshAnomalyM(
        daily: Map<CopernicusField, CopernicusGrid>,
        monthly: Map<CopernicusField, CopernicusGrid>,
        lat: Double,
        lon: Double
    ): Double? {
        val today = daily[CopernicusField.SSH]?.let { nearestCellValue(it, lat, lon) } ?: return null
        val climatology = monthly[CopernicusField.SSH_MONTHLY]
            ?.let { nearestCellValue(it, lat, lon) } ?: return null
        return today - climatology
    }

    private fun depthGradientMperKm(probes: List<DepthProbe>): Double? {
        val center = probes.firstOrNull() ?: return null
        val centerDepth = center.depthM ?: return null
        var maxSlope = 0.0
        for (p in probes.drop(1)) {
            val depth = p.depthM ?: continue
            val distanceKm = approxKm(center.lat, center.lon, p.lat, p.lon)
            if (distanceKm <= 0.0) continue
            val slope = abs(depth - centerDepth) / distanceKm
            if (slope > maxSlope) maxSlope = slope
        }
        return if (maxSlope > 0.0) maxSlope else null
    }

    /** Equirectangular approximation, fine for the sub-10 km probes and boxes here. */
    private fun approxKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = lat2 - lat1
        val dLon = lon2 - lon1
        val latMidRad = PI * (lat1 + lat2) / 360.0
        val x = dLon * KM_PER_DEG_LON * cos(latMidRad)
        val y = dLat * KM_PER_DEG_LAT
        return sqrt(x * x + y * y)
    }

    private data class EdgeGradient(
        val magCPerKm: Double,
        val magPerKm: Double,
        val latMid: Double,
        val lonMid: Double
    )

    private data class DepthProbe(val lat: Double, val lon: Double, val depthM: Double?)

    companion object {
        private const val KM_PER_DEG_LAT = 110.574
        private const val KM_PER_DEG_LON = 111.320
        /**
         * Wall-clock budget for one analysis.
         *
         * This has to clear [CopernicusGridClient]'s 180s per-dataset subprocess
         * timeout, or a single slow dataset can never finish and every cold area
         * fails the budget by construction. A measured cold fetch of nine Med
         * datasets over three parallel batches ran 106-131s, which the previous
         * 120s cap straddled: the same request succeeded or reported "no data"
         * depending on which side of the line the download happened to land.
         */
        private const val ANALYZE_TIMEOUT_MS = 240_000L

        /** Days between the two SST analyses differenced into dSST30. */
        private const val SST_TREND_DAYS = 30L

        /**
         * The trend fetch is the least important thing in the corridor, so it
         * gets its own shorter budget: a hung 30-day-old request must not
         * discard an otherwise complete analysis.
         */
        private const val TREND_TIMEOUT_MS = 30_000L

        /** The surface corridor, all from the same L4 daily products. */
        val FRONT_FIELDS = listOf(
            CopernicusField.SST, CopernicusField.SSTA, CopernicusField.CHL
        )

        /**
         * The benthic and mesoscale fields, all from the same L4 physics product,
         * so they share one subset request instead of one each.
         *
         * SSH belongs here, not in [FRONT_FIELDS]: the anomaly is differenced
         * against [SSH_MONTHLY], and the daily term is read out of this same
         * batch. Omitting it leaves every SSHa null, which makes bluefin feeding
         * permanently `insufficient_data` because it requires `ssh_anomaly`.
         */
        val BENTHIC_FIELDS = listOf(
            CopernicusField.BOTTOM_TEMP,
            CopernicusField.BOTTOM_SALINITY,
            CopernicusField.CURRENT_U,
            CopernicusField.CURRENT_V,
            CopernicusField.MLD,
            CopernicusField.SSH
        )

        /** SST anomaly magnitude (C) that counts as a warm/cool core. */
        private const val ANOMALY_CORE_C = 0.5

        /**
         * Barrier thresholds carve patch boundaries. They sit well above the
         * front-detection thresholds ([PfzFactors]) so shallow gradients still
         * score but do not shred the geometry into single-cell speckle.
         */
        private const val BARRIER_SST_FRONT_C_PER_KM = 0.25
        private const val BARRIER_CHL_FRONT_MG_M3_PER_KM = 0.25

        /** Cap on ranked candidate zones per request (bounds bathymetry calls). */
        const val MAX_ZONES = 24
    }
}

/**
 * One candidate zone of [PfzGridService.analyzeZones]: the corridor fields of
 * one resolved habitat patch. Every nullable is "the grid could not resolve
 * this here", which the engine turns into a named missing factor.
 */
data class PfzZoneDatum(
    /** Patch centroid — the map centre of the polygon. */
    val lat: Double,
    val lon: Double,
    /**
     * The patch's representative grid cell, nearest the centroid.
     *
     * Stable where [lat]/[lon] is not: the centroid is an average over the
     * patch, so it drifts whenever the front reshapes the patch even though the
     * cell the score was sampled from has not moved. Persisted history keys on
     * this, not the centroid, so a day-over-day trend compares one grid cell to
     * itself.
     */
    val cellLat: Double = lat,
    val cellLon: Double = lon,
    val sstGradientCkm: Double?,
    val chlaGradientMgM3km: Double?,
    val sstAnomalyC: Double?,
    val depthM: Double?,
    val depthGradientMperKm: Double?,
    val frontKm: Double?,
    val frontCoincidence: FrontCoincidence?,
    val dataDate: LocalDate?,
    val forecastDay: Int,

    // ---- benthic and mesoscale factors, sampled at the patch's representative
    // cell exactly as for a spot. A zone is scored by the same model, so it needs
    // the same real measurements: without these a hake or red shrimp zone could
    // only ever be refused, and a bluefin zone could never clear its required
    // `ssh_anomaly`.
    val waterTempC: Double? = null,
    val chlorophyllMgM3: Double? = null,
    val bottomTempC: Double? = null,
    val bottomSalinityPsu: Double? = null,
    val bottomCurrentMs: Double? = null,
    val mldM: Double? = null,
    /** DERIVED: daily SSH minus the monthly mean of the same product. */
    val sshAnomalyM: Double? = null,
    /** DERIVED: current SST minus SST 30 days earlier. */
    val sstWarmingC: Double? = null,

    /** Outer ring of the patch. Closed (first point == last point). */
    val polygon: List<PfzPoint> = emptyList(),
    /** Interior rings of [polygon] (holes). */
    val holes: List<List<PfzPoint>> = emptyList()
)

/**
 * What an analysis found (or, honestly, could not find) around one spot.
 * Every nullable is "the grid/field did not resolve", never a defaulted guess.
 */
data class PfzSpatialGrid(
    val sstGradientCkm: Double?,
    val chlaGradientMgM3km: Double?,
    val sstAnomalyC: Double?,
    val depthGradientMperKm: Double?,
    val frontKm: Double?,
    val frontCoincidence: FrontCoincidence?,
    val dataDate: LocalDate?,

    // ---- benthic and mesoscale fields, all real Copernicus physics products.
    // Null means the grid did not resolve, and the engine reports the factor as
    // missing. For a species that declares one of these REQUIRED that is
    // insufficient_data, not a smaller number.

    /** Copernicus `bottomT`, °C. Colloca et al.'s SBT, the hake profile's main term. */
    val bottomTempC: Double? = null,

    /** Copernicus `so` at the deepest non-masked level, psu. */
    val bottomSalinityPsu: Double? = null,

    /**
     * Bottom current SPEED, m/s, from the vector magnitude of `uo` and `vo` at
     * the deepest non-masked level.
     *
     * m/s and not km/h because Colloca et al. (2014) fit hake recruitment to a
     * 0.034 m/s ceiling; 0.034 m/s is 0.12 km/h, and mixing the units would
     * invert every cell in the profile.
     */
    val bottomCurrentMs: Double? = null,

    /** Copernicus `mlotst`, m. */
    val mldM: Double? = null,

    /**
     * DERIVED, not published: daily `zos` minus the monthly-mean `zos` of the
     * same product, because that product's static dataset carries no `mdt`.
     *
     * Measured live: 18 mm of spread across a 9 km box against 276 mm across a
     * 5x8 degree box, so this is a regional gate rather than a local signal.
     */
    val sshAnomalyM: Double? = null,

    /**
     * DERIVED 30-day SST change in °C (Druon et al.'s dSST30), differencing the
     * current SST analysis against the one 30 days earlier.
     */
    val sstWarmingC: Double? = null
) {
    companion object {
        fun missing(): PfzSpatialGrid =
            PfzSpatialGrid(null, null, null, null, null, null, null)
    }
}

/**
 * Pluggable access to the ocean/data grids the analysis consumes.
 *
 * This seam is what makes [PfzGridService] testable without touching the real
 * Copernicus toolbox or the bathy services.
 */
interface PfzGridSource {
    suspend fun fetchGrid(
        field: CopernicusField,
        lat: Double,
        lon: Double,
        date: LocalDate
    ): CopernicusGrid?

    /**
     * Several fields at one point, grouped by dataset so each `copernicusmarine
     * subset` call carries all the variables its dataset can serve.
     *
     * The default is a plain loop over [fetchGrid]. [CopernicusGridSource]
     * overrides it with the client's real batching, because a subset call takes
     * repeated `--variable` flags: `uo` and `vo` are one subprocess there and two
     * here, and the difference is 9-20 s of network and Python startup each.
     *
     * A field that cannot be fetched is simply absent from the result. Nothing
     * is substituted and nothing is ordered, so callers must treat a missing key
     * as "unknown".
     */
    suspend fun fetchGrids(
        fields: List<CopernicusField>,
        lat: Double,
        lon: Double,
        date: LocalDate
    ): Map<CopernicusField, CopernicusGrid> {
        val out = LinkedHashMap<CopernicusField, CopernicusGrid>(fields.size)
        for (field in fields) {
            fetchGrid(field, lat, lon, date)?.let { out[field] = it }
        }
        return out
    }

    /** Single-point bathymetric depth at (lat, lon), or null. */
    suspend fun depthM(lat: Double, lon: Double): Double?
}

/**
 * Production [PfzGridSource]: Copernicus Marine Toolbox grids for SST/SSTA/CHL
 * and NCEI-DEM/GEBCO bathymetry for the depth probes.
 */
class CopernicusGridSource(
    private val grid: CopernicusGridClient = CopernicusGridClient(),
    private val bathymetry: BathymetryClient = BathymetryClient()
) : PfzGridSource {
    override suspend fun fetchGrid(
        field: CopernicusField,
        lat: Double,
        lon: Double,
        date: LocalDate
    ): CopernicusGrid? = grid.fetchGrid(field, lat, lon, date = date)

    /**
     * Real batching. [CopernicusGridClient.fetchGrids] groups the requested
     * fields by dataset and issues one subset call per dataset, so the benthic
     * corridor is three subprocess runs rather than five.
     */
    override suspend fun fetchGrids(
        fields: List<CopernicusField>,
        lat: Double,
        lon: Double,
        date: LocalDate
    ): Map<CopernicusField, CopernicusGrid> =
        grid.fetchGrids(fields, lat, lon, date = date)

    override suspend fun depthM(lat: Double, lon: Double): Double? =
        bathymetry.fetchDepthOnly(lat, lon)?.depthM
}