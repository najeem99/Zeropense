package com.najeemfasil.expense.feature.sms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.formatDate
import com.najeemfasil.expense.core.formatMoney
import com.najeemfasil.expense.data.LocalDb
import com.najeemfasil.expense.ui.components.Body
import com.najeemfasil.expense.ui.components.Button
import com.najeemfasil.expense.ui.components.Card
import com.najeemfasil.expense.ui.components.Chip
import com.najeemfasil.expense.ui.components.ChipRow
import com.najeemfasil.expense.ui.components.Field
import com.najeemfasil.expense.ui.components.Label
import com.najeemfasil.expense.ui.components.LocalAlerts
import com.najeemfasil.expense.ui.components.Screen
import com.najeemfasil.expense.ui.navigation.LocalNavigator
import com.najeemfasil.expense.ui.theme.Radius
import com.najeemfasil.expense.ui.theme.Spacing
import com.najeemfasil.expense.ui.theme.colors
import java.util.Locale
import kotlinx.coroutines.launch

private enum class WordField(val label: String, val color: Color, val field: FieldName) {
    MERCHANT("Merchant", Color(0xFF13804B), FieldName.MERCHANT),
    DATE("Date", Color(0xFFA35F00), FieldName.DATE)
}

/** Builds a custom SMS format from one sample message (FSD §12.11). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParserNewScreen(initialSample: String = "", initialSender: String = "") {
    val c = colors
    val db = LocalDb.current
    val nav = LocalNavigator.current
    val alerts = LocalAlerts.current
    val scope = rememberCoroutineScope()

    var sample by rememberSaveable { mutableStateOf(initialSample) }
    var sender by rememberSaveable { mutableStateOf(initialSender) }
    var name by rememberSaveable { mutableStateOf("") }
    var amountPick by remember { mutableStateOf<Int?>(null) }
    var digitsPick by remember { mutableStateOf<Int?>(null) }
    var currency by remember { mutableStateOf(Currency.AED) }
    var direction by remember { mutableStateOf(findDirection(initialSample) ?: Direction.DEBIT) }
    var wordField by remember { mutableStateOf(WordField.MERCHANT) }
    var ranges by remember { mutableStateOf(mapOf<WordField, TokenRange>()) }
    var saving by remember { mutableStateOf(false) }

    val amounts = remember(sample) { findAmountCandidates(sample) }
    val digits = remember(sample) { findDigitsCandidates(sample) }
    val tokens = remember(sample) { tokenize(sample) }

    val changeSample = { text: String ->
        sample = text
        amountPick = null
        digitsPick = null
        ranges = emptyMap()
        direction = findDirection(text) ?: Direction.DEBIT
    }

    val marks = buildList {
        amountPick?.let { amounts.getOrNull(it) }?.let { add(FieldMark(FieldName.AMOUNT, it.start, it.end)) }
        digitsPick?.let { digits.getOrNull(it) }?.let { add(FieldMark(FieldName.ACCOUNT, it.start, it.end)) }
        for (f in WordField.entries) {
            val r = ranges[f] ?: continue
            if (r.to < tokens.size) add(FieldMark(f.field, tokens[r.from].start, tokens[r.to].end))
        }
    }
    val built = if (amountPick != null) buildPattern(sample, marks) else null
    val preview = (built as? BuildResult.Ok)?.let {
        applyCustomParser(CustomParser("preview", name, null, it.pattern, it.fields, currency, direction), RawSms(sender, sample, 0))
    }

    fun wordFieldAt(i: Int) = WordField.entries.firstOrNull { f -> ranges[f]?.let { i in it.from..it.to } == true }

    val save: () -> Unit = save@{
        val ok = built as? BuildResult.Ok ?: return@save
        if (preview == null || saving) return@save
        saving = true
        val parser = CustomParser(
            "",
            name.trim().ifEmpty { sender.trim() }.ifEmpty { "My SMS format" },
            sender.trim().ifEmpty { null },
            ok.pattern,
            ok.fields,
            currency,
            direction
        )
        scope.launch {
            val recheck = db.write {
                createCustomParser(it, parser, sample)
                recheckUnread(it)
            }
            if (recheck.added > 0) {
                alerts.show("Saved", "${recheck.added} more message${if (recheck.added == 1) "" else "s"} could be read and went to Review.")
            }
            nav.back()
        }
    }

    Screen {
        Field("Bank SMS", sample, changeSample, placeholder = "Paste one message from your bank", multiline = true)

        if (sample.isNotBlank()) {
            Card {
                Label("1. Which amount is the transaction?")
                if (amounts.isEmpty()) Body("No amounts found in this message.", muted = true)
                amounts.forEachIndexed { i, a ->
                    val selected = amountPick == i
                    val shape = RoundedCornerShape(Radius.md)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .border(1.5.dp, if (selected) c.primary else c.border, shape)
                            .background(if (selected) c.surfaceAlt else Color.Transparent, shape)
                            .clickable {
                                amountPick = i
                                a.currency?.let { currency = it }
                            }
                            .padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            val cur = a.currency
                            Body(
                                if (cur != null) formatMoney(a.minor, cur) else String.format(Locale.ROOT, "%.2f", a.minor / 100.0),
                                fontWeight = FontWeight.SemiBold
                            )
                            if (a.looksLikeBalance) Body("looks like a balance", muted = true, fontSize = 13.sp)
                            if (selected) Body("✓ This one", color = c.primary, fontSize = 13.sp)
                        }
                        Body(a.context, muted = true, fontSize = 13.sp)
                    }
                }
            }
        }

        if (amountPick != null) {
            Card {
                Label("2. Money in or out?")
                ChipRow {
                    Chip("Money out (spent)", selected = direction == Direction.DEBIT) { direction = Direction.DEBIT }
                    Chip("Money in (received)", selected = direction == Direction.CREDIT) { direction = Direction.CREDIT }
                }
                ChipRow {
                    for (cur in Currency.entries) Chip(cur.name, selected = currency == cur) { currency = cur }
                }
            }

            Card {
                Label("3. Which card or account?")
                ChipRow {
                    Chip("Not in this SMS", selected = digitsPick == null) { digitsPick = null }
                    digits.forEachIndexed { i, d -> Chip("••${d.digits.takeLast(4)}", selected = digitsPick == i) { digitsPick = i } }
                }
            }

            Card {
                Label("4. Merchant and date (optional)")
                Body("Pick one, then tap its first and last word.", muted = true, fontSize = 14.sp)
                ChipRow {
                    for (f in WordField.entries) {
                        Chip(f.label + if (ranges[f] != null) " ✓" else "", selected = wordField == f) { wordField = f }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    tokens.forEachIndexed { i, t ->
                        val field = wordFieldAt(i)
                        Box(
                            Modifier
                                .background(field?.color ?: c.surfaceAlt, RoundedCornerShape(Radius.sm))
                                .clickable {
                                    val next = tapToken(ranges[wordField], i)
                                    ranges = if (next == null) ranges - wordField else ranges + (wordField to next)
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(t.text, color = if (field != null) Color.White else c.text, fontSize = 15.sp)
                        }
                    }
                }
            }

            Card {
                Label("5. Save")
                Field(
                    "Only for senders containing (optional)",
                    sender,
                    { sender = it },
                    placeholder = "e.g. ENBD",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
                )
                Field("Name", name, { name = it }, placeholder = "e.g. ENBD credit card")
                if (built is BuildResult.Error) Body(built.error, color = c.expense)
                if (built is BuildResult.Ok && preview == null) {
                    Body("This setup doesn't read the message back. Try marking fewer words.", color = c.expense)
                }
                if (preview != null) {
                    Column(
                        Modifier.fillMaxWidth().background(c.surfaceAlt, RoundedCornerShape(Radius.md)).padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Body(
                            "${if (direction == Direction.DEBIT) "Spent" else "Received"} ${formatMoney(preview.amountMinor, preview.currency)}",
                            fontWeight = FontWeight.SemiBold
                        )
                        Body("Card: ${preview.accountHint?.let { "••$it" } ?: "—"}", muted = true)
                        Body("Merchant: ${preview.merchant ?: "—"}", muted = true)
                        Body("Date: ${preview.occurredAt?.let { formatDate(it) } ?: "when the SMS arrived"}", muted = true)
                    }
                }
                Button("Save and use for future SMS", save, enabled = preview != null && !saving)
            }
        }
    }
}
