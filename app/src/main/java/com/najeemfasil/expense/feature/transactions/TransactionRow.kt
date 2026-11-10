package com.najeemfasil.expense.feature.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najeemfasil.expense.core.formatDate
import com.najeemfasil.expense.data.rememberDbQuery
import com.najeemfasil.expense.feature.accounts.Account
import com.najeemfasil.expense.feature.accounts.accountLabel
import com.najeemfasil.expense.feature.accounts.listAccounts
import com.najeemfasil.expense.feature.categories.Category
import com.najeemfasil.expense.feature.categories.CategoryIcon
import com.najeemfasil.expense.feature.categories.CategoryPill
import com.najeemfasil.expense.feature.categories.listCategories
import com.najeemfasil.expense.ui.components.Amount
import com.najeemfasil.expense.ui.components.bottomHairline
import com.najeemfasil.expense.ui.navigation.LocalNavigator
import com.najeemfasil.expense.ui.navigation.Routes
import com.najeemfasil.expense.ui.theme.Spacing
import com.najeemfasil.expense.ui.theme.colors

/** Accounts and categories with id → name helpers. */
class Lookups(val accounts: List<Account>, val categories: List<Category>) {
    private val accountNames = accounts.associate { it.id to accountLabel(it) }
    private val categoryNames = categories.associate { it.id to it.name }

    fun accountName(id: String?): String? = id?.let { accountNames[it] }

    fun categoryName(id: String?): String? = id?.let { categoryNames[it] }

    /** The card/account a transaction used: its label, or "Card ••1234" if not added yet. */
    fun cardName(tx: Transaction): String? = accountName(tx.accountId) ?: tx.accountHint?.takeIf { it.isNotEmpty() }?.let { "Card ••${it.takeLast(4)}" }
}

/** Accounts and categories, kept fresh. Null while loading. */
@Composable
fun rememberLookups(): Lookups? {
    val data by rememberDbQuery { Lookups(listAccounts(it), listCategories(it)) }
    return data
}

@Composable
fun TransactionRow(tx: Transaction, lookups: Lookups) {
    val c = colors
    val nav = LocalNavigator.current
    val title = transactionTitle(tx.merchant, tx.smsBody, tx.direction)
    val category = lookups.categoryName(tx.categoryId) ?: "Uncategorised"
    val meta = listOfNotNull(lookups.cardName(tx), formatDate(tx.occurredAt)).joinToString(" · ")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (pressed) 0.6f else 1f)
            .clickable(interactionSource = interaction, indication = null) { nav.push(Routes.transaction(tx.id)) }
            .bottomHairline(c.border)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        CategoryIcon(tx.categoryId)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (title.isSnippet) {
                Text(
                    title.text,
                    color = c.textMuted,
                    fontSize = 13.sp,
                    fontStyle = FontStyle.Italic,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    title.text,
                    color = c.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                CategoryPill(tx.categoryId, category)
                Text(
                    meta,
                    Modifier.weight(1f),
                    color = c.textMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Amount(tx.amountMinor, tx.currency, direction = tx.direction)
    }
}
