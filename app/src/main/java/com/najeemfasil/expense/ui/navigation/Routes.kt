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
    const val CARD_PAYMENTS = "card-payments"
    const val CARD_PAYMENT_NEW = "card-payments/new"
    const val BILLS = "bills"
    const val BILL = "bill/{id}"
    const val BILL_EDIT = "bills/edit?id={id}&name={name}&amount={amount}&currency={currency}&day={day}&category={category}&merchant={merchant}"

    fun bill(id: String) = "bill/${Uri.encode(id)}"

    /** Edit bill [id], or start a new one (id empty) with the given values. */
    fun billEdit(
        id: String = "",
        name: String = "",
        amount: String = "",
        currency: String = "",
        day: String = "",
        category: String = "",
        merchant: String = ""
    ) = "bills/edit?id=${Uri.encode(id)}&name=${Uri.encode(name)}&amount=${Uri.encode(amount)}&currency=${Uri.encode(currency)}" +
        "&day=${Uri.encode(day)}&category=${Uri.encode(category)}&merchant=${Uri.encode(merchant)}"

    /** `id` is a transaction id, or "new" to create one. */
    fun transaction(id: String) = "transaction/${Uri.encode(id)}"

    fun accountNew(digits: String = "", currency: String = "", sender: String = "") = "accounts/new?digits=${Uri.encode(digits)}&currency=${Uri.encode(currency)}&sender=${Uri.encode(sender)}"

    fun parserNew(sample: String = "", sender: String = "") = "parsers/new?sample=${Uri.encode(sample)}&sender=${Uri.encode(sender)}"

    fun debtPerson(id: String) = "debts/person?id=${Uri.encode(id)}"

    /** A new entry for [person] (with [kind] "gave" or "got"), or the entry [id] to edit. */
    fun debtEntry(person: String, id: String = "", kind: String = "") = "debts/entry?person=${Uri.encode(person)}&id=${Uri.encode(id)}&kind=${Uri.encode(kind)}"
}
