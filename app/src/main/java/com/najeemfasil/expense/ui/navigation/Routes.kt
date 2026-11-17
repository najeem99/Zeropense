package com.najeemfasil.expense.ui.navigation

import android.net.Uri

/** Bottom tabs, in order. */
enum class Tab(val label: String) {
    Overview("Overview"),
    Insights("Insights"),
    Review("Review"),
    Transactions("Transactions"),
    More("More")
}

/** Stack destinations and their header titles (FSD §12). */
object Routes {
    const val TABS = "tabs"
    const val TRANSACTION = "transaction/{id}"
    const val IMPORT_SMS = "import-sms"
    const val SMS_SETUP = "sms-setup"
    const val ACCOUNTS = "accounts"
    const val ACCOUNT_NEW = "accounts/new?digits={digits}&currency={currency}&sender={sender}"
    const val CATEGORIES = "categories"
    const val PARSERS = "parsers"
    const val PARSER_NEW = "parsers/new?sample={sample}&sender={sender}"
    const val DEBTS = "debts"
    const val DEBT_PERSON = "debts/person?id={id}"
    const val DEBT_ENTRY = "debts/entry?person={person}&id={id}&kind={kind}"
    const val BACKUP = "backup"

    /** `id` is a transaction id, or "new" to create one. */
    fun transaction(id: String) = "transaction/${Uri.encode(id)}"

    fun accountNew(digits: String = "", currency: String = "", sender: String = "") = "accounts/new?digits=${Uri.encode(digits)}&currency=${Uri.encode(currency)}&sender=${Uri.encode(sender)}"

    fun parserNew(sample: String = "", sender: String = "") = "parsers/new?sample=${Uri.encode(sample)}&sender=${Uri.encode(sender)}"

    fun debtPerson(id: String) = "debts/person?id=${Uri.encode(id)}"

    /** A new entry for [person] (with [kind] "gave" or "got"), or the entry [id] to edit. */
    fun debtEntry(person: String, id: String = "", kind: String = "") = "debts/entry?person=${Uri.encode(person)}&id=${Uri.encode(id)}&kind=${Uri.encode(kind)}"
}
