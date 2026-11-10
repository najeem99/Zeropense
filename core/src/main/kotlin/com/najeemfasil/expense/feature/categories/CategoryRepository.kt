package com.najeemfasil.expense.feature.categories

import com.najeemfasil.expense.core.jsTrim
import com.najeemfasil.expense.core.newId
import com.najeemfasil.expense.data.db.Conn

enum class CategoryKind(val db: String) {
    EXPENSE("expense"),
    INCOME("income");

    companion object {
        fun from(value: String): CategoryKind = entries.firstOrNull { it.db == value } ?: EXPENSE
    }
}

data class Category(val id: String, val name: String, val kind: CategoryKind)

fun listCategories(db: Conn): List<Category> = db.query("SELECT id, name, kind FROM categories ORDER BY kind, sort, name") {
    Category(it.text(0), it.text(1), CategoryKind.from(it.text(2)))
}

fun createCategory(db: Conn, name: String, kind: CategoryKind) {
    val max = db.first("SELECT MAX(sort) AS m FROM categories") { it.longOrNull(0) } ?: 0
    db.run("INSERT INTO categories (id, name, kind, sort) VALUES (?, ?, ?, ?)", newId(), name.jsTrim(), kind.db, max + 1)
}

/** Transactions become uncategorised; learned rules for it are dropped. */
fun deleteCategory(db: Conn, id: String) {
    db.run("DELETE FROM categories WHERE id = ?", id)
}

fun listRules(db: Conn): List<MerchantRule> = db.query("SELECT merchant_key AS merchantKey, category_id AS categoryId FROM merchant_rules ORDER BY merchant_key") {
    MerchantRule(it.text(0), it.text(1))
}

/** Remember "this merchant → this category" for future SMS. Refunds are never learned. */
fun learnRule(db: Conn, merchant: String?, categoryId: String?) {
    if (merchant.isNullOrEmpty() || categoryId.isNullOrEmpty() || categoryId == REFUND_CATEGORY) return
    val key = merchantKey(merchant)
    if (key.isEmpty()) return
    db.run(
        """INSERT INTO merchant_rules (merchant_key, category_id, updated_at) VALUES (?, ?, ?)
           ON CONFLICT (merchant_key) DO UPDATE SET category_id = excluded.category_id, updated_at = excluded.updated_at""",
        key,
        categoryId,
        System.currentTimeMillis()
    )
}

fun deleteRule(db: Conn, key: String) {
    db.run("DELETE FROM merchant_rules WHERE merchant_key = ?", key)
}
