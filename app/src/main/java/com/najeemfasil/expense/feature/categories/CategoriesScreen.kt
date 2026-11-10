package com.najeemfasil.expense.feature.categories

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.najeemfasil.expense.data.rememberDbQuery
import com.najeemfasil.expense.data.rememberDbWriter
import com.najeemfasil.expense.ui.components.Body
import com.najeemfasil.expense.ui.components.Button
import com.najeemfasil.expense.ui.components.ButtonVariant
import com.najeemfasil.expense.ui.components.Card
import com.najeemfasil.expense.ui.components.Chip
import com.najeemfasil.expense.ui.components.ChipRow
import com.najeemfasil.expense.ui.components.Field
import com.najeemfasil.expense.ui.components.Label
import com.najeemfasil.expense.ui.components.LocalAlerts
import com.najeemfasil.expense.ui.components.Row
import com.najeemfasil.expense.ui.components.Screen

/** Categories and remembered merchants (FSD §12.8). */
@Composable
fun CategoriesScreen() {
    val write = rememberDbWriter()
    val alerts = LocalAlerts.current
    val data by rememberDbQuery { listCategories(it) to listRules(it) }
    var name by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(CategoryKind.EXPENSE) }

    val (categories, rules) = data ?: return Screen {}
    val nameOf = categories.associate { it.id to it.name }

    Screen {
        Card {
            Label("Remembered merchants")
            if (rules.isEmpty()) {
                Body("When you accept or edit a transaction with a category, the merchant is remembered here.", muted = true)
            } else {
                for (r in rules) {
                    Row(
                        title = r.merchantKey,
                        subtitle = nameOf[r.categoryId] ?: r.categoryId,
                        right = {
                            Button("Forget", { write { deleteRule(it, r.merchantKey) } }, variant = ButtonVariant.Ghost, fullWidth = false)
                        }
                    )
                }
            }
        }

        for (k in CategoryKind.entries) {
            Card {
                Label(if (k == CategoryKind.EXPENSE) "Expense categories" else "Income categories")
                for (cat in categories.filter { it.kind == k }) {
                    Row(
                        title = "${categoryLook(cat.id).icon}  ${cat.name}",
                        right = {
                            Button(
                                "Delete",
                                {
                                    alerts.confirm(
                                        "Delete ${cat.name}?",
                                        "Its transactions become uncategorised.",
                                        action = "Delete",
                                        destructive = true
                                    ) { write { deleteCategory(it, cat.id) } }
                                },
                                variant = ButtonVariant.Ghost,
                                fullWidth = false
                            )
                        }
                    )
                }
            }
        }

        Card {
            Label("Add category")
            Field("Name", name, { name = it }, placeholder = "e.g. Kids")
            ChipRow {
                Chip("Expense", selected = kind == CategoryKind.EXPENSE) { kind = CategoryKind.EXPENSE }
                Chip("Income", selected = kind == CategoryKind.INCOME) { kind = CategoryKind.INCOME }
            }
            Button(
                "Add category",
                {
                    val newName = name
                    val newKind = kind
                    write { createCategory(it, newName, newKind) }
                    name = ""
                },
                enabled = name.isNotBlank()
            )
        }
    }
}
