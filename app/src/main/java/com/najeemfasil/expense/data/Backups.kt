package com.najeemfasil.expense.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.staticCompositionLocalOf
import com.najeemfasil.expense.data.db.Db
import com.najeemfasil.expense.data.db.RestoreResult
import com.najeemfasil.expense.data.db.exportDatabase
import com.najeemfasil.expense.data.db.restoreDatabase
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Backup files outside the app, so data survives uninstalling: saved on demand to a place the
 * user picks, and written automatically to a chosen folder each time the app goes to the
 * background. Android's own backup to the Google account is configured in the manifest.
 */
class Backups(context: Context, private val db: Db) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("backup", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private var lastSavedVersion = -1L

    /** The folder for automatic backups, while the app still has access to it. */
    val folder: Uri?
        get() = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)?.takeIf { uri ->
            app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        }

    val folderName: String?
        get() = folder?.let { DocumentsContract.getTreeDocumentId(it).substringAfter(':').ifEmpty { "Phone storage" } }

    /** Time of the last automatic or manual backup file, 0 if none. */
    val lastBackupAt: Long get() = prefs.getLong(KEY_LAST_AT, 0)

    val lastError: String? get() = prefs.getString(KEY_ERROR, null)

    fun setFolder(tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        app.contentResolver.persistedUriPermissions.filter { it.uri != tree }.forEach {
            runCatching { app.contentResolver.releasePersistableUriPermission(it.uri, flags) }
        }
        app.contentResolver.takePersistableUriPermission(tree, flags)
        prefs.edit().putString(KEY_FOLDER, tree.toString()).apply()
        lastSavedVersion = -1
    }

    fun clearFolder() {
        folder?.let {
            runCatching {
                app.contentResolver.releasePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        prefs.edit().remove(KEY_FOLDER).apply()
    }

    private suspend fun snapshot(): File {
        val file = File(app.cacheDir, "backup/$FILE_NAME")
        db.read { exportDatabase(it, file) }
        return file
    }

    private fun copyTo(file: File, target: Uri) {
        val out = app.contentResolver.openOutputStream(target, "wt") ?: throw IOException("Can’t write the file")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    private fun markSaved(version: Long) {
        lastSavedVersion = version
        prefs.edit().putLong(KEY_LAST_AT, System.currentTimeMillis()).remove(KEY_ERROR).apply()
    }

    /** Saves a backup to [target] (a file the user picked). */
    suspend fun saveTo(target: Uri) = lock.withLock {
        val version = db.version.value
        val file = snapshot()
        withContext(Dispatchers.IO) { copyTo(file, target) }
        file.delete()
        markSaved(version)
    }

    /** Writes the backup file into the chosen folder if anything changed. Never throws. */
    suspend fun autoBackup() = lock.withLock {
        val tree = folder ?: return@withLock
        val version = db.version.value
        if (version == lastSavedVersion) return@withLock
        try {
            val file = snapshot()
            withContext(Dispatchers.IO) { copyTo(file, findOrCreate(tree)) }
            file.delete()
            markSaved(version)
        } catch (e: Exception) {
            prefs.edit().putString(KEY_ERROR, e.message ?: e.javaClass.simpleName).apply()
        }
    }

    private fun findOrCreate(tree: Uri): Uri {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        app.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == FILE_NAME) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
        }
        return DocumentsContract.createDocument(app.contentResolver, parent, MIME, FILE_NAME)
            ?: throw IOException("Can’t create $FILE_NAME in that folder")
    }

    /** Replaces all data with the backup file at [source]. Throws BackupException with a readable message. */
    suspend fun restoreFrom(source: Uri): RestoreResult = lock.withLock {
        val file = File(app.cacheDir, "backup/restore-source.db")
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val input = app.contentResolver.openInputStream(source) ?: throw IOException("Can’t open the file")
            input.use { i -> file.outputStream().use { i.copyTo(it) } }
        }
        try {
            db.write { restoreDatabase(it, file, File(app.cacheDir, "backup")) }
        } finally {
            file.delete()
        }
    }

    companion object {
        const val FILE_NAME = "Zeropense-backup.db"
        const val MIME = "application/octet-stream"
        private const val KEY_FOLDER = "folder"
        private const val KEY_LAST_AT = "lastAt"
        private const val KEY_ERROR = "error"
    }
}

val LocalBackups = staticCompositionLocalOf<Backups?> { null }
