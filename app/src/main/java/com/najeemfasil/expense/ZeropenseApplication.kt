package com.najeemfasil.expense

import android.app.Application
import com.najeemfasil.expense.data.db.Db
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

class ZeropenseApplication : Application() {
    /** Lives as long as the process: imports and scans keep running when a screen closes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Opened and migrated off the main thread as soon as the process starts. */
    lateinit var database: Deferred<Db>
        private set

    override fun onCreate() {
        super.onCreate()
        database = appScope.async(Dispatchers.IO) { Db.open(databaseFile().path) }
    }

    /**
     * Same file the Expo app used (expo-sqlite keeps databases in files/SQLite), so installing
     * this build over it keeps all data.
     */
    private fun databaseFile(): File = File(File(filesDir, "SQLite").apply { mkdirs() }, "expense.db")
}
