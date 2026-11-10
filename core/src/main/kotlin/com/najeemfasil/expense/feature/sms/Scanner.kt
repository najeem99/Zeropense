package com.najeemfasil.expense.feature.sms

import com.najeemfasil.expense.data.db.Conn
import com.najeemfasil.expense.data.db.Db
import com.najeemfasil.expense.data.db.SettingKey
import com.najeemfasil.expense.data.db.getSetting
import com.najeemfasil.expense.data.db.setSetting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** True once SMS setup (or a custom format) has configured something to import. */
fun isSmsConfigured(db: Conn): Boolean = listSmsRules(db).isNotEmpty() || listCustomParsers(db).isNotEmpty()

fun getLastScanRunAt(db: Conn): Long? = getSetting(db, SettingKey.LAST_SMS_SCAN_RUN_AT)?.toLongOrNull()

const val SCAN_FALLBACK_MS = 24L * 60 * 60 * 1000

/**
 * Reads new inbox messages into Review (FSD §7.5). Concurrent [scan] calls share one run;
 * [progress] holds the running total while a scan is going, null otherwise.
 */
class SmsScanner(
    private val db: Db,
    private val inbox: SmsInbox,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val lock = Any()
    private var running: Deferred<ImportSummary>? = null

    private val _progress = MutableStateFlow<ImportSummary?>(null)
    val progress: StateFlow<ImportSummary?> = _progress.asStateFlow()

    /** Starts a scan, or joins the one already running. */
    suspend fun scan(): ImportSummary = start().await()

    fun start(): Deferred<ImportSummary> = synchronized(lock) {
        running ?: scope.async { doScan() }.also { job ->
            running = job
            job.invokeOnCompletion { synchronized(lock) { if (running === job) running = null } }
        }
    }

    /** Lets setup and reset wait for a running scan; its errors don't matter to them. */
    suspend fun waitForScan() {
        val job = synchronized(lock) { running } ?: return
        runCatching { job.await() }
    }

    private suspend fun doScan(): ImportSummary {
        var total = ImportSummary()
        if (!inbox.hasPermission() || !db.read { isSmsConfigured(it) }) return total
        val last = db.read { getSetting(it, SettingKey.LAST_SMS_SCAN_AT) }?.toLongOrNull()
        // Never fall back to the whole inbox: without a marker, only the last day.
        var after = last ?: (now() - SCAN_FALLBACK_MS)
        try {
            _progress.value = total
            while (true) {
                val from = after
                val page = withContext(io) { inbox.readInbox(from, INBOX_PAGE_SIZE, null) }
                if (page.isEmpty()) break
                val msgs = page.map { RawSms(it.sender, it.body, it.date) }
                after = page.last().date
                val marker = after
                val s = db.write {
                    val r = importBatch(it, msgs)
                    setSetting(it, SettingKey.LAST_SMS_SCAN_AT, marker.toString())
                    r
                }
                total += s
                _progress.value = total
                if (page.size < INBOX_PAGE_SIZE) break
            }
            db.write { setSetting(it, SettingKey.LAST_SMS_SCAN_RUN_AT, now().toString()) }
        } finally {
            _progress.value = null
        }
        return total
    }
}
