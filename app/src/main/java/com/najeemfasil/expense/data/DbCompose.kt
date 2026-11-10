package com.najeemfasil.expense.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import com.najeemfasil.expense.data.db.Conn
import com.najeemfasil.expense.data.db.Db
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

val LocalDb = staticCompositionLocalOf<Db> { error("No database") }

/**
 * Runs [query] now and again after every write. [key] must change when the query's inputs
 * change (e.g. the selected month); the value is null while a new key loads, so the previous
 * month's rows are never shown under the new month.
 */
@Composable
fun <T> rememberDbQuery(key: Any? = Unit, query: (Conn) -> T): State<T?> {
    val db = LocalDb.current
    val current = rememberUpdatedState(query)
    return produceState<T?>(initialValue = null, db, key) {
        db.version.collectLatest { value = db.read { current.value(it) } }
    }
}

/** Runs a write off the main thread; observers refresh when it is done. */
@Composable
fun rememberDbWriter(): (block: (Conn) -> Unit) -> Unit {
    val db = LocalDb.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return androidx.compose.runtime.remember(db, scope) {
        { block -> scope.launch { db.write(block) } }
    }
}
