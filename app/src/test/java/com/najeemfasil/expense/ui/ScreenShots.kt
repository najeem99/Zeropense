package com.najeemfasil.expense.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.najeemfasil.expense.ui.theme.ZeropenseTheme

typealias ComposeRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

/** Renders [content] in the app theme and saves it as build/outputs/roborazzi/<name>.png. */
fun ComposeRule.shoot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
    setContent { ZeropenseTheme(dark = dark, content = content) }
    snap(name)
}

fun ComposeRule.snap(name: String) {
    waitForIdle()
    onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
}
