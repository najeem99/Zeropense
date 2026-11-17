package com.najeemfasil.expense.feature.debts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.formatDate
import com.najeemfasil.expense.core.localTime
import com.najeemfasil.expense.core.minorToInput
import com.najeemfasil.expense.core.parseAmountToMinor
import com.najeemfasil.expense.data.LocalDb
import com.najeemfasil.expense.data.rememberDbQuery
import com.najeemfasil.expense.ui.components.Body
import com.najeemfasil.expense.ui.components.Button
import com.najeemfasil.expense.ui.components.ButtonVariant
import com.najeemfasil.expense.ui.components.Chip
import com.najeemfasil.expense.ui.components.ChipRow
import com.najeemfasil.expense.ui.components.Empty
import com.najeemfasil.expense.ui.components.Field
import com.najeemfasil.expense.ui.components.Label
import com.najeemfasil.expense.ui.components.LocalAlerts
import com.najeemfasil.expense.ui.components.Screen
import com.najeemfasil.expense.ui.navigation.LocalNavigator
import com.najeemfasil.expense.ui.theme.Spacing
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.launch

private class EntryData(val person: DebtPerson?, val entry: DebtEntry?, val all: List<DebtEntry>)

/** The local calendar day of [ts] as the UTC midnight the date picker works with. */
internal fun toPickerMillis(ts: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = ts }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** The date picker's UTC midnight back to that calendar day in local time, keeping [timeOf]'s time of day. */
internal fun fromPickerMillis(utc: Long, timeOf: Long): Long {
    val day = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
    val time = Calendar.getInstance().apply { timeInMillis = timeOf }
    return localTime(
        day.get(Calendar.YEAR),
        day.get(Calendar.MONTH),
        day.get(Calendar.DAY_OF_MONTH),
        time.get(Calendar.HOUR_OF_DAY),
        time.get(Calendar.MINUTE)
    )
}

/**
 * Add or edit one debt entry. [id] empty = new entry for [personId] with [kind] preselected.
 */
@Composable
fun DebtEntryScreen(personId: String, id: String, kind: String) {
    val data by rememberDbQuery("$personId/$id") {
        val entry = id.takeIf { s -> s.isNotEmpty() }?.let { e -> getEntry(it, e) }
        val pid = entry?.personId ?: personId
        EntryData(getPerson(it, pid), entry, listEntries(it, pid))
    }
    val loaded = data ?: return Screen {}
    val person = loaded.person ?: return Screen { Empty("This person was deleted") }
    if (id.isNotEmpty() && loaded.entry == null) return Screen { Empty("This entry was deleted") }
    EntryForm(person, loaded.entry, loaded.all, DebtKind.from(kind))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryForm(person: DebtPerson, entry: DebtEntry?, all: List<DebtEntry>, initialKind: DebtKind) {
    val db = LocalDb.current
    val nav = LocalNavigator.current
    val alerts = LocalAlerts.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(entry?.kind ?: initialKind) }
    var amount by remember { mutableStateOf(entry?.let { minorToInput(it.amountMinor) }.orEmpty()) }
    var currency by remember { mutableStateOf(entry?.currency ?: all.firstOrNull()?.currency ?: Currency.AED) }
    var occurredAt by remember { mutableStateOf(entry?.occurredAt ?: System.currentTimeMillis()) }
    var note by remember { mutableStateOf(entry?.note.orEmpty()) }
    var picking by remember { mutableStateOf(false) }

    val save: () -> Unit = save@{
        val minor = parseAmountToMinor(amount)
        if (minor == null) {
            alerts.show("Enter an amount", "Use a number like 45.50.")
            return@save
        }
        scope.launch {
            db.write { saveEntry(it, person.id, entry?.id, DebtInput(kind, minor, currency, note, occurredAt)) }
            nav.back()
        }
    }

    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = toPickerMillis(occurredAt))
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { occurredAt = fromPickerMillis(it, occurredAt) }
                    picking = false
                }) { androidx.compose.material3.Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { androidx.compose.material3.Text("Cancel") } }
        ) { DatePicker(state = state) }
    }

    Screen {
        Body(person.name, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        ChipRow {
            for (k in DebtKind.entries) Chip(k.label, selected = kind == k) { kind = k }
        }
        Body(
            if (kind == DebtKind.GAVE) {
                "Money you gave ${person.name}: a loan to them, or paying back what you owe."
            } else {
                "Money you got from ${person.name}: a loan from them, or them paying you back."
            },
            muted = true,
            fontSize = 13.sp
        )
        Field(
            "Amount",
            amount,
            { amount = it },
            placeholder = "0.00",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            autoFocus = entry == null
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Label("Currency")
            ChipRow {
                for (cur in Currency.entries) Chip(cur.name, selected = currency == cur) { currency = cur }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Label("Date")
            Button(formatDate(occurredAt), { picking = true }, variant = ButtonVariant.Secondary)
        }
        Field("Note", note, { note = it }, placeholder = "Optional, e.g. Dinner at Gazebo", multiline = true)

        Button(if (entry == null) "Save" else "Save changes", save)
        if (entry != null) {
            Button(
                "Share on WhatsApp",
                { if (!shareOnWhatsApp(context, entryShareText(entry, all), person.phone)) noWhatsApp(alerts) },
                variant = ButtonVariant.Secondary
            )
            Button(
                "Delete entry",
                {
                    alerts.confirm("Delete this entry?", null, action = "Delete", destructive = true) {
                        scope.launch {
                            db.write { deleteEntry(it, entry.id) }
                            nav.back()
                        }
                    }
                },
                variant = ButtonVariant.Danger
            )
        }
    }
}
