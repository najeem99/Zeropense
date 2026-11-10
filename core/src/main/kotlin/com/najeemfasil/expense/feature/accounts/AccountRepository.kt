package com.najeemfasil.expense.feature.accounts

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.jsTrim
import com.najeemfasil.expense.core.newId
import com.najeemfasil.expense.data.db.Conn

enum class AccountKind(val db: String, val label: String) {
    CREDIT_CARD("credit_card", "Credit card"),
    DEBIT_CARD("debit_card", "Debit card"),
    BANK("bank", "Bank account");

    companion object {
        fun from(value: String): AccountKind = entries.firstOrNull { it.db == value } ?: BANK
    }
}

data class Account(
    val id: String,
    val name: String,
    val kind: AccountKind,
    val currency: Currency,
    /** Last 4 digits of the account/card as they appear in bank SMS. */
    val smsHint: String?
)

/** "ENBD Platinum ••1234" */
fun accountLabel(name: String, smsHint: String?): String = if (!smsHint.isNullOrEmpty()) "$name ••$smsHint" else name

fun accountLabel(a: Account): String = accountLabel(a.name, a.smsHint)

fun listAccounts(db: Conn): List<Account> = db.query("SELECT id, name, kind, currency, sms_hint FROM accounts ORDER BY kind, currency, name") {
    Account(
        id = it.text(0),
        name = it.text(1),
        kind = AccountKind.from(it.text(2)),
        currency = Currency.from(it.text(3)) ?: Currency.AED,
        smsHint = it.textOrNull(4)
    )
}

/**
 * Adds the account and links every earlier transaction whose SMS named the same last
 * digits. Returns how many transactions were linked.
 */
fun createAccount(db: Conn, name: String, kind: AccountKind, currency: Currency, smsHint: String?): Int {
    val id = newId()
    val hint = smsHint?.jsTrim()?.takeLast(4)?.ifEmpty { null }
    var linked = 0
    db.transaction {
        db.run(
            "INSERT INTO accounts (id, name, kind, currency, sms_hint, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            id,
            name.jsTrim(),
            kind.db,
            currency.name,
            hint,
            System.currentTimeMillis()
        )
        if (hint != null) {
            linked = db.run(
                "UPDATE transactions SET account_id = ? WHERE account_id IS NULL AND substr(account_hint, -4) = ?",
                id,
                hint
            )
        }
    }
    return linked
}

fun renameAccount(db: Conn, id: String, name: String) {
    val trimmed = name.jsTrim()
    if (trimmed.isEmpty()) return
    db.run("UPDATE accounts SET name = ? WHERE id = ?", trimmed, id)
}

/** Transactions keep their data; they just lose the account link. */
fun deleteAccount(db: Conn, id: String) {
    db.run("DELETE FROM accounts WHERE id = ?", id)
}

/** Card/account digits seen in SMS that aren't linked to any account yet. */
data class DetectedCard(
    val digits: String,
    val currency: Currency,
    val count: Int,
    val lastSender: String?,
    val lastAt: Long
)

fun listDetectedCards(db: Conn): List<DetectedCard> = db.query(
    """SELECT substr(t.account_hint, -4) AS digits, t.currency AS currency, COUNT(*) AS count,
              MAX(t.occurred_at) AS lastAt,
              (SELECT sms_sender FROM transactions t2 WHERE substr(t2.account_hint, -4) = substr(t.account_hint, -4)
               ORDER BY occurred_at DESC LIMIT 1) AS lastSender
       FROM transactions t
       WHERE t.account_hint IS NOT NULL AND t.account_id IS NULL AND t.status != 'rejected'
         AND substr(t.account_hint, -4) NOT IN (SELECT sms_hint FROM accounts WHERE sms_hint IS NOT NULL)
       GROUP BY digits, t.currency
       ORDER BY count DESC"""
) {
    DetectedCard(
        digits = it.text(0),
        currency = Currency.from(it.text(1)) ?: Currency.AED,
        count = it.int(2),
        lastAt = it.long(3),
        lastSender = it.textOrNull(4)
    )
}

/** Links an existing account to the card digits found in SMS (and its earlier transactions). */
fun setAccountHint(db: Conn, id: String, hint: String) {
    db.run("UPDATE accounts SET sms_hint = ? WHERE id = ?", hint.takeLast(4), id)
    db.run(
        "UPDATE transactions SET account_id = ? WHERE account_id IS NULL AND substr(account_hint, -4) = ?",
        id,
        hint.takeLast(4)
    )
}
