package com.najeemfasil.expense.feature.sms

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.najeemfasil.expense.core.formatDateTime
import com.najeemfasil.expense.data.LocalSmsInbox
import com.najeemfasil.expense.data.rememberDbQuery
import com.najeemfasil.expense.ui.components.Body
import com.najeemfasil.expense.ui.components.Button
import com.najeemfasil.expense.ui.components.ButtonVariant
import com.najeemfasil.expense.ui.components.Card
import com.najeemfasil.expense.ui.components.Label
import com.najeemfasil.expense.ui.navigation.LocalNavigator
import com.najeemfasil.expense.ui.navigation.Routes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

val LocalSmsScanner = staticCompositionLocalOf<SmsScanner> { error("No SMS scanner") }

/** "Checked N new SMS · A transactions to review · …" (FSD §12.12). */
fun describeScan(s: ImportSummary): String {
    fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
    val parts = mutableListOf("Checked ${s.read} new SMS", "${plural(s.added, "transaction")} to review")
    if (s.duplicates > 0) parts += "${plural(s.duplicates, "possible duplicate")}"
    if (s.unread > 0) parts += "${s.unread} not understood"
    return parts.joinToString(" · ")
}

/**
 * Scans new SMS when the app first shows and on every return to the foreground (FSD §7.5),
 * only when the permission is already granted. Errors are logged, not shown. Mount once.
 */
@Composable
fun AutoInboxScan() {
    val scanner = LocalSmsScanner.current
    val inbox = LocalSmsInbox.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(owner, scanner, inbox) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && inbox.hasPermission()) {
                scope.launch {
                    try {
                        scanner.scan()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("Zeropense", "SMS scan failed", e)
                    }
                }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/** SMS status card on Overview and More (FSD §12.12). */
@Composable
fun ScanCard() {
    val nav = LocalNavigator.current
    val scanner = LocalSmsScanner.current
    val scope = rememberCoroutineScope()
    val configured by rememberDbQuery { isSmsConfigured(it) }
    val lastRun by rememberDbQuery { getLastScanRunAt(it) ?: 0L }
    val live by scanner.progress.collectAsState()
    var manual by remember { mutableStateOf<ImportSummary?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    when (configured) {
        null -> return
        false -> Card {
            Label("Set up your bank SMS")
            Body(
                "Pick how far back to read, which senders are your banks, which of their messages are transactions, " +
                    "and which card each one is. Only those messages are imported. Everything stays on this phone.",
                muted = true,
                fontSize = 14.sp
            )
            Button("Set up bank SMS", { nav.push(Routes.SMS_SETUP) })
        }
        true -> Card {
            Label("Bank SMS")
            val progress = live ?: manual
            val text = when {
                scanning -> "Checking new SMS…" + (progress?.let { " " + describeScan(it) } ?: "")
                manual != null -> describeScan(manual!!)
                (lastRun ?: 0L) > 0 -> "Last checked ${formatDateTime(lastRun!!)}. New bank SMS of the kinds you set up go to Review."
                else -> "New bank SMS of the kinds you set up go to Review."
            }
            Body(text, muted = true, fontSize = 14.sp)
            error?.let { Body("Check failed: $it", fontSize = 14.sp) }
            Button(
                if (scanning) "Checking…" else "Check now",
                {
                    scanning = true
                    error = null
                    scope.launch {
                        try {
                            manual = scanner.scan()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: e.toString()
                        } finally {
                            scanning = false
                        }
                    }
                },
                variant = ButtonVariant.Secondary,
                enabled = !scanning
            )
            Button("Edit SMS setup", { nav.push(Routes.SMS_SETUP) }, variant = ButtonVariant.Ghost)
        }
    }
}
