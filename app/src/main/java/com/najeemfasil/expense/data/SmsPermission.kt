package com.najeemfasil.expense.data

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.najeemfasil.expense.ui.components.AlertButton
import com.najeemfasil.expense.ui.components.AlertStyle
import com.najeemfasil.expense.ui.components.LocalAlerts

/**
 * Asks for READ_SMS (FSD §2.1): returns a function that calls back with true at once when
 * already granted; otherwise shows the rationale (Allow / Not now) and then the system prompt.
 */
@Composable
fun rememberSmsPermissionRequest(): (onResult: (Boolean) -> Unit) -> Unit {
    val inbox = LocalSmsInbox.current
    val alerts = LocalAlerts.current
    val pending = remember { arrayOfNulls<(Boolean) -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending[0]?.invoke(granted)
        pending[0] = null
    }
    val currentLauncher = rememberUpdatedState(launcher)
    return remember(inbox, alerts) {
        { onResult ->
            if (inbox.hasPermission()) {
                onResult(true)
            } else {
                alerts.show(
                    "Read bank SMS",
                    "Zeropense reads your bank SMS to find transactions. Messages are processed on this phone and never uploaded.",
                    listOf(
                        AlertButton("Not now", AlertStyle.Cancel) { onResult(false) },
                        AlertButton("Allow") {
                            pending[0] = onResult
                            currentLauncher.value.launch(Manifest.permission.READ_SMS)
                        }
                    )
                )
            }
        }
    }
}
