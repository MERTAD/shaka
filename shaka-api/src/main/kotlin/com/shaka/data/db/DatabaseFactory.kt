package com.shaka.data.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.*
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * Database connection factory using HikariCP for connection pooling.
 * Configures PostgreSQL + PostGIS for spatial queries.
 */
object DatabaseFactory {

    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)
    private var dataSource: HikariDataSource? = null

    init {
        // Bounds the raw DriverManager.getConnection calls (SpotDataCache
        // admin helpers) that otherwise block indefinitely on a hung DB.
        java.sql.DriverManager.setLoginTimeout(5)
    }

    /**
     * Initialize the database connection from application config.
     */
    fun init(config: ApplicationConfig) {
        val dbConfig = config.config("database")
        val url = dbConfig.property("url").getString()
        val driver = dbConfig.property("driver").getString()
        val user = dbConfig.property("user").getString()
        val password = dbConfig.propertyOrNull("password")?.getString() ?: ""

        logger.info("Initializing database connection to: ${url.substringBefore("?")}")

        val hikariConfig = HikariConfig().apply {
            jdbcUrl = url
            driverClassName = driver
            username = user
            this.password = password
            maximumPoolSize = 10
            minimumIdle = 2
            idleTimeout = 60000
            // Fail fast when the DB is unreachable. At 30s, Exposed's 3
            // transaction retries stacked into 90s+ request hangs during the
            // Jun 2026 Postgres outage; requests must error quickly instead.
            connectionTimeout = 5000
            validationTimeout = 3000
            maxLifetime = 1800000
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            // pgjdbc default socketTimeout is infinite: when Postgres hung on
            // Jun 3 2026, in-flight reads blocked forever and froze every
            // scheduled job mid-iteration for 8 days. 120s bounds any query.
            addDataSourceProperty("socketTimeout", "120")
            addDataSourceProperty("connectTimeout", "5")
            validate()
        }

        dataSource = HikariDataSource(hikariConfig)
        Database.connect(dataSource!!)

        // Create tables if they don't exist
        transaction {
            SchemaUtils.create(SpotsTable, ReportsTable, UserSpotsTable, LegalAcceptancesTable)
        }
        
        // Seed spots data if table is empty
        seedSpotsIfEmpty()

        logger.info("Database connection initialized successfully")
    }

    /**
     * Initialize with Railway DATABASE_URL format.
     * Parses: postgresql://user:password@host:port/database
     */
    fun init(url: String, userOverride: String, passwordOverride: String) {
        // Parse Railway URL format: postgresql://user:pass@host:port/database
        val parsed = parseRailwayUrl(url)
        
        val jdbcUrl = "jdbc:postgresql://${parsed.host}:${parsed.port}/${parsed.database}"
        val dbUser = userOverride.ifBlank { parsed.user }
        val dbPassword = passwordOverride.ifBlank { parsed.password }
        
        logger.info("Connecting to database at ${parsed.host}:${parsed.port}/${parsed.database}")
        
        val hikariConfig = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            driverClassName = "org.postgresql.Driver"
            username = dbUser
            this.password = dbPassword
            maximumPoolSize = 15
            minimumIdle = 2
            // Fail fast when the DB is unreachable (see init() above)
            connectionTimeout = 5000
            validationTimeout = 3000
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            addDataSourceProperty("socketTimeout", "120")
            addDataSourceProperty("connectTimeout", "5")
            validate()
        }

        dataSource = HikariDataSource(hikariConfig)
        Database.connect(dataSource!!)
        
        logger.info("Database connected, creating tables if needed...")

        transaction {
            SchemaUtils.create(SpotsTable, ReportsTable, UserSpotsTable, LegalAcceptancesTable)
        }
        
        // Seed spots data if table is empty
        seedSpotsIfEmpty()
        
        logger.info("Database initialization complete")
    }
    
    /**
     * Seed spots table from SpotDatabase if empty.
     * This ensures all 789 spots with corrected water coordinates are in PostgreSQL.
     */
    private fun seedSpotsIfEmpty() {
        transaction {
            val count = exec("SELECT COUNT(*) FROM spots") { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            } ?: 0L
            
            if (count == 0L) {
                logger.info("Spots table empty - seeding from SpotDatabase...")
                
                val spots = com.shaka.data.client.SpotDatabase.getAllSpots()
                var inserted = 0
                
                for (spot in spots) {
                    try {
                        val region = spot.id.split("-").firstOrNull()?.replaceFirstChar { it.uppercase() } ?: "Unknown"
                        val country = mapRegionToCountry(region)
                        
                        exec("""
                            INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
                            VALUES (
                                '${spot.name.replace("'", "''")}',
                                '${spot.description.replace("'", "''").take(500)}',
                                ${spot.coordinates.lat},
                                ${spot.coordinates.lon},
                                '$region',
                                '$country',
                                'shore',
                                ${spot.depth},
                                ${spot.depth}
                            )
                        """.trimIndent())
                        inserted++
                    } catch (e: Exception) {
                        logger.warn("Failed to insert spot ${spot.id}: ${e.message}")
                    }
                }
                
                logger.info("Seeded $inserted spots into PostgreSQL")
            } else {
                logger.info("Spots table has $count entries - skipping seed")
            }
        }
    }
    
    private fun mapRegionToCountry(region: String): String = when (region.lowercase()) {
        "oahu", "maui", "bigisland", "kauai", "molokai", "lanai", "cali", "florida", "carolinas", "texas", "gulf" -> "USA"
        "baja", "mexico", "yucatan", "cozumel" -> "Mexico"
        "fiji" -> "Fiji"
        "tahiti", "moorea", "fakarava", "rangiroa", "tikehau", "bora" -> "French Polynesia"
        "indonesia", "bali", "komodo", "raja" -> "Indonesia"
        "philippines", "palawan", "cebu" -> "Philippines"
        "thailand" -> "Thailand"
        "australia", "gbr" -> "Australia"
        "maldives" -> "Maldives"
        "bahamas" -> "Bahamas"
        "galapagos" -> "Ecuador"
        "cocos" -> "Costa Rica"
        else -> region
    }
    
    /**
     * Parse Railway DATABASE_URL format.
     * Format: postgresql://user:password@host:port/database
     */
    private data class DbConnectionInfo(
        val host: String,
        val port: Int,
        val database: String,
        val user: String,
        val password: String
    )
    
    private fun parseRailwayUrl(url: String): DbConnectionInfo {
        // Remove protocol prefix
        val withoutProtocol = url
            .removePrefix("postgresql://")
            .removePrefix("postgres://")
            .removePrefix("jdbc:postgresql://")
        
        // Split into credentials@hostInfo
        val atIndex = withoutProtocol.lastIndexOf("@")
        if (atIndex == -1) {
            throw IllegalArgumentException("Invalid DATABASE_URL format - missing @")
        }
        
        val credentials = withoutProtocol.substring(0, atIndex)
        val hostInfo = withoutProtocol.substring(atIndex + 1)
        
        // Parse credentials (user:password)
        val colonIndex = credentials.indexOf(":")
        val user = if (colonIndex != -1) credentials.substring(0, colonIndex) else credentials
        val password = if (colonIndex != -1) credentials.substring(colonIndex + 1) else ""
        
        // Parse hostInfo (host:port/database)
        val slashIndex = hostInfo.indexOf("/")
        val hostPort = if (slashIndex != -1) hostInfo.substring(0, slashIndex) else hostInfo
        val database = if (slashIndex != -1) hostInfo.substring(slashIndex + 1).substringBefore("?") else "railway"
        
        val portColonIndex = hostPort.lastIndexOf(":")
        val host = if (portColonIndex != -1) hostPort.substring(0, portColonIndex) else hostPort
        val port = if (portColonIndex != -1) hostPort.substring(portColonIndex + 1).toIntOrNull() ?: 5432 else 5432
        
        return DbConnectionInfo(host, port, database, user, password)
    }

    /**
     * Execute a database query within a coroutine context.
     */
    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    /**
     * Close the database connection pool.
     */
    fun close() {
        dataSource?.close()
        logger.info("Database connection pool closed")
    }

    /**
     * Check if database is connected.
     */
    fun isConnected(): Boolean = dataSource?.isRunning == true

    /**
     * Whether a database was asked for at all.
     *
     * Distinct from [isConnected] on purpose. `Application.tryInitDatabase`
     * falls back to in-memory when a configured database refuses to connect,
     * which leaves [isConnected] false in exactly the same state as an
     * installation that never wanted a database. Without this flag the health
     * endpoint cannot tell "no database here" from "the database is broken",
     * and reports `db: ok` for the second one while every write is dropped.
     */
    @Volatile
    private var configured: Boolean = false

    fun isConfigured(): Boolean = configured

    /**
     * Record that a database was requested, before any connection attempt.
     *
     * Called from `tryInitDatabase` so the flag is set even when the attempt
     * that follows throws.
     */
    fun markConfigured() {
        configured = true
    }

    /**
     * Configured but not connected: the app is running in-memory while
     * pretending it is persisted.
     */
    fun isDegraded(): Boolean = configured && !isConnected()
}
