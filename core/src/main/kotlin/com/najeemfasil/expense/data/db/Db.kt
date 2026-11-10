package com.najeemfasil.expense.data.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The app's database: one connection, used by one caller at a time, off the main thread.
 * Every [write] bumps [version], and [observe] re-runs its query, so screens refresh after any
 * change (the port of `notifyDbChanged` / `useDbQuery`).
 */
class Db private constructor(private val conn: Conn, private val dispatcher: CoroutineDispatcher) : AutoCloseable {
    private val mutex = Mutex()
    private val _version = MutableStateFlow(0L)

    /** Changes after every write. */
    val version: StateFlow<Long> = _version

    suspend fun <T> read(block: (Conn) -> T): T = withContext(dispatcher) { mutex.withLock { block(conn) } }

    suspend fun <T> write(block: (Conn) -> T): T = try {
        read(block)
    } finally {
        notifyChanged()
    }

    fun notifyChanged() {
        _version.value++
    }

    /** Runs [query] now and again after every write. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun <T> observe(query: (Conn) -> T): Flow<T> = version.mapLatest { read(query) }.distinctUntilChanged()

    override fun close() = conn.close()

    companion object {
        /** In-memory database name, for tests. */
        const val IN_MEMORY = ":memory:"

        /** Opens (creating or upgrading as needed) the database file at [path]. */
        fun open(path: String, dispatcher: CoroutineDispatcher = Dispatchers.IO): Db {
            val conn = openConnection(path)
            migrate(conn)
            return Db(conn, dispatcher)
        }

        fun openConnection(path: String): Conn = Conn(BundledSQLiteDriver().open(path))
    }
}
