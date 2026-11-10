package com.najeemfasil.expense.feature.sms

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import com.najeemfasil.expense.core.jsTrim
import com.najeemfasil.expense.data.LocalDb
import com.najeemfasil.expense.data.LocalSmsInbox
import com.najeemfasil.expense.data.db.Db
import com.najeemfasil.expense.data.rememberDbQuery
import com.najeemfasil.expense.data.rememberSmsPermissionRequest
import com.najeemfasil.expense.feature.accounts.createAccount
import com.najeemfasil.expense.feature.accounts.listAccounts
import com.najeemfasil.expense.feature.accounts.setAccountHint
import com.najeemfasil.expense.feature.sms.setup.CardChoice
import com.najeemfasil.expense.feature.sms.setup.KindChoice
import com.najeemfasil.expense.feature.sms.setup.SenderOption
import com.najeemfasil.expense.feature.sms.setup.buildRules
import com.najeemfasil.expense.feature.sms.setup.cardChoiceComplete
import com.najeemfasil.expense.feature.sms.setup.collectDigits
import com.najeemfasil.expense.feature.sms.setup.countIncluded
import com.najeemfasil.expense.feature.sms.setup.initialChoices
import com.najeemfasil.expense.feature.sms.setup.kindsWithoutDigits
import com.najeemfasil.expense.feature.sms.setup.mergeSenders
import com.najeemfasil.expense.feature.sms.setup.periodStart
import com.najeemfasil.expense.ui.components.Body
import com.najeemfasil.expense.ui.components.Button
import com.najeemfasil.expense.ui.components.ButtonVariant
import com.najeemfasil.expense.ui.components.Card
import com.najeemfasil.expense.ui.components.Label
import com.najeemfasil.expense.ui.components.Screen
import com.najeemfasil.expense.ui.components.SpinnerCard
import com.najeemfasil.expense.ui.navigation.LocalNavigator
import com.najeemfasil.expense.ui.navigation.Tab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Step { PERIOD, SENDERS, KINDS, CARDS, CONFIRM, IMPORTING, DONE }

private class PermissionDenied : Exception("SMS permission is needed to read your bank messages.")

/**
 * Runs the setup import in the exact order of the Expo app (FSD §12.9): wait for a running
 * scan, create or link cards, clear earlier SMS transactions, replace the rules, import with
 * auto-accept, then set the scan markers. Chunks are written one by one so the app stays usable.
 */
suspend fun runSetupImport(
    db: Db,
    groups: List<SenderGroup>,
    choices: Map<String, KindChoice>,
    cardChoices: Map<String, CardChoice>,
    fixedAccounts: Map<String, String?>,
    messages: List<RawSms>,
    readAt: Long,
    waitForScan: suspend () -> Unit = {},
    onProgress: (ImportSummary) -> Unit = {}
): ImportSummary {
    waitForScan()
    val digits = collectDigits(groups, choices)
    db.write { conn ->
        // Cards first, so imported messages link to them by their last 4 digits.
        for (d in digits) {
            when (val c = cardChoices[d.digits]) {
                is CardChoice.New -> c.kind?.let { createAccount(conn, c.name.jsTrim(), it, c.currency, d.digits) }
                is CardChoice.Existing -> setAccountHint(conn, c.accountId, d.digits)
                else -> Unit
            }
        }
        clearSmsTransactions(conn)
        replaceSmsRules(conn, groups.map { it.senderKey }, buildRules(groups, choices, fixedAccounts))
    }
    var total = ImportSummary()
    if (messages.isNotEmpty()) {
        val ctx = db.read { loadIngestContext(it, messages.minOf { m -> m.receivedAt }, messages.maxOf { m -> m.receivedAt }) }
        for (chunk in messages.chunked(IMPORT_CHUNK)) {
            total += db.write { importChunk(it, chunk, ctx, autoAccept = true).first }
            onProgress(total)
        }
    }
    db.write { markScannedUpTo(it, readAt) }
    return total
}

/** Set up bank SMS (FSD §12.9). */
@Composable
fun SmsSetupScreen(now: () -> Long = System::currentTimeMillis, waitForScan: suspend () -> Unit = {}) {
    val db = LocalDb.current
    val inbox = LocalSmsInbox.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val requestPermission = rememberSmsPermissionRequest()
    val accounts by rememberDbQuery { listAccounts(it) }
    val oldCount by rememberDbQuery { countSmsTransactions(it) }

    var step by remember { mutableStateOf(Step.PERIOD) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var since by remember { mutableStateOf(0L) }
    var senders by remember { mutableStateOf(emptyList<SenderOption>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var readAt by remember { mutableStateOf(0L) }
    var messages by remember { mutableStateOf(emptyList<RawSms>()) }
    var groups by remember { mutableStateOf(emptyList<SenderGroup>()) }
    var choices by remember { mutableStateOf(emptyMap<String, KindChoice>()) }
    var cardChoices by remember { mutableStateOf(emptyMap<String, CardChoice>()) }
    var fixedAccounts by remember { mutableStateOf(emptyMap<String, String?>()) }
    var result by remember { mutableStateOf<ImportSummary?>(null) }

    val digits = remember(groups, choices) { collectDigits(groups, choices) }
    val noDigitKinds = remember(groups, choices) { kindsWithoutDigits(groups, choices) }
    val includedCount = remember(groups, choices) { countIncluded(groups, choices) }
    val accountList = accounts.orEmpty()

    fun guard(label: String, job: suspend () -> Unit) {
        busy = label
        error = null
        scope.launch {
            try {
                job()
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                busy = null
            }
        }
    }

    val pickPeriod = { months: Int ->
        requestPermission { granted ->
            guard("Counting your messages…") {
                if (!granted) throw PermissionDenied()
                val start = periodStart(months, now())
                val list = withContext(Dispatchers.IO) { mergeSenders(inbox.listSenders(start)) }
                since = start
                senders = list
                selected = list.filter { it.suggested }.map { it.key }.toSet()
                step = Step.SENDERS
            }
        }
    }

    val loadKinds = {
        guard("Reading messages from the selected banks…") {
            val addresses = senders.filter { it.key in selected }.flatMap { it.addresses }
            val started = now()
            val msgs = withContext(Dispatchers.IO) { readMessages(inbox, addresses, since) { n -> busy = "Reading messages… $n" } }
            val g = withContext(Dispatchers.Default) { groupMessages(msgs) }
            readAt = started
            messages = msgs
            groups = g
            choices = initialChoices(g)
            step = Step.KINDS
        }
    }

    val goToCards = {
        // Pre-pick accounts that already carry a detected card number.
        val next = cardChoices.toMutableMap()
        for (d in digits) {
            if (d.digits in next) continue
            accountList.firstOrNull { it.smsHint == d.digits }?.let { next[d.digits] = CardChoice.Existing(it.id) }
        }
        cardChoices = next
        step = Step.CARDS
    }

    val runImport = {
        step = Step.IMPORTING
        guard("Importing…") {
            val total = messages.size
            result = runSetupImport(db, groups, choices, cardChoices, fixedAccounts, messages, readAt, waitForScan) { s ->
                busy = "Importing… ${s.read} of $total messages checked"
            }
            step = Step.DONE
        }
    }

    Screen {
        error?.let {
            Card {
                Body(it)
                if (step == Step.IMPORTING) Button("Back", { step = Step.CONFIRM }, variant = ButtonVariant.Secondary)
            }
        }

        val label = busy
        if (label != null) {
            SpinnerCard(label)
        } else {
            when (step) {
                Step.PERIOD -> PeriodStep(pickPeriod)
                Step.SENDERS -> SendersStep(senders, selected, { key -> selected = if (key in selected) selected - key else selected + key }, loadKinds)
                Step.KINDS -> KindsStep(groups, choices, { id, c -> choices = choices + (id to c) }, includedCount, goToCards)
                Step.CARDS -> CardsStep(
                    digits,
                    noDigitKinds,
                    accountList,
                    cardChoices,
                    fixedAccounts,
                    { d, c -> cardChoices = cardChoices + (d to c) },
                    { id, a -> fixedAccounts = fixedAccounts + (id to a) },
                    digits.all { cardChoiceComplete(cardChoices[it.digits]) }
                ) { step = Step.CONFIRM }
                Step.CONFIRM -> Card {
                    val banks = groups.size
                    Label("Ready to import")
                    Body(
                        "$includedCount transaction message${if (includedCount == 1) "" else "s"} from $banks bank${if (banks == 1) "" else "s"} " +
                            "will be added to your totals. Possible duplicates go to Review."
                    )
                    val old = oldCount ?: 0
                    if (old > 0) {
                        Body(
                            "The $old transaction${if (old == 1) "" else "s"} imported from SMS earlier will be deleted first. " +
                                "Transactions you added by hand stay."
                        )
                    }
                    Body("From now on, only these kinds of messages are read. New ones wait in Review.", muted = true, fontSize = 14.sp)
                    Button("Import", runImport)
                    Button("Back", { step = Step.CARDS }, variant = ButtonVariant.Ghost)
                }
                Step.IMPORTING -> Unit
                Step.DONE -> result?.let { r ->
                    Card {
                        Label("Done")
                        val dup = if (r.duplicates > 0) " ${r.duplicates} possible duplicate${if (r.duplicates == 1) "" else "s"} in Review." else ""
                        Body("${r.accepted} transaction${if (r.accepted == 1) "" else "s"} added to your totals.$dup")
                        Button("Go to Overview", { nav.goToTab(Tab.Overview) })
                    }
                }
            }
        }
    }
}
