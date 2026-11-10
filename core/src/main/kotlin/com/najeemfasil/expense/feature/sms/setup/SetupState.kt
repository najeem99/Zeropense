package com.najeemfasil.expense.feature.sms.setup

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.jsTrim
import com.najeemfasil.expense.feature.accounts.AccountKind
import com.najeemfasil.expense.feature.sms.KindLabel
import com.najeemfasil.expense.feature.sms.MessageKind
import com.najeemfasil.expense.feature.sms.RuleAction
import com.najeemfasil.expense.feature.sms.SenderGroup
import com.najeemfasil.expense.feature.sms.SmsRule
import com.najeemfasil.expense.feature.sms.isPersonalSender
import com.najeemfasil.expense.feature.sms.profileForSender
import com.najeemfasil.expense.feature.sms.senderKey
import java.util.Calendar

/** A sender in the phone's inbox, with counts made on the native side. */
data class InboxSender(val sender: String, val count: Int, val moneyCount: Int, val lastDate: Long)

data class Period(val label: String, val months: Int)

val PERIODS = listOf(Period("1 month", 1), Period("3 months", 3), Period("6 months", 6), Period("1 year", 12))

fun periodStart(months: Int, now: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
    timeInMillis = now
    add(Calendar.MONTH, -months)
}.timeInMillis

/** One bank sender in the setup, merged across its operator prefixes. */
data class SenderOption(
    val key: String,
    val addresses: List<String>,
    val count: Int,
    val moneyCount: Int,
    /** Pre-ticked: a known bank, or mostly messages with amounts. */
    val suggested: Boolean
)

fun mergeSenders(raw: List<InboxSender>): List<SenderOption> {
    class Acc(val key: String) {
        val addresses = ArrayList<String>()
        var count = 0
        var moneyCount = 0
    }
    val byKey = LinkedHashMap<String, Acc>()
    for (s in raw) {
        if (isPersonalSender(s.sender)) continue
        val o = byKey.getOrPut(senderKey(s.sender)) { Acc(senderKey(s.sender)) }
        o.addresses.add(s.sender)
        o.count += s.count
        o.moneyCount += s.moneyCount
    }
    return byKey.values
        .map {
            SenderOption(
                it.key,
                it.addresses,
                it.count,
                it.moneyCount,
                profileForSender(it.key) != null || (it.moneyCount >= 2 && it.moneyCount.toDouble() / it.count >= 0.3)
            )
        }
        .sortedWith(compareByDescending<SenderOption> { it.moneyCount }.thenByDescending { it.count })
}

fun kindId(sender: String, template: String) = "$sender|$template"

data class KindChoice(val include: Boolean, val amountIndex: Int, val direction: Direction, val currency: Currency)

fun initialChoices(groups: List<SenderGroup>): Map<String, KindChoice> = buildMap {
    for (g in groups) {
        for (k in g.kinds) {
            val s = k.suggestion
            put(kindId(g.senderKey, k.templateKey), KindChoice(s.label == KindLabel.TRANSACTION, s.amountIndex, s.direction, s.currency))
        }
    }
}

sealed interface CardChoice {
    data class Existing(val accountId: String) : CardChoice

    data class New(val name: String, val kind: AccountKind?, val currency: Currency) : CardChoice

    data object Skip : CardChoice
}

data class DetectedDigits(val digits: String, val count: Int, val senders: List<String>, val currency: Currency)

/** Card digits named in the kinds the user is importing. */
fun collectDigits(groups: List<SenderGroup>, choices: Map<String, KindChoice>): List<DetectedDigits> {
    class Acc(val digits: String, val currency: Currency) {
        var count = 0
        val senders = ArrayList<String>()
    }
    val map = LinkedHashMap<String, Acc>()
    for (g in groups) {
        for (k in g.kinds) {
            val c = choices[kindId(g.senderKey, k.templateKey)]
            if (c?.include != true) continue
            for (d in k.digits) {
                val e = map.getOrPut(d) { Acc(d, c.currency) }
                e.count += k.messages.size
                if (g.senderKey !in e.senders) e.senders.add(g.senderKey)
            }
        }
    }
    return map.values.map { DetectedDigits(it.digits, it.count, it.senders, it.currency) }.sortedByDescending { it.count }
}

data class KindWithoutDigits(val id: String, val senderKey: String, val kind: MessageKind)

/** Included kinds that never name a card; the user can pick a fixed account for them. */
fun kindsWithoutDigits(groups: List<SenderGroup>, choices: Map<String, KindChoice>): List<KindWithoutDigits> = groups.flatMap { g ->
    g.kinds
        .filter { choices[kindId(g.senderKey, it.templateKey)]?.include == true && it.digits.isEmpty() }
        .map { KindWithoutDigits(kindId(g.senderKey, it.templateKey), g.senderKey, it) }
}

fun cardChoiceComplete(c: CardChoice?): Boolean = when (c) {
    null -> false
    is CardChoice.New -> c.kind != null && c.name.jsTrim().isNotEmpty()
    else -> true
}

/** Every kind of the chosen senders becomes a rule: included ones import, the rest are ignored. Ids are empty. */
fun buildRules(groups: List<SenderGroup>, choices: Map<String, KindChoice>, fixedAccounts: Map<String, String?>): List<SmsRule> = groups.flatMap { g ->
    g.kinds.map { k ->
        val id = kindId(g.senderKey, k.templateKey)
        val c = choices[id]
        SmsRule(
            id = "",
            senderKey = g.senderKey,
            templateKey = k.templateKey,
            action = if (c?.include == true) RuleAction.IMPORT else RuleAction.IGNORE,
            amountIndex = c?.amountIndex ?: 0,
            direction = c?.direction ?: Direction.DEBIT,
            currency = c?.currency ?: Currency.AED,
            fixedAccountId = fixedAccounts[id],
            sample = k.messages.last().body
        )
    }
}

fun countIncluded(groups: List<SenderGroup>, choices: Map<String, KindChoice>): Int = groups.sumOf { g ->
    g.kinds.filter { choices[kindId(g.senderKey, it.templateKey)]?.include == true }.sumOf { it.messages.size }
}
