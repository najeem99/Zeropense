package com.najeemfasil.expense

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.najeemfasil.expense.data.AndroidSmsInbox
import com.najeemfasil.expense.data.LocalDb
import com.najeemfasil.expense.data.LocalSmsInbox
import com.najeemfasil.expense.data.db.Db
import com.najeemfasil.expense.ui.ZeropenseApp
import com.najeemfasil.expense.ui.theme.ZeropenseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as ZeropenseApplication
        // The splash stays up until the database is open and migrated.
        splash.setKeepOnScreenCondition { !app.database.isCompleted }
        val inbox = AndroidSmsInbox(this)
        setContent {
            val db by produceState<Db?>(initialValue = null) { value = app.database.await() }
            ZeropenseTheme {
                db?.let {
                    CompositionLocalProvider(LocalDb provides it, LocalSmsInbox provides inbox) { ZeropenseApp() }
                }
            }
        }
    }
}
