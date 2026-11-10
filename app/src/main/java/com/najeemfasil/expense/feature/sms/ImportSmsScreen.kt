package com.najeemfasil.expense.feature.sms

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.sp
import com.najeemfasil.expense.core.jsTrim
import com.najeemfasil.expense.data.LocalDb
import com.najeemfasil.expense.ui.components.Amount
import com.najeemfasil.expense.ui.components.Body
import com.najeemfasil.expense.ui.components.Button
import com.najeemfasil.expense.ui.components.ButtonVariant
import com.najeemfasil.expense.ui.components.Card
import com.najeemfasil.expense.ui.components.Chip
import com.najeemfasil.expense.ui.components.ChipRow
import com.najeemfasil.expense.ui.components.Field
import com.najeemfasil.expense.ui.components.Label
import com.najeemfasil.expense.ui.components.Screen
import com.najeemfasil.expense.ui.navigation.LocalNavigator
import com.najeemfasil.expense.ui.navigation.Routes
import com.najeemfasil.expense.ui.navigation.Tab
import com.najeemfasil.expense.ui.theme.colors
import kotlinx.coroutines.launch

private class Example(val label: String, val sender: String, val body: String)

private val EXAMPLES = listOf(
    Example(
        "ENBD card",
        "EmiratesNBD",
        "Purchase of AED 45.50 with Credit Card ending 1234 at CARREFOUR MOE, DUBAI. Avl Cr. Limit is AED 12,000.00"
    ),
    Example("HDFC UPI", "VM-HDFCBK", "Sent Rs.500.00\nFrom HDFC Bank A/C *5678\nTo SWIGGY\nRef 123456789\nNot You? Call 18002586161"),
    Example("OTP", "VM-HDFCBK", "482913 is your OTP for txn of Rs 500.00 at AMAZON. Do not share.")
)

/** Paste an SMS (FSD §12.10). [now] is injectable for tests. */
@Composable
fun ImportSmsScreen(now: () -> Long = System::currentTimeMillis) {
    val db = LocalDb.current
    val nav = LocalNavigator.current
    val c = colors
    val scope = rememberCoroutineScope()
    var sender by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<IngestResult?>(null) }
    var busy by remember { mutableStateOf(false) }

    val run = {
        busy = true
        val sms = RawSms(sender.jsTrim(), body.jsTrim(), now())
        scope.launch {
            try {
                result = db.write { importSms(it, sms) }
            } finally {
                busy = false
            }
        }
        Unit
    }
    val setUpFormat = { nav.push(Routes.parserNew(body, sender)) }

    Screen {
        Body("Paste a bank SMS to see how it's read. On the Android build, your inbox is scanned automatically.", muted = true)
        Label("Try an example")
        ChipRow {
            for (e in EXAMPLES) {
                Chip(e.label) {
                    sender = e.sender
                    body = e.body
                    result = null
                }
            }
        }
        Field(
            "Sender",
            sender,
            { sender = it },
            placeholder = "e.g. VM-HDFCBK",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
        )
        Field("Message", body, { body = it }, placeholder = "Paste the SMS text", multiline = true)
        Button("Read this SMS", run, enabled = body.jsTrim().isNotEmpty() && !busy)

        when (val r = result) {
            null -> Unit
            is IngestResult.Draft -> Card {
                val d = r.draft
                Label("Added to review")
                Amount(d.amountMinor, d.currency, direction = d.direction, fontSize = 22.sp)
                Body(d.merchant ?: "Merchant not found")
                Body("Read by: ${d.parserId}", muted = true, fontSize = 13.sp)
                if (d.duplicateOfId != null) Body("Flagged as a possible duplicate.", color = c.warning)
                Button("Go to review", { nav.goToTab(Tab.Review) }, variant = ButtonVariant.Secondary)
            }
            is IngestResult.Ignored -> Card {
                Label("Ignored")
                Body("${r.reason}. Only the kinds of bank SMS you set up are imported.")
                Button("Set up this SMS format", setUpFormat, variant = ButtonVariant.Secondary)
            }
            IngestResult.AlreadyImported -> Card {
                Label("Already imported")
                Body("This exact SMS was imported before.")
            }
            IngestResult.Unparsed -> Card {
                Label("Couldn't read this SMS")
                Body("It's from one of your banks but doesn't match a kind you set up. You can teach the app this format.")
                Button("Set up this SMS format", setUpFormat, variant = ButtonVariant.Secondary)
            }
        }
    }
}
