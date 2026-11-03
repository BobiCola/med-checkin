package com.luge.medreminder

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.sql.DriverManager

/** Validates MIGRATION_1_2 against Room's exported schemas (app/schemas) using a real SQLite. */
class MigrationTest {
    private fun schema(v: Int) = JSONObject(File("schemas/com.luge.medreminder.AppDb/$v.json").readText()).getJSONObject("database")

    private fun createSql(db: JSONObject) = (0 until db.getJSONArray("entities").length()).flatMap { i ->
        val e = db.getJSONArray("entities").getJSONObject(i); val t = e.getString("tableName")
        listOf(e.getString("createSql").replace("\${TABLE_NAME}", t)) +
            e.optJSONArray("indices").let { a -> if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}", t) } }
    }

    private fun expectedColumns(db: JSONObject) = (0 until db.getJSONArray("entities").length()).associate { i ->
        val e = db.getJSONArray("entities").getJSONObject(i)
        e.getString("tableName") to (0 until e.getJSONArray("fields").length()).map { j ->
            val f = e.getJSONArray("fields").getJSONObject(j); Triple(f.getString("columnName"), f.getString("affinity"), f.getBoolean("notNull"))
        }.toSet()
    }

    @Test fun migrate1to2KeepsDataAndMatchesSchema() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            val st = c.createStatement()
            createSql(schema(1)).forEach { st.execute(it) }
            st.execute("INSERT INTO medicine VALUES ('m1','维生素','1片','2026-11-01',NULL,'DAILY',2,127,0)")
            st.execute("INSERT INTO dose_slot VALUES ('s1','m1','早',9,0,0)")
            st.execute("INSERT INTO intake_record VALUES ('m1|s1|2026-11-02','m1','s1','2026-11-02',540,'早','TAKEN',1,'')")

            MIGRATION_1_2_SQL.forEach { st.execute(it) }

            val v2 = schema(2)
            assertEquals(2, v2.getInt("version"))
            expectedColumns(v2).forEach { (table, cols) ->
                val rs = st.executeQuery("PRAGMA table_info(`$table`)")
                val actual = mutableSetOf<Triple<String, String, Boolean>>()
                while (rs.next()) actual += Triple(rs.getString("name"), rs.getString("type"), rs.getInt("notnull") == 1)
                assertEquals("columns of $table", cols, actual)
            }
            st.executeQuery("SELECT name, winBefore, winAfter FROM medicine").use { rs ->
                assertTrue(rs.next()); assertEquals("维生素", rs.getString(1)); rs.getInt(2); assertTrue(rs.wasNull())
            }
            st.executeQuery("SELECT COUNT(*) FROM intake_record").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
            st.executeQuery("SELECT value FROM app_setting WHERE key='winBefore'").use { rs -> rs.next(); assertEquals(30, rs.getInt(1)) }
            st.executeQuery("SELECT value FROM app_setting WHERE key='winAfter'").use { rs -> rs.next(); assertEquals(60, rs.getInt(1)) }
        }
    }
}
