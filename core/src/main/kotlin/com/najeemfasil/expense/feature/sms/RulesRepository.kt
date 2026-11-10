package com.najeemfasil.expense.feature.sms

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.newId
import com.najeemfasil.expense.data.db.Conn

fun listSmsRules(db: Conn): List<SmsRule> = db.query(
    "SELECT id, sender_key, action, amount_index, direction, currency, fixed_account_id, sample FROM sms_rules ORDER BY sender_key, created_at"
) {
    val sample = it.text(7)
    SmsRule(
        id = it.text(0),
        senderKey = it.text(1),
        // Recomputed from the sample so rules keep matching if the key format changes.
        templateKey = templateKey(sample),
        action = RuleAction.from(it.text(2)),
        amountIndex = it.int(3),
        direction = Direction.from(it.text(4)) ?: Direction.DEBIT,
        currency = Currency.from(it.text(5)) ?: Currency.AED,
        fixedAccountId = it.textOrNull(6),
        sample = sample
    )
}

/**
 * Saves the setup's choices for the given senders. Earlier rules for those senders are
 * replaced; rules for other senders are kept. Rule ids passed in are ignored.
 */
fun replaceSmsRules(db: Conn, senderKeys: List<String>, rules: List<SmsRule>) {
    db.transaction {
        for (key in senderKeys) db.run("DELETE FROM sms_rules WHERE sender_key = ?", key)
        val now = System.currentTimeMillis()
        for (r in rules) {
            db.run(
                """INSERT OR REPLACE INTO sms_rules
                     (id, sender_key, template_key, action, amount_index, direction, currency, fixed_account_id, sample, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                newId(), r.senderKey, r.templateKey, r.action.db, r.amountIndex, r.direction.db, r.currency.name,
                r.fixedAccountId, r.sample, now
            )
        }
    }
}

fun deleteSmsRule(db: Conn, id: String) {
    db.run("DELETE FROM sms_rules WHERE id = ?", id)
}
