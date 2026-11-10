package com.najeemfasil.expense.data.db

/** Keys of the `settings` table. */
enum class SettingKey(val key: String) {
    /** Receive time of the newest SMS the scanner has read. */
    LAST_SMS_SCAN_AT("lastSmsScanAt"),

    /** When a scan or setup import last finished. */
    LAST_SMS_SCAN_RUN_AT("lastSmsScanRunAt")
}

fun getSetting(db: Conn, key: SettingKey): String? = db.first("SELECT value FROM settings WHERE key = ?", key.key) { it.text(0) }

fun setSetting(db: Conn, key: SettingKey, value: String) {
    db.run(
        "INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value",
        key.key,
        value
    )
}
