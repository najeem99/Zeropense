package com.najeemfasil.expense.feature.sms

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.formatMoney
import com.najeemfasil.expense.core.minorToInput
import com.najeemfasil.expense.feature.accounts.Account
import com.najeemfasil.expense.feature.accounts.AccountKind
import com.najeemfasil.expense.feature.accounts.accountLabel
import com.najeemfasil.expense.feature.sms.setup.CardChoice
import com.najeemfasil.expense.feature.sms.setup.DetectedDigits
import com.najeemfasil.expense.feature.sms.setup.KindChoice
import com.najeemfasil.expense.feature.sms.setup.KindWithoutDigits
import com.najeemfasil.expense.feature.sms.setup.PERIODS
import com.najeemfasil.expense.feature.sms.setup.SenderOption
import com.najeemfasil.expense.feature.sms.setup.kindId
import com.najeemfasil.expense.ui.components.Body
import com.najeemfasil.expense.ui.components.Button
import com.najeemfasil.expense.ui.components.ButtonVariant
import com.najeemfasil.expense.ui.components.Card
import com.najeemfasil.expense.ui.components.CheckRow
import com.najeemfasil.expense.ui.components.Chip
import com.najeemfasil.expense.ui.components.ChipRow
import com.najeemfasil.expense.ui.components.Field
import com.najeemfasil.expense.ui.components.Label
import com.najeemfasil.expense.ui.theme.Radius
import com.najeemfasil.expense.ui.theme.Spacing
import com.najeemfasil.expense.ui.theme.colors

@Composable
fun PeriodStep(onPick: (months: Int) -> Unit) {
    Card {
        Label("How far back should we read?")
        Body("Only bank messages from this period are imported. You can run the setup again later.", muted = true, fontSize = 14.sp)
        for (p in PERIODS) Button(p.label, { onPick(p.months) }, variant = ButtonVariant.Secondary)
    }
}

@Composable
fun SendersStep(senders: List<SenderOption>, selected: Set<String>, onToggle: (String) -> Unit, onNext: () -> Unit) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val withMoney = senders.filter { it.moneyCount > 0 }
    val rest = senders.filter { it.moneyCount == 0 }
    val row = @Composable { s: SenderOption ->
        CheckRow(
            checked = s.key in selected,
            onClick = { onToggle(s.key) },
            title = s.key,
            subtitle = "${s.count} messages · ${s.moneyCount} with an amount",
            badge = if (s.suggested) "bank?" else null
        )
    }
    Card {
        Label("Which senders are your banks?")
        Body("Personal numbers are hidden. Tick only the banks and card providers you want to track.", muted = true, fontSize = 14.sp)
        if (withMoney.isEmpty()) Body("No messages with amounts in this period.", muted = true) else withMoney.forEach { row(it) }
    }
    if (rest.isNotEmpty()) {
        Card {
            Button(
                if (showAll) "Hide other senders" else "Show ${rest.size} senders without amounts",
                { showAll = !showAll },
                variant = ButtonVariant.Ghost
            )
            if (showAll) rest.forEach { row(it) }
        }
    }
    Button("Next: message types (${selected.size} selected)", onNext, enabled = selected.isNotEmpty())
}

@Composable
private fun KindEditor(sample: String, choice: KindChoice, onChange: (KindChoice) -> Unit) {
    val c = colors
    val amounts = remember(sample) { findAmountCandidates(sample) }
    Column(Modifier.padding(start = 34.dp), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Body("Which amount is the transaction?", muted = true, fontSize = 13.sp)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            amounts.forEachIndexed { i, a ->
                val selected = choice.amountIndex == i
                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(1.5.dp, if (selected) c.primary else c.border, RoundedCornerShape(Radius.sm))
                        .clickable { onChange(choice.copy(amountIndex = i, currency = a.currency ?: choice.currency)) }
                        .padding(Spacing.sm)
                ) {
                    val cur = a.currency
                    Body(
                        (if (cur != null) formatMoney(a.minor, cur) else minorToInput(a.minor)) + if (a.looksLikeBalance) "  (looks like a balance)" else "",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Body(a.context, muted = true, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        ChipRow {
            Chip("Money out", selected = choice.direction == Direction.DEBIT) { onChange(choice.copy(direction = Direction.DEBIT)) }
            Chip("Money in", selected = choice.direction == Direction.CREDIT) { onChange(choice.copy(direction = Direction.CREDIT)) }
            for (cur in Currency.entries) Chip(cur.name, selected = choice.currency == cur) { onChange(choice.copy(currency = cur)) }
        }
    }
}

@Composable
fun KindsStep(groups: List<SenderGroup>, choices: Map<String, KindChoice>, onChange: (String, KindChoice) -> Unit, includedCount: Int, onNext: () -> Unit) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    Body(
        "Each bank sends several kinds of messages. Tick only the ones that are real payments or money received. OTPs and offers are left out.",
        muted = true
    )
    for (g in groups) {
        val main = g.kinds.filter { it.suggestion.label == KindLabel.TRANSACTION || choices[kindId(g.senderKey, it.templateKey)]?.include == true }
        val others = g.kinds.filter { it !in main }
        val renderKind = @Composable { k: MessageKind ->
            val id = kindId(g.senderKey, k.templateKey)
            val choice = choices.getValue(id)
            val sample = k.messages.last().body
            val hasAmount = findAmountCandidates(sample).isNotEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                CheckRow(
                    checked = choice.include,
                    onClick = { if (hasAmount) onChange(id, choice.copy(include = !choice.include)) },
                    title = "${k.suggestion.label.text} · ${k.messages.size} message${if (k.messages.size == 1) "" else "s"}",
                    subtitle = sample
                )
                if (choice.include) KindEditor(sample, choice) { onChange(id, it) }
            }
        }
        Card {
            Label(g.senderKey)
            if (main.isEmpty()) Body("No transaction messages recognised.", muted = true, fontSize = 14.sp)
            main.forEach { renderKind(it) }
            if (others.isNotEmpty()) {
                val open = expanded[g.senderKey] == true
                Button(
                    if (open) "Hide other messages" else "Show ${others.size} other kinds (OTP, offers…)",
                    { expanded[g.senderKey] = !open },
                    variant = ButtonVariant.Ghost
                )
                if (open) others.forEach { renderKind(it) }
            }
        }
    }
    Button("Next: cards ($includedCount transactions)", onNext, enabled = includedCount > 0)
}

private val CARD_KINDS = listOf(AccountKind.CREDIT_CARD, AccountKind.DEBIT_CARD, AccountKind.BANK)

@Composable
private fun NewCardForm(value: CardChoice.New, onChange: (CardChoice) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Body("Is this a credit card or a debit card?", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        ChipRow {
            for (k in CARD_KINDS) Chip(k.label, selected = value.kind == k) { onChange(value.copy(kind = k)) }
        }
        if (value.kind == null) Body("Choose one.", color = colors.expense, fontSize = 13.sp)
        Field("Name", value.name, { onChange(value.copy(name = it)) }, placeholder = "e.g. ENBD Platinum")
        ChipRow {
            for (cur in Currency.entries) Chip(cur.name, selected = value.currency == cur) { onChange(value.copy(currency = cur)) }
        }
    }
}

@Composable
fun CardsStep(
    digits: List<DetectedDigits>,
    noDigitKinds: List<KindWithoutDigits>,
    accounts: List<Account>,
    cardChoices: Map<String, CardChoice>,
    fixedAccounts: Map<String, String?>,
    onCard: (String, CardChoice) -> Unit,
    onFixed: (String, String?) -> Unit,
    canContinue: Boolean,
    onNext: () -> Unit
) {
    if (digits.isEmpty() && noDigitKinds.isEmpty()) Body("No card numbers in these messages.", muted = true)
    for (d in digits) {
        val choice = cardChoices[d.digits]
        // An account can only stand for one card number.
        val usable = accounts.filter { it.smsHint.isNullOrEmpty() || it.smsHint == d.digits }
        Card {
            Label("Card ending ••${d.digits}")
            Body("${d.count} messages · ${d.senders.joinToString(", ")}", muted = true, fontSize = 13.sp)
            ChipRow {
                for (a in usable) {
                    Chip(accountLabel(a), selected = (choice as? CardChoice.Existing)?.accountId == a.id) {
                        onCard(d.digits, CardChoice.Existing(a.id))
                    }
                }
                Chip("+ New card", selected = choice is CardChoice.New) {
                    onCard(d.digits, CardChoice.New(d.senders.firstOrNull().orEmpty(), null, d.currency))
                }
                Chip("Skip", selected = choice == CardChoice.Skip) { onCard(d.digits, CardChoice.Skip) }
            }
            if (choice is CardChoice.New) NewCardForm(choice) { onCard(d.digits, it) }
        }
    }
    if (noDigitKinds.isNotEmpty()) {
        Card {
            Label("Messages without a card number")
            Body("Optionally pick the account these belong to.", muted = true, fontSize = 13.sp)
            for (k in noDigitKinds) {
                Column(Modifier.padding(vertical = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Body("${k.senderKey}: ${k.kind.messages.last().body}", fontSize = 14.sp, maxLines = 2)
                    ChipRow {
                        Chip("None", selected = fixedAccounts[k.id] == null) { onFixed(k.id, null) }
                        for (a in accounts) Chip(accountLabel(a), selected = fixedAccounts[k.id] == a.id) { onFixed(k.id, a.id) }
                    }
                }
            }
        }
    }
    if (!canContinue) Body("Choose a card, “New card” or “Skip” for every number above.", muted = true)
    Button("Next: review and import", onNext, enabled = canContinue)
}
