package com.najeemfasil.expense.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.najeemfasil.expense.ui.theme.colors

enum class AlertStyle { Default, Cancel, Destructive }

class AlertButton(val text: String, val style: AlertStyle = AlertStyle.Default, val onClick: () -> Unit = {})

class AlertSpec(val title: String, val message: String? = null, val buttons: List<AlertButton>)

/** Shows alerts and confirmations, like `Alert.alert` in the Expo app. */
class Alerts {
    internal var current by mutableStateOf<AlertSpec?>(null)

    fun show(title: String, message: String? = null, buttons: List<AlertButton> = listOf(AlertButton("OK"))) {
        current = AlertSpec(title, message, buttons)
    }

    /** Cancel + one action. */
    fun confirm(
        title: String,
        message: String? = null,
        action: String,
        destructive: Boolean = false,
        onConfirm: () -> Unit
    ) = show(
        title,
        message,
        listOf(
            AlertButton("Cancel", AlertStyle.Cancel),
            AlertButton(action, if (destructive) AlertStyle.Destructive else AlertStyle.Default, onConfirm)
        )
    )
}

val LocalAlerts = staticCompositionLocalOf<Alerts> { error("No AlertHost") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlertHost(content: @Composable () -> Unit) {
    val alerts = remember { Alerts() }
    CompositionLocalProvider(LocalAlerts provides alerts, content = content)

    val spec = alerts.current ?: return
    val c = colors
    Dialog(onDismissRequest = { alerts.current = null }) {
        Surface(shape = RoundedCornerShape(20.dp), color = c.surface) {
            Column(
                Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(spec.title, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                if (!spec.message.isNullOrEmpty()) Text(spec.message, color = c.textMuted, fontSize = 15.sp)
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, androidx.compose.ui.Alignment.End)
                ) {
                    for (b in spec.buttons) {
                        TextButton(onClick = {
                            alerts.current = null
                            b.onClick()
                        }) {
                            Text(
                                b.text,
                                color = if (b.style == AlertStyle.Destructive) c.expense else c.primary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}
