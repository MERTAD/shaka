package com.shaka.pfz

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the one thing the pure-logic tests cannot reach: the agreement between
 * the `INSERT`'s column list and the values bound to its placeholders.
 *
 * The first version of [PfzZoneStore.saveZoneResponse] bound 26 values into 25
 * placeholders, which shifted every column from `anchor_lat` onward by one. Every
 * behavioural test still passed, because they all construct [PfzZoneStore.Row]
 * values by hand and never execute the statement. A row written to
 * `anchor_lon` while `species_id` received a latitude is not a loud failure; it
 * is a history table full of confidently wrong rows.
 *
 * So this asserts the shape of the statement directly, without a database: the
 * number of placeholders must equal the number of columns, and the declared
 * count the store checks at bind time must equal it too. If someone adds a
 * column and forgets the bind, or vice versa, this fails.
 */
class PfzZoneStoreSqlTest {

    private fun insertSql(): String {
        val field = PfzZoneStore::class.java.getDeclaredField("INSERT_SQL")
        field.isAccessible = true
        return field.get(PfzZoneStore) as String
    }

    private fun declaredColumnCount(): Int {
        val field = PfzZoneStore::class.java.getDeclaredField("INSERT_COLUMN_COUNT")
        field.isAccessible = true
        return field.getInt(null)
    }

    private fun columnsIn(sql: String): List<String> {
        val start = sql.indexOf("(")
        val end = sql.indexOf(")", start)
        return sql.substring(start + 1, end)
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    @Test
    fun `the insert binds exactly one placeholder per column`() {
        val sql = insertSql()
        val columns = columnsIn(sql)
        val placeholders = placeholdersInValuesClause(sql)

        assertEquals(
            columns.size,
            placeholders,
            "pfz_zones_daily INSERT has ${columns.size} columns but $placeholders " +
                "placeholders. A mismatch writes every value after the gap into the " +
                "wrong column, silently."
        )
    }

    /** The `?` characters between `VALUES (` and `ON CONFLICT`. */
    private fun placeholdersInValuesClause(sql: String): Int {
        val start = sql.indexOf("VALUES")
        val end = sql.indexOf("ON CONFLICT", start)
        require(start >= 0 && end > start) { "INSERT_SQL has no VALUES/ON CONFLICT clause" }
        return sql.substring(start, end).count { it == '?' }
    }

    @Test
    fun `the declared column count matches the statement`() {
        val columns = columnsIn(insertSql())
        assertEquals(
            declaredColumnCount(),
            columns.size,
            "INSERT_COLUMN_COUNT is the number asserted at bind time; it must " +
                "equal the real column count or the guard itself is wrong"
        )
    }

    @Test
    fun `every conflict target column is one of the inserted columns`() {
        val sql = insertSql()
        val columns = columnsIn(sql)
        val conflictLine = sql.lines().first { it.trim().startsWith("ON CONFLICT") }
        val targets = conflictLine
            .substringAfter("(")
            .substringBefore(")")
            .split(",")
            .map { it.trim() }

        for (target in targets) {
            assertTrue(
                target in columns,
                "ON CONFLICT target '$target' is not in the column list, so the " +
                    "upsert would silently create a duplicate instead of updating"
            )
        }
    }

    @Test
    fun `the date column is the one carrying the date cast`() {
        // `?::date` must bind the local_date, not the second column. A cast on the
        // wrong placeholder is the same class of bug as a wrong bind index, and it
        // fails at execute time rather than at compile time.
        val sql = insertSql()
        val castIndex = Regex("\\?::date").find(sql)!!.range.first
        val firstPlaceholder = sql.indexOf('?')
        assertEquals(
            firstPlaceholder,
            castIndex,
            "the ?::date cast must sit on the first placeholder (local_date)"
        )
    }
}
