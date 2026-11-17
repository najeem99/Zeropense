package com.najeemfasil.expense.feature.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import com.najeemfasil.expense.core.formatDateTime
import com.najeemfasil.expense.core.toDateInput
import com.najeemfasil.expense.data.Backups
import com.najeemfasil.expense.data.LocalBackups
import com.najeemfasil.expense.data.db.BackupException
import com.najeemfasil.expense.feature.sms.LocalSmsScanner
import com.najeemfasil.expense.ui.components.Body
import com.najeemfasil.expense.ui.components.Button
import com.najeemfasil.expense.ui.components.ButtonVariant
import com.najeemfasil.expense.ui.components.Card
import com.najeemfasil.expense.ui.components.Label
import com.najeemfasil.expense.ui.components.LocalAlerts
import com.najeemfasil.expense.ui.components.Screen
import com.najeemfasil.expense.ui.components.SpinnerCard
import kotlinx.coroutines.launch

/** Backup files that survive uninstalling, and restoring one (e.g. after a reinstall). */
@Composable
fun BackupScreen() {
    val backups = LocalBackups.current ?: return Screen { Body("Backups aren’t available here.", muted = true) }
    BackupContent(backups)
}

@Composable
private fun BackupContent(backups: Backups) {
    val alerts = LocalAlerts.current
    val scanner = LocalSmsScanner.current
    val scope = rememberCoroutineScope()
    // Preferences aren't observable; bump this to re-read them after an action.
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }

    fun run(label: String, block: suspend () -> Unit) {
        scope.launch {
            busy = label
            try {
                block()
            } catch (e: BackupException) {
                alerts.show("Couldn’t restore", e.message)
            } catch (e: Exception) {
                alerts.show("Something went wrong", e.message ?: "Try again, or pick another place.")
            } finally {
                busy = null
                refresh++
            }
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        if (tree != null) {
            run("Saving the backup…") {
                backups.setFolder(tree)
                backups.autoBackup()
                backups.lastError?.let { alerts.show("Couldn’t save in that folder", it) }
            }
        }
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Backups.MIME)) { uri: Uri? ->
        if (uri != null) {
            run("Saving the backup…") {
                backups.saveTo(uri)
                alerts.show("Backup saved", "Keep this file somewhere safe. After reinstalling, open Backup & restore and choose it.")
            }
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            alerts.confirm(
                "Replace all data with this backup?",
                "Everything in the app now (transactions, cards, categories, SMS setup, debts) is replaced by the backup.",
                action = "Restore",
                destructive = true
            ) {
                run("Restoring…") {
                    scanner.waitForScan()
                    val r = backups.restoreFrom(uri)
                    alerts.show(
                        "Backup restored",
                        "${r.transactions} transactions and ${r.people} people with debts (${r.debts} entries) are back."
                    )
                }
            }
        }
    }

    val folder = remember(refresh) { backups.folderName }
    val last = remember(refresh) { backups.lastBackupAt }
    val lastError = remember(refresh) { backups.lastError }

    Screen {
        busy?.let { SpinnerCard(it) }

        Card {
            Label("Automatic backup file")
            if (folder == null) {
                Body(
                    "Choose a folder on your phone (for example Documents). Zeropense then saves " +
                        "${Backups.FILE_NAME} there every time you leave the app. The file stays when the app is " +
                        "uninstalled, so you can restore it after installing again."
                )
                Button("Choose a folder", { pickFolder.launch(null) }, enabled = busy == null)
            } else {
                Body("Saving to: $folder")
                Body(
                    if (last > 0) "Last saved ${formatDateTime(last)}" else "Not saved yet",
                    muted = true,
                    fontSize = 14.sp
                )
                lastError?.let { Body("Last try failed: $it", muted = true, fontSize = 14.sp) }
                Button("Save now", { run("Saving the backup…") { backups.autoBackup() } }, enabled = busy == null)
                Button("Change folder", { pickFolder.launch(null) }, variant = ButtonVariant.Secondary, enabled = busy == null)
                Button("Stop automatic backup", {
                    backups.clearFolder()
                    refresh++
                }, variant = ButtonVariant.Ghost)
            }
        }

        Card {
            Label("Backup file")
            Body("Save a copy now, for example to Google Drive or to share with yourself on WhatsApp.")
            Button(
                "Save a backup file",
                { saveFile.launch("Zeropense-backup-${toDateInput(System.currentTimeMillis())}.db") },
                variant = ButtonVariant.Secondary,
                enabled = busy == null
            )
            Body("Reinstalled the app or got a new phone? Choose your backup file to bring everything back.")
            Button(
                "Restore from a backup file",
                { openFile.launch(arrayOf("*/*")) },
                variant = ButtonVariant.Secondary,
                enabled = busy == null
            )
        }

        Card {
            Label("Google account backup")
            Body(
                "Android also backs up Zeropense to your Google account when Backup is on in the phone’s settings. " +
                    "It runs about once a day while the phone is charging and on Wi-Fi, and is put back when the app " +
                    "is installed again on a phone with the same Google account. It can be up to a day old, so the " +
                    "backup file above is the surer way.",
                muted = true,
                fontSize = 14.sp
            )
        }
    }
}
