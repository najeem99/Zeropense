package com.najeemfasil.expense.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement

/** One result row. Columns are read by position, in the order of the SELECT. */
class Row(private val stmt: SQLiteStatement) {
    fun text(i: Int): String = stmt.getText(i)
    fun textOrNull(i: Int): String? = if (stmt.isNull(i)) null else stmt.getText(i)
    fun long(i: Int): Long = stmt.getLong(i)
    fun longOrNull(i: Int): Long? = if (stmt.isNull(i)) null else stmt.getLong(i)
    fun int(i: Int): Int = stmt.getLong(i).toInt()
}

/** A statement compiled once and run many times (bulk inserts). */
class Prepared internal constructor(private val conn: Conn, private val stmt: SQLiteStatement) : AutoCloseable {
    /** Runs the statement with [args] and returns the number of rows it changed. */
    fun run(vararg args: Any?): Int {
        stmt.reset()
        stmt.clearBindings()
        bind(stmt, args)
        while (stmt.step()) Unit
        return conn.changes()
    }

    override fun close() = stmt.close()
}

private fun bind(stmt: SQLiteStatement, args: Array<out Any?>) {
    args.forEachIndexed { index, value ->
        val i = index + 1
        when (value) {
            null -> stmt.bindNull(i)
            is String -> stmt.bindText(i, value)
            is Long -> stmt.bindLong(i, value)
            is Int -> stmt.bindLong(i, value.toLong())
            is Boolean -> stmt.bindLong(i, if (value) 1 else 0)
            is Double -> stmt.bindDouble(i, value)
            else -> throw IllegalArgumentException("Cannot bind ${value::class}")
        }
    }
}

/**
 * Thin synchronous wrapper over one SQLite connection. Repositories are plain functions on a
 * [Conn]; [Db] decides which thread they run on and tells observers after writes.
 */
class Conn(private val raw: SQLiteConnection) : AutoCloseable {
    private var depth = 0
    private val changesStmt by lazy { raw.prepare("SELECT changes()") }

    internal fun changes(): Int {
        changesStmt.reset()
        changesStmt.step()
        return changesStmt.getLong(0).toInt()
    }

    /** Runs one statement and returns the number of rows it changed. */
    fun run(sql: String, vararg args: Any?): Int {
        raw.prepare(sql).use { stmt ->
            bind(stmt, args)
            while (stmt.step()) Unit
        }
        return changes()
    }

    /** Runs a script of several statements separated by ";" (line comments allowed). */
    fun script(sql: String) {
        sql.lineSequence()
            .joinToString("\n") { it.substringBefore("--") }
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { run(it) }
    }

    fun <T> query(sql: String, vararg args: Any?, map: (Row) -> T): List<T> {
        raw.prepare(sql).use { stmt ->
            bind(stmt, args)
            val row = Row(stmt)
            val out = ArrayList<T>()
            while (stmt.step()) out.add(map(row))
            return out
        }
    }

    fun <T> first(sql: String, vararg args: Any?, map: (Row) -> T): T? {
        raw.prepare(sql).use { stmt ->
            bind(stmt, args)
            return if (stmt.step()) map(Row(stmt)) else null
        }
    }

    fun prepare(sql: String): Prepared = Prepared(this, raw.prepare(sql))

    /** All or nothing. Nested calls join the outer transaction. */
    fun <T> transaction(block: () -> T): T {
        if (depth > 0) return block()
        run("BEGIN")
        depth++
        try {
            val result = block()
            depth--
            run("COMMIT")
            return result
        } catch (e: Throwable) {
            depth = 0
            runCatching { run("ROLLBACK") }
            throw e
        }
    }

    override fun close() {
        runCatching { changesStmt.close() }
        raw.close()
    }
}
