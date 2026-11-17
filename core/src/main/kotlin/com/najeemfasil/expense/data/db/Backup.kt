package com.najeemfasil.expense.data.db

import java.io.File

/**
 * Backup files are plain copies of the database. [exportDatabase] writes a consistent snapshot
 * (including anything still in the WAL), [restoreDatabase] replaces every row with the rows of
 * a backup, upgrading an older backup first.
 */
class BackupException(message: String) : Exception(message)

data class RestoreResult(val transactions: Int, val people: Int, val debts: Int)

/** Writes a snapshot of the whole database to [dest] (overwritten). */
fun exportDatabase(db: Conn, dest: File) {
    dest.parentFile?.mkdirs()
    dest.delete()
    db.run("VACUUM INTO ?", dest.path)
}

private fun tables(db: Conn, schema: String): List<String> = db.query("SELECT name FROM $schema.sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'") {
    it.text(0)
}

private fun columns(db: Conn, schema: String, table: String): List<String> = db.query("PRAGMA $schema.table_info(\"$table\")") { it.text(1) }

/** True when the app holds anything the user entered or imported. */
fun hasUserData(db: Conn): Boolean = listOf("transactions", "accounts", "debt_people", "sms_rules").any { t ->
    (db.first("SELECT EXISTS (SELECT 1 FROM $t)") { it.int(0) } ?: 0) == 1
}

/**
 * Replaces all data with the backup at [source]. The file is checked and upgraded to the
 * current schema first (on a private copy), so a bad file changes nothing.
 */
fun restoreDatabase(db: Conn, source: File, workDir: File): RestoreResult {
    workDir.mkdirs()
    val copy = File(workDir, "restore-check.db")
    listOf("", "-wal", "-shm", "-journal").forEach { File(copy.path + it).delete() }
    source.copyTo(copy, overwrite = true)
    try {
        try {
            Db.openConnection(copy.path).use { src ->
                val ok = src.first("PRAGMA quick_check") { it.text(0) }
                if (ok != "ok") throw BackupException("The file is damaged.")
                val names = tables(src, "main")
                if ("transactions" !in names || "categories" !in names) {
                    throw BackupException("This isn’t a Zeropense backup.")
                }
                if (userVersion(src) > LATEST_VERSION) {
                    throw BackupException("This backup is from a newer version of the app. Update the app first.")
                }
                migrate(src)
                // Leave a single self-contained file for ATTACH.
                src.query("PRAGMA wal_checkpoint(TRUNCATE)") { }
                src.query("PRAGMA journal_mode = DELETE") { }
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("This file can’t be read as a backup.")
        }

        db.run("PRAGMA foreign_keys = OFF")
        db.run("ATTACH DATABASE ? AS src", copy.path)
        try {
            db.transaction {
                val srcTables = tables(db, "src").toSet()
                for (t in tables(db, "main")) {
                    db.run("DELETE FROM main.\"$t\"")
                    if (t !in srcTables) continue
                    val srcCols = columns(db, "src", t).toSet()
                    val cols = columns(db, "main", t).filter { it in srcCols }.joinToString(", ") { "\"$it\"" }
                    if (cols.isNotEmpty()) db.run("INSERT INTO main.\"$t\" ($cols) SELECT $cols FROM src.\"$t\"")
                }
            }
        } finally {
            db.run("DETACH DATABASE src")
            db.run("PRAGMA foreign_keys = ON")
        }
        fun count(t: String) = db.first("SELECT COUNT(*) FROM $t") { it.int(0) } ?: 0
        return RestoreResult(count("transactions"), count("debt_people"), count("debts"))
    } finally {
        listOf("", "-wal", "-shm", "-journal").forEach { File(copy.path + it).delete() }
    }
}
