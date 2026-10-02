package com.shaka.pfz

import com.shaka.data.db.DatabaseFactory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

/**
 * Daily persistence for ranked [PfzZone] snapshots, plus the day-over-day trend
 * derived from them.
 *
 * ## Why this table is keyed the way it is
 *
 * A zone is not a stable place. The grid patches move every day, so there is no
 * zone id that survives a rebuild. The one thing that *is* stable across days is
 * the request: the same (anchor coordinate, species, mode, size class) box. So the
 * primary key is that request plus the local date, and a zone within one run is
 * keyed by its **grid cell**, not its rank.
 *
 * Rank is deliberately not part of the key. Rank 1 yesterday and rank 1 today are
 * frequently different patches of ocean, and writing them into one row would
 * silently splice two unrelated zones into a fake "trend". Cell identity keeps a
 * cell's own score series intact, and lets [trend] match cells honestly.
 *
 * `mode` and `size_class` are part of the key for the same reason they are part
 * of the API response: a bluefin feeding score and a bluefin spawning score are
 * near-inverted models against different thermal envelopes, so a trend that mixed
 * them would be a trend of a species that does not exist.
 *
 * ## What a NULL means here
 *
 * `pfz` is nullable and null means "not scoreable" — a species outside its
 * habitat, or a data gap. It is never 0. [trend] therefore reports unscored days
 * as *absent* days rather than zeros, because a run of zeros reads as "consistently
 * bad ground" when it actually means "we could not see it".
 */
object PfzZoneStore {
    private val logger = LoggerFactory.getLogger(PfzZoneStore::class.java)
    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    /** One persisted zone row. Mirrors the columns of `pfz_zones_daily`. */
    data class Row(
        val localDate: String,
        val anchorLat: Double,
        val anchorLon: Double,
        val speciesId: String,
        val mode: String,
        val sizeClass: String,
        val cellLat: Double,
        val cellLon: Double,
        val zoneName: String,
        val rank: Int,
        val status: String,
        val pfz: Int?,
        val confidence: Int,
        val frontKm: Double?,
        val depthM: Double?,
        val sstGradientCkm: Double?,
        val chlaGradient: Double?,
        val sstAnomalyC: Double?,
        val region: String?,
        val polygonJson: String?,
        val driversJson: String?,
        val blockersJson: String?,
        val missingFactorsJson: String?,
        val lowConfidenceJson: String?,
        val persistedAt: Instant
    )

    /**
     * One day of a species' zone history at one anchor.
     *
     * [days] is chronological. A date absent from the list is a date the persist
     * job never produced — see [fromRows] for why that is not silently filled.
     */
    data class Day(
        val date: String,
        val zones: List<Row>,
        /**
         * Confidence of the day's best *scoreable* zone, or 0 when nothing scored.
         *
         * Taken over scoreable zones only, for the same reason [topPfz] is: a day
         * whose every zone refused to score cannot also be claiming high
         * confidence. A day of `insufficient_data` zones is a day we were blind.
         */
        val confidence: Int = zones
            .filter { it.status == SCOREABLE }
            .maxOfOrNull { it.confidence }
            ?: 0
    ) {
        /** Best scoreable zone for the day, or null when none was scoreable. */
        val topPfz: Int? get() = zones.filter { it.status == SCOREABLE }
            .maxOfOrNull { it.pfz ?: -1 }
            ?.takeIf { it >= 0 }

        /** Day-over-day change in [topPfz]; null unless both days are scoreable. */
        fun deltaFrom(previous: Day?): Int? {
            val now = topPfz ?: return null
            val before = previous?.topPfz ?: return null
            return now - before
        }
    }

    /** A cell's score across the days it was present, with its own trend. */
    data class CellSeries(
        val cellLat: Double,
        val cellLon: Double,
        val points: List<Point>
    ) {
        val firstPfz: Int? get() = points.firstOrNull()?.pfz
        val lastPfz: Int? get() = points.lastOrNull()?.pfz

        /**
         * Net movement across the series, or null when there is nothing to compare.
         *
         * Null on three counts, all of which mean "no trend":
         *  - a single observation is a level, not a change, and last-minus-first
         *    over one point is a misleading 0 rather than an honest null;
         *  - either end unscored (see [PfzZoneStore.Day.topPfz] for why unscored
         *    is not 0);
         *  - the two ends being the same day, which happens when a day was
         *    re-persisted within one window.
         */
        val change: Int? get() {
            if (points.size < 2) return null
            if (points.first().date == points.last().date) return null
            val a = firstPfz ?: return null
            val b = lastPfz ?: return null
            return b - a
        }
    }

    data class Point(
        val date: String,
        val pfz: Int?,
        val confidence: Int,
        val rank: Int
    )

    /** A full trend for one (anchor, species, mode, size class) request. */
    data class Trend(
        val anchorLat: Double,
        val anchorLon: Double,
        val speciesId: String,
        val mode: String,
        val sizeClass: String,
        val days: List<Day>,
        val series: List<CellSeries>,
        val coverageNotes: List<String> = emptyList()
    ) {
        /**
         * Best score seen on the most recent day that had one.
         *
         * Scans backwards rather than reading the last day, because the last
         * persisted day may have no scoreable zone: reporting 0 there would turn
         * "we could not see it" into "bad ground".
         */
        val latestTopPfz: Int? get() = days.lastOrNull { it.topPfz != null }?.topPfz

        /** Day-over-day change of [latestTopPfz]; null with fewer than two scored days. */
        val dayOverDay: Int? get() {
            val scored = days.filter { it.topPfz != null }
            return if (scored.size < 2) null else scored.last().topPfz!! - scored[scored.size - 2].topPfz!!
        }
    }

    const val SCOREABLE = "scoreable"
    const val UNAVAILABLE = "unavailable"
    const val INSUFFICIENT_DATA = "insufficient_data"

    /** The applied mode/size class, normalised to what the key stores. */
    fun normaliseMode(mode: String?): String = mode?.trim()?.takeIf { it.isNotEmpty() } ?: "default"
    fun normaliseSizeClass(sizeClass: String?): String = sizeClass?.trim()?.takeIf { it.isNotEmpty() } ?: "default"

    /**
     * Idempotent DDL.
     *
     * Mirrors [com.shaka.data.cache.SpotDataCache.createTableIfNotExists]: the
     * table is created at boot rather than relying on a migration having run, so
     * a fresh environment is not a runtime error on the first write. Same DDL is
     * mirrored in db/init.sql for fresh-database provisioning.
     */
    fun createTablesIfNotExists() {
        if (!DatabaseFactory.isConnected()) {
            logger.info("Database not connected, skipping pfz_zones_daily table creation")
            return
        }
        try {
            transaction {
                val conn = this.connection.connection as java.sql.Connection
                conn.createStatement().use { stmt ->
                    stmt.execute(DDL.trimIndent().split(";").filter { it.isNotBlank() }.joinToString(";\n"))
                }
            }
            logger.info("pfz_zones_daily table ready")
        } catch (e: Exception) {
            logger.warn("Failed to create pfz_zones_daily table: ${e.message}")
        }
    }

    private val DDL = """
        CREATE TABLE IF NOT EXISTS pfz_zones_daily (
            id BIGSERIAL PRIMARY KEY,
            local_date DATE NOT NULL,
            anchor_lat DOUBLE PRECISION NOT NULL,
            anchor_lon DOUBLE PRECISION NOT NULL,
            species_id VARCHAR(50) NOT NULL,
            mode VARCHAR(50) NOT NULL DEFAULT 'default',
            size_class VARCHAR(30) NOT NULL DEFAULT 'default',
            cell_lat DOUBLE PRECISION NOT NULL,
            cell_lon DOUBLE PRECISION NOT NULL,
            zone_name VARCHAR(100),
            rank INTEGER NOT NULL,
            status VARCHAR(24) NOT NULL,
            pfz INTEGER,
            confidence INTEGER NOT NULL DEFAULT 0,
            front_km DOUBLE PRECISION,
            depth_m DOUBLE PRECISION,
            sst_gradient_ckm DOUBLE PRECISION,
            chla_gradient DOUBLE PRECISION,
            sst_anomaly_c DOUBLE PRECISION,
            region VARCHAR(50),
            polygon_json TEXT,
            drivers_json TEXT,
            blockers_json TEXT,
            missing_factors_json TEXT,
            low_confidence_json TEXT,
            persisted_at TIMESTAMP NOT NULL DEFAULT NOW(),
            UNIQUE (local_date, anchor_lat, anchor_lon, species_id, mode, size_class, cell_lat, cell_lon)
        )
    """

    /**
     * Persist one ranked zone response.
     *
     * Returns the number of rows written, or 0 when the response carried no zones.
     *
     * A response with zero zones is *not* written as a row. There is no zone to
     * describe, and inventing a placeholder row would make an unresolved day
     * look like a day the model was asked about and answered. The day is simply
     * absent, which [trend] surfaces as a gap.
     *
     * Re-running the same day is safe: rows are keyed on the same columns, so a
     * second persist of a re-scored day updates in place rather than duplicating.
     * `rank` and `zone_name` are refreshed too, since they are display fields and
     * the cell identity is what stays constant.
     */
    fun saveZoneResponse(response: PfzZonesResponse, mode: String?, sizeClass: String?): Int {
        if (!DatabaseFactory.isConnected()) return 0
        if (response.zones.isEmpty()) return 0

        val appliedMode = normaliseMode(mode)
        val appliedSize = normaliseSizeClass(sizeClass)

        return try {
            transaction {
                val conn = this.connection.connection as java.sql.Connection
                conn.prepareStatement(INSERT_SQL).use { stmt ->
                    val now = Timestamp.from(Instant.now())
                    response.zones.forEach { zone ->
                        // The column order and the bind order are both driven by
                        // this one list, so a new column cannot be added to the
                        // INSERT without also being bound here. Hand-numbered
                        // indices are what let the first version of this statement
                        // drift by one and write every value into the wrong column.
                        var i = 1
                        fun bind(value: Any?) {
                            when (value) {
                                null -> stmt.setNull(i, java.sql.Types.NULL)
                                is String -> stmt.setString(i, value)
                                is Int -> stmt.setInt(i, value)
                                is Double -> stmt.setDouble(i, value)
                                is Timestamp -> stmt.setTimestamp(i, value)
                                else -> stmt.setObject(i, value)
                            }
                            i++
                        }

                        bind(response.date)
                        bind(response.lat)
                        bind(response.lon)
                        bind(response.speciesId)
                        bind(appliedMode)
                        bind(appliedSize)
                        bind(zone.cellLat)
                        bind(zone.cellLon)
                        bind(zone.name)
                        bind(zone.rank)
                        bind(zone.status.wire())
                        bind(zone.pfz)
                        bind(zone.confidence)
                        bind(zone.frontKm)
                        bind(zone.depthM)
                        bind(zone.sstGradientCkm)
                        bind(zone.chlaGradientMgM3km)
                        bind(zone.sstAnomalyC)
                        bind(zone.region)
                        bind(polygonJson(zone))
                        bind(json.encodeToString(zone.drivers))
                        bind(json.encodeToString(zone.blockers))
                        bind(json.encodeToString(zone.missingFactors))
                        bind(json.encodeToString(zone.lowConfidenceFactors))
                        bind(now)

                        // A column added to the INSERT without a bind shows up here
                        // rather than as a runtime "invalid parameter number" that
                        // silently drops the rest of the row.
                        if (i - 1 != INSERT_COLUMN_COUNT) {
                            throw IllegalStateException(
                                "pfz_zones_daily INSERT binds ${i - 1} values but declares " +
                                    "$INSERT_COLUMN_COUNT columns"
                            )
                        }
                        stmt.addBatch()
                    }
                    stmt.executeBatch().sum()
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to persist ${response.zones.size} PFZ zones for ${response.speciesId}/${response.date}: ${e.message}")
            0
        }
    }

    /**
     * Number of columns in [INSERT_SQL], which is the number of bind parameters it
     * takes. Asserted at bind time so the two cannot drift apart.
     */
    private const val INSERT_COLUMN_COUNT = 25

    private val INSERT_SQL = """
        INSERT INTO pfz_zones_daily
            (local_date, anchor_lat, anchor_lon, species_id, mode, size_class,
             cell_lat, cell_lon, zone_name, rank, status, pfz, confidence,
             front_km, depth_m, sst_gradient_ckm, chla_gradient, sst_anomaly_c,
             region, polygon_json, drivers_json, blockers_json,
             missing_factors_json, low_confidence_json, persisted_at)
        VALUES (?::date, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (local_date, anchor_lat, anchor_lon, species_id, mode, size_class, cell_lat, cell_lon)
        DO UPDATE SET
            zone_name = EXCLUDED.zone_name,
            rank = EXCLUDED.rank,
            status = EXCLUDED.status,
            pfz = EXCLUDED.pfz,
            confidence = EXCLUDED.confidence,
            front_km = EXCLUDED.front_km,
            depth_m = EXCLUDED.depth_m,
            sst_gradient_ckm = EXCLUDED.sst_gradient_ckm,
            chla_gradient = EXCLUDED.chla_gradient,
            sst_anomaly_c = EXCLUDED.sst_anomaly_c,
            region = EXCLUDED.region,
            polygon_json = EXCLUDED.polygon_json,
            drivers_json = EXCLUDED.drivers_json,
            blockers_json = EXCLUDED.blockers_json,
            missing_factors_json = EXCLUDED.missing_factors_json,
            low_confidence_json = EXCLUDED.low_confidence_json,
            persisted_at = EXCLUDED.persisted_at
    """.trimIndent()

    private fun setNullableDouble(stmt: java.sql.PreparedStatement, index: Int, value: Double?) {
        if (value != null) stmt.setDouble(index, value) else stmt.setNull(index, java.sql.Types.DOUBLE)
    }

    /** Ring coordinates as compact JSON, or null when the zone has no drawable outline. */
    private fun polygonJson(zone: PfzZone): String? =
        if (zone.polygon.size < 3) null
        else json.encodeToString(zone.polygon.map { listOf(it.lat, it.lon) })

    private fun PfzStatus.wire(): String = when (this) {
        PfzStatus.SCOREABLE -> SCOREABLE
        PfzStatus.UNAVAILABLE -> UNAVAILABLE
        PfzStatus.INSUFFICIENT_DATA -> INSUFFICIENT_DATA
    }

    /**
     * Read the persisted history for one request, newest day last.
     *
     * Returns an empty list when no DB is connected. The caller must be able to
     * render "no history yet" from that — which is a true statement, not a
     * failure, since history only exists once the persist job has run.
     */
    fun loadHistory(
        anchorLat: Double,
        anchorLon: Double,
        speciesId: String,
        mode: String?,
        sizeClass: String?,
        from: LocalDate,
        to: LocalDate
    ): List<Row> {
        if (!DatabaseFactory.isConnected()) return emptyList()
        return try {
            transaction {
                val conn = this.connection.connection as java.sql.Connection
                conn.prepareStatement(
                    """
                    SELECT * FROM pfz_zones_daily
                    WHERE anchor_lat = ? AND anchor_lon = ?
                      AND species_id = ? AND mode = ? AND size_class = ?
                      AND local_date BETWEEN ?::date AND ?::date
                    ORDER BY local_date, rank
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setDouble(1, anchorLat)
                    stmt.setDouble(2, anchorLon)
                    stmt.setString(3, speciesId)
                    stmt.setString(4, normaliseMode(mode))
                    stmt.setString(5, normaliseSizeClass(sizeClass))
                    stmt.setString(6, from.toString())
                    stmt.setString(7, to.toString())
                    stmt.executeQuery(                ).use { rs ->
                        val rows = mutableListOf<Row>()
                        while (rs.next()) {
                            rows += readRow(rs)
                        }
                        rows
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to load PFZ zone history for $speciesId: ${e.message}")
            emptyList()
        }
    }

    private fun readRow(rs: java.sql.ResultSet) = Row(
        localDate = rs.getDate("local_date").toString(),
        anchorLat = rs.getDouble("anchor_lat"),
        anchorLon = rs.getDouble("anchor_lon"),
        speciesId = rs.getString("species_id"),
        mode = rs.getString("mode"),
        sizeClass = rs.getString("size_class"),
        cellLat = rs.getDouble("cell_lat"),
        cellLon = rs.getDouble("cell_lon"),
        zoneName = rs.getString("zone_name") ?: "",
        rank = rs.getInt("rank"),
        status = rs.getString("status"),
        pfz = rs.getInt("pfz").takeIf { !rs.wasNull() },
        confidence = rs.getInt("confidence"),
        frontKm = rs.getDouble("front_km").takeIf { !rs.wasNull() },
        depthM = rs.getDouble("depth_m").takeIf { !rs.wasNull() },
        sstGradientCkm = rs.getDouble("sst_gradient_ckm").takeIf { !rs.wasNull() },
        chlaGradient = rs.getDouble("chla_gradient").takeIf { !rs.wasNull() },
        sstAnomalyC = rs.getDouble("sst_anomaly_c").takeIf { !rs.wasNull() },
        region = rs.getString("region"),
        polygonJson = rs.getString("polygon_json"),
        driversJson = rs.getString("drivers_json"),
        blockersJson = rs.getString("blockers_json"),
        missingFactorsJson = rs.getString("missing_factors_json"),
        lowConfidenceJson = rs.getString("low_confidence_json"),
        persistedAt = rs.getTimestamp("persisted_at")?.toInstant() ?: Instant.EPOCH
    )

    /**
     * Group flat rows into days, in chronological order.
     *
     * Public and pure so the grouping is testable without a database — the
     * interesting question is not the SQL, it is whether a day with no
     * scoreable zone is represented honestly.
     */
    fun fromRows(rows: List<Row>): List<Day> =
        rows.groupBy { it.localDate }
            .toSortedMap()
            .map { (date, dayRows) -> Day(date = date, zones = dayRows.sortedBy { it.rank }) }

    /**
     * Build the per-cell score series and the honest coverage notes.
     *
     * The notes exist because a trend is a claim about time, and the ways this
     * data can be incomplete are not visible from the numbers alone.
     */
    fun trend(rows: List<Row>, anchorLat: Double, anchorLon: Double, speciesId: String, mode: String?, sizeClass: String?): Trend {
        // Defensive filter, even though loadHistory already applies it in SQL.
        // The model context is part of the key, so a feeding series and a spawning
        // series of the same species and cell are different measurements. If a
        // caller ever hands this a mixed list, silently interleaving them would
        // produce a trend for a species that does not exist.
        val appliedMode = normaliseMode(mode)
        val appliedSize = normaliseSizeClass(sizeClass)
        val scoped = rows.filter { it.mode == appliedMode && it.sizeClass == appliedSize }
        val days = fromRows(scoped)
        val series = scoped
            .groupBy { it.cellLat to it.cellLon }
            .toSortedMap(compareBy({ it.first }, { it.second }))
            .map { (cell, cellRows) ->
                CellSeries(
                    cellLat = cell.first,
                    cellLon = cell.second,
                    points = cellRows
                        .sortedBy { it.localDate }
                        .map { Point(date = it.localDate, pfz = it.pfz, confidence = it.confidence, rank = it.rank) }
                )
            }

        val notes = mutableListOf<String>()
        val scoredDays = days.count { it.topPfz != null }
        if (days.isEmpty()) {
            notes += "No PFZ zone history has been persisted for this location, species and model " +
                "context yet. History is written by a daily job, so a new species or location " +
                "starts empty and fills in on later days."
        } else {
            notes += "$scoredDays of the last ${days.size} persisted day(s) produced a scoreable " +
                "zone. Days with no scoreable zone are gaps, not zeros: the species was outside " +
                "its habitat or a required measurement did not resolve, and neither is a score of 0."
        }
        series.firstOrNull()?.let { first ->
            val changing = series.count { it.change != null }
            if (changing > 0) {
                notes += "Trend is per grid cell, not per rank. Ranks are re-assigned every day, so " +
                    "rank 1 today and rank 1 tomorrow are usually different patches of ocean; $changing " +
                    "cell(s) of ${series.size} have a scored value on both the first and last day " +
                    "persisted, which is the only comparison the data supports."
            }
            if (first.points.any { it.pfz == null }) {
                notes += "A cell can be scored on some days and not on others when a required factor " +
                    "such as bottom temperature or bottom salinity did not resolve. Those days are " +
                    "left blank rather than interpolated."
            }
        }
        return Trend(
            anchorLat = anchorLat,
            anchorLon = anchorLon,
            speciesId = speciesId,
            mode = appliedMode,
            sizeClass = appliedSize,
            days = days,
            series = series,
            coverageNotes = notes
        )
    }

    /**
     * Delete history older than [cutoff].
     *
     * Called from the same nightly cleanup as the tide/swell rows. The table is
     * keyed on a moving request, so without a retention window it grows without
     * bound as users explore new species and locations.
     */
    fun deleteOlderThan(cutoff: LocalDate): Int {
        if (!DatabaseFactory.isConnected()) return 0
        return try {
            transaction {
                val conn = this.connection.connection as java.sql.Connection
                conn.prepareStatement("DELETE FROM pfz_zones_daily WHERE local_date < ?::date").use { stmt ->
                    stmt.setString(1, cutoff.toString())
                    stmt.executeUpdate()
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to prune old PFZ zone history: ${e.message}")
            0
        }
    }

    /**
     * Truncate and rewrite one day for a request.
     *
     * Used by the daily job: a zone that scored yesterday's rank 1 and is absent
     * today must disappear, and an upsert alone cannot express "this cell no
     * longer exists". Deleting the day's rows for this exact request key first
     * makes the write a full replacement, and the unique key keeps it idempotent
     * if the job runs twice.
     */
    fun replaceDay(
        response: PfzZonesResponse,
        mode: String?,
        sizeClass: String?
    ): Int {
        if (!DatabaseFactory.isConnected()) return 0
        deleteDay(response.date, response.lat, response.lon, response.speciesId, mode, sizeClass)
        return saveZoneResponse(response, mode, sizeClass)
    }

    fun deleteDay(
        localDate: String,
        anchorLat: Double,
        anchorLon: Double,
        speciesId: String,
        mode: String?,
        sizeClass: String?
    ): Int {
        if (!DatabaseFactory.isConnected()) return 0
        return try {
            transaction {
                val conn = this.connection.connection as java.sql.Connection
                conn.prepareStatement(
                    """
                    DELETE FROM pfz_zones_daily
                    WHERE local_date = ?::date AND anchor_lat = ? AND anchor_lon = ?
                      AND species_id = ? AND mode = ? AND size_class = ?
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setString(1, localDate)
                    stmt.setDouble(2, anchorLat)
                    stmt.setDouble(3, anchorLon)
                    stmt.setString(4, speciesId)
                    stmt.setString(5, normaliseMode(mode))
                    stmt.setString(6, normaliseSizeClass(sizeClass))
                    stmt.executeUpdate()
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to clear PFZ day $localDate for $speciesId: ${e.message}")
            0
        }
    }

    /**
     * Coordinates the daily job should re-score.
     *
     * Derived from what has actually been asked for: every distinct
     * (anchor, species, mode, size class) already in the table. A job that
     * invented its own grid of boxes would be writing rows nobody ever queries,
     * and would be spending Copernicus quota on answers no user can reach.
     */
    fun trackedRequests(limit: Int = 200): List<Request> {
        if (!DatabaseFactory.isConnected()) return emptyList()
        return try {
            transaction {
                val conn = this.connection.connection as java.sql.Connection
                conn.prepareStatement(
                    """
                    SELECT anchor_lat, anchor_lon, species_id, mode, size_class, max(local_date) AS last_date
                    FROM pfz_zones_daily
                    GROUP BY anchor_lat, anchor_lon, species_id, mode, size_class
                    ORDER BY max(local_date) DESC
                    LIMIT ?
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setInt(1, limit)
                    stmt.executeQuery().use { rs ->
                        val out = mutableListOf<Request>()
                        while (rs.next()) {
                            out += Request(
                                anchorLat = rs.getDouble("anchor_lat"),
                                anchorLon = rs.getDouble("anchor_lon"),
                                speciesId = rs.getString("species_id"),
                                mode = rs.getString("mode").takeIf { it != "default" },
                                sizeClass = rs.getString("size_class").takeIf { it != "default" },
                                lastPersistedDate = rs.getDate("last_date")?.toString()
                            )
                        }
                        out
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to list tracked PFZ requests: ${e.message}")
            emptyList()
        }
    }

    /** A (anchor, species, model context) request the daily job will re-score. */
    data class Request(
        val anchorLat: Double,
        val anchorLon: Double,
        val speciesId: String,
        val mode: String?,
        val sizeClass: String?,
        val lastPersistedDate: String?
    )

    /** Days in the retention window, kept in one place so the job and the test agree. */
    const val RETENTION_DAYS = 120L
    const val MAX_TRACKED_REQUESTS = 200
}
