package com.najeemfasil.expense.feature.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najeemfasil.expense.core.formatDateTime
import com.najeemfasil.expense.data.rememberDbQuery
import com.najeemfasil.expense.data.rememberDbWriter
import com.najeemfasil.expense.feature.categories.CategoryIcon
import com.najeemfasil.expense.feature.categories.CategoryPill
import com.najeemfasil.expense.feature.transactions.Lookups
import com.najeemfasil.expense.feature.transactions.Transaction
import com.najeemfasil.expense.feature.transactions.TransactionStatus
import com.najeemfasil.expense.feature.transactions.approveAllPending
import com.najeemfasil.expense.feature.transactions.listPending
import com.najeemfasil.expense.feature.transactions.rememberLookups
import com.najeemfasil.expense.feature.transactions.setStatus
import com.najeemfasil.expense.feature.transactions.transactionTitle
import com.najeemfasil.expense.ui.components.AlertButton
import com.najeemfasil.expense.ui.components.AlertStyle
import com.najeemfasil.expense.ui.components.Amount
import com.najeemfasil.expense.ui.components.Body
import com.najeemfasil.expense.ui.components.Button
import com.najeemfasil.expense.ui.components.ButtonVariant
import com.najeemfasil.expense.ui.components.Card
import com.najeemfasil.expense.ui.components.Empty
import com.najeemfasil.expense.ui.components.Label
import com.najeemfasil.expense.ui.components.LocalAlerts
import com.najeemfasil.expense.ui.components.Screen
import com.najeemfasil.expense.ui.components.ScreenTitle
import com.najeemfasil.expense.ui.navigation.LocalNavigator
import com.najeemfasil.expense.ui.navigation.Routes
import com.najeemfasil.expense.ui.theme.Radius
import com.najeemfasil.expense.ui.theme.Spacing
import com.najeemfasil.expense.ui.theme.colors

/** Review tab (FSD §12.2). */
@Composable
fun ReviewScreen() {
    val c = colors
    val nav = LocalNavigator.current
    val alerts = LocalAlerts.current
    val write = rememberDbWriter()
    val pending by rememberDbQuery { listPending(it) }
    val lookups = rememberLookups()

    val list = pending
    if (list == null || lookups == null) return Screen(title = "Review") {}

    if (list.isEmpty()) {
        Screen(title = "Review") {
            Empty("All caught up", "New transactions from your bank SMS show up here for you to check.") {
                Button("Paste an SMS", { nav.push(Routes.IMPORT_SMS) }, variant = ButtonVariant.Secondary, fullWidth = false)
            }
        }
        return
    }

    val byId = list.associateBy { it.id }
    val duplicates = list.count { it.duplicateOfId != null }
    val approveAll = {
        val n = list.size
        val buttons = buildList {
            add(AlertButton("Cancel", AlertStyle.Cancel))
            if (duplicates > 0) {
                add(AlertButton("All except $duplicates duplicate${if (duplicates == 1) "" else "s"}") { write { approveAllPending(it, false) } })
            }
            add(AlertButton(if (duplicates > 0) "All $n" else "Approve") { write { approveAllPending(it, true) } })
        }
        alerts.show(
            "Approve $n transaction${if (n == 1) "" else "s"}?",
            if (duplicates > 0) {
                "$duplicates of them may be duplicates of another entry. Approving those counts the payment twice."
            } else {
                "They will be added to your totals."
            },
            buttons
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(start = Spacing.lg, top = Spacing.lg, end = Spacing.lg, bottom = Spacing.xl * 2),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item {
            Column(Modifier.padding(bottom = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                ScreenTitle("Review")
                Body(
                    "Accept what looks right, edit what doesn't, and reject double entries. Your category choices are remembered for next time.",
                    muted = true
                )
                Button("Approve all (${list.size})", approveAll)
            }
        }
        items(list, key = { it.id }) { tx ->
            ReviewCard(
                tx,
                lookups,
                tx.duplicateOfId?.let(byId::get),
                onAccept = { write { setStatus(it, listOf(tx.id), TransactionStatus.ACCEPTED) } },
                onReject = { write { setStatus(it, listOf(tx.id), TransactionStatus.REJECTED) } }
            )
        }
    }
}

@Composable
private fun ReviewCard(tx: Transaction, lookups: Lookups, duplicateOf: Transaction?, onAccept: () -> Unit, onReject: () -> Unit) {
    val c = colors
    val nav = LocalNavigator.current
    var showSms by rememberSaveable(tx.id) { mutableStateOf(false) }
    val title = transactionTitle(tx.merchant, tx.smsBody, tx.direction)

    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            CategoryIcon(tx.categoryId, size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (title.isSnippet) {
                    Body(title.text, muted = true, fontSize = 13.sp, fontStyle = FontStyle.Italic, maxLines = 1)
                } else {
                    Body(title.text, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                CategoryPill(tx.categoryId, lookups.categoryName(tx.categoryId) ?: "Uncategorised")
            }
            Amount(tx.amountMinor, tx.currency, direction = tx.direction, fontSize = 18.sp)
        }
        Body("${formatDateTime(tx.occurredAt)} · ${lookups.cardName(tx) ?: "No card"}", muted = true, fontSize = 13.sp)

        val hint = tx.accountHint
        if (!hint.isNullOrEmpty() && tx.accountId == null) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Body("Card ••${hint.takeLast(4)} isn't in your cards yet.", Modifier.weight(1f), muted = true, fontSize = 14.sp)
                Button(
                    "Add card",
                    { nav.push(Routes.accountNew(hint.takeLast(4), tx.currency.name, tx.smsSender.orEmpty())) },
                    variant = ButtonVariant.Secondary,
                    fullWidth = false
                )
            }
        }

        if (tx.duplicateOfId != null) {
            val same = if (duplicateOf != null) "as the entry from ${formatDateTime(duplicateOf.occurredAt)}" else "as an existing transaction"
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.sm)).background(c.warningBg).padding(Spacing.sm)) {
                Body(
                    "Possible duplicate: same amount $same within a few minutes. Reject it if this is the same payment.",
                    color = c.warning,
                    fontSize = 14.sp
                )
            }
        }

        val sms = tx.smsBody
        if (!sms.isNullOrEmpty()) {
            Column {
                Button(
                    if (showSms) "Hide SMS" else "Show SMS from ${tx.smsSender ?: "sender"}",
                    { showSms = !showSms },
                    variant = ButtonVariant.Ghost,
                    minHeight = 32.dp,
                    horizontalPadding = 0.dp,
                    fullWidth = false
                )
                if (showSms) {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.sm)).background(c.surfaceAlt).padding(Spacing.sm)) {
                        Label(sms, uppercase = false)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Button("Reject", onReject, Modifier.weight(1f), variant = ButtonVariant.Danger)
            Button("Edit", { nav.push(Routes.transaction(tx.id)) }, Modifier.weight(1f), variant = ButtonVariant.Secondary)
            Button("Accept", onAccept, Modifier.weight(1f))
        }
    }
}
