package com.najeemfasil.expense.feature

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.najeemfasil.expense.data.AndroidSmsInbox
import com.najeemfasil.expense.data.rememberSmsPermissionRequest
import com.najeemfasil.expense.feature.sms.FakeSmsInbox
import com.najeemfasil.expense.feature.sms.SmsPermissionException
import com.najeemfasil.expense.ui.components.Button
import com.najeemfasil.expense.ui.components.Screen
import com.najeemfasil.expense.ui.setScreen
import com.najeemfasil.expense.ui.testDb
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class SmsPermissionTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theReaderRefusesWithoutPermission() {
        val inbox = AndroidSmsInbox(ApplicationProvider.getApplicationContext())
        assertFalse(inbox.hasPermission())
        assertFailsWith<SmsPermissionException> { inbox.readInbox(0, 10, null) }
        assertFailsWith<SmsPermissionException> { inbox.listSenders(0) }
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.READ_SMS)
        assertEquals(emptyList(), inbox.readInbox(0, 10, null))
    }

    @Test
    fun showsTheRationaleFirstAndNotNowDenies() {
        val results = ArrayList<Boolean>()
        rule.setScreen(testDb(), inbox = FakeSmsInbox(granted = false)) {
            val request = rememberSmsPermissionRequest()
            Screen { Button("Ask", { request { results.add(it) } }) }
        }
        rule.onNodeWithText("Ask").performClick()
        rule.onNodeWithText("Read bank SMS").assertIsDisplayed()
        rule.onNodeWithText("Zeropense reads your bank SMS to find transactions. Messages are processed on this phone and never uploaded.")
            .assertIsDisplayed()
        rule.onNodeWithText("Allow").assertIsDisplayed()
        rule.onNodeWithText("Not now").performClick()
        assertEquals(listOf(false), results)
    }

    @Test
    fun grantedGoesStraightThrough() {
        val results = ArrayList<Boolean>()
        rule.setScreen(testDb(), inbox = FakeSmsInbox(granted = true)) {
            val request = rememberSmsPermissionRequest()
            Screen { Button("Ask", { request { results.add(it) } }) }
        }
        rule.onNodeWithText("Ask").performClick()
        assertEquals(listOf(true), results)
    }
}
