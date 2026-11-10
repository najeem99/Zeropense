package com.najeemfasil.expense.feature.categories

import com.najeemfasil.expense.data.db.testDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CategorizerTest {
    @Test
    fun normalisesMerchantVariants() {
        assertEquals("SWIGGY", merchantKey("swiggy@ybl"))
        assertEquals("SWIGGY", merchantKey("SWIGGY  PVT LTD"))
        assertEquals("GAZEBO RESTAURANT", merchantKey("Gazebo Restaurant LLC, Dubai"))
        // Quirk kept on purpose (Q10): digits are dropped.
        assertEquals("ELEVEN", merchantKey("7-ELEVEN"))
        assertEquals("SWIGGY ORDER", merchantKey("Swiggy*Order 123"))
        assertEquals("", merchantKey("A 1 @x"))
    }

    @Test
    fun remembersLearnedMerchantsIncludingLongerVariants() {
        val rules = listOf(
            MerchantRule("GAZEBO", "restaurant"),
            MerchantRule("AMAZON", "shopping"),
            MerchantRule("AMAZON PRIME", "subscriptions")
        )
        assertEquals("restaurant", suggestCategory("Gazebo", rules))
        assertEquals("restaurant", suggestCategory("GAZEBO KARAMA", rules))
        assertEquals("subscriptions", suggestCategory("Amazon Prime Video", rules))
        assertNull(suggestCategory("Unknown Shop", rules))
        assertNull(suggestCategory(null, rules))
        // Whole words only, and keys shorter than 3 never match as a part.
        assertNull(suggestCategory("GAZEBOS", rules))
        assertNull(suggestCategory("AB SHOP", listOf(MerchantRule("AB", "other"))))
        assertEquals("other", suggestCategory("AB", listOf(MerchantRule("AB", "other"))))
    }
}

class AppearanceTest {
    @Test
    fun seededCategoriesHaveFixedLooks() {
        assertEquals(CategoryLook("🛒", 0x008300), categoryLook("groceries"))
        assertEquals(0xd95926, categoryLook("restaurant", dark = true).color)
        assertEquals(CategoryLook("🏷️", 0x8a8984), categoryLook("other"))
        assertEquals(CategoryLook("❔", 0x8f8e88), categoryLook(null, dark = true))
        assertEquals(0x2a78d6, seriesColor(false))
    }

    @Test
    fun userCategoriesGetAStableSlotFromTheirId() {
        // "abc".hashCode() = 96354 → slot 2 (aqua), the same as the JS (h * 31 + code) | 0 hash.
        assertEquals(CategoryLook("🏷️", 0x1baf7a), categoryLook("abc"))
        assertEquals(categoryLook("mhsx1abc"), categoryLook("mhsx1abc"))
    }
}

class CategoryRepositoryTest {
    @Test
    fun addsAfterTheLastOneAndListsByKindThenSort() {
        val db = testDb()
        createCategory(db, "  Kids ", CategoryKind.EXPENSE)
        val all = listCategories(db)
        assertEquals(19, all.size)
        assertEquals("Groceries", all.first().name)
        // sort = max + 1 = 101, so it comes after every seeded expense.
        assertEquals("Kids", all.last { it.kind == CategoryKind.EXPENSE }.name)
        assertEquals(CategoryKind.INCOME, all.last().kind)
    }

    @Test
    fun learnsUpdatesAndForgetsMerchants() {
        val db = testDb()
        learnRule(db, "Carrefour MOE", "groceries")
        learnRule(db, "CARREFOUR moe", "shopping")
        learnRule(db, "Noon", REFUND_CATEGORY)
        learnRule(db, null, "groceries")
        learnRule(db, "Noon", null)
        learnRule(db, "7", "groceries")
        assertEquals(
            listOf(MerchantRule("ASPORA", "remittance"), MerchantRule("CARREFOUR MOE", "shopping")),
            listRules(db)
        )
        deleteRule(db, "CARREFOUR MOE")
        assertEquals(1, listRules(db).size)
    }

    @Test
    fun deletingACategoryDropsItsRules() {
        val db = testDb()
        learnRule(db, "Lulu", "groceries")
        deleteCategory(db, "groceries")
        assertTrue(listCategories(db).none { it.id == "groceries" })
        assertEquals(listOf("ASPORA"), listRules(db).map { it.merchantKey })
    }
}
