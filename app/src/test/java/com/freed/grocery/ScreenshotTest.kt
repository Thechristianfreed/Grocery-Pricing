package com.freed.grocery

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders each screen from a real captured snapshot at Pixel 6 size. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-420dpi")
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val state = UiState(
        snapshot = Parser.snapshot(res("prices.json")),
        history = Parser.history(res("history.json")),
    )

    private fun show(s: UiState = state) = rule.setContent {
        GroceryTheme { GroceryApp(s, onRefresh = {}, onToggleStore = {}) }
    }

    private fun shot(name: String) = rule.onRoot().captureRoboImage("build/screenshots/$name.png")

    @Test fun staples() {
        show()
        shot("1-staples")
        rule.onNodeWithText("Butter (1 lb)").performClick()
        rule.waitForIdle()
        shot("2-staple-expanded")
    }

    @Test fun deals() {
        show()
        rule.onNodeWithText("Deals").performClick()
        rule.waitForIdle()
        shot("3-deals")
    }

    @Test fun history() {
        show()
        rule.onNodeWithText("History").performClick()
        rule.waitForIdle()
        shot("4-history-list")
        rule.onNodeWithText("Butter (1 lb)").performClick()
        rule.waitForIdle()
        shot("5-history-item")
    }

    @Test fun storesDialogAndOfflineError() {
        show(state.copy(error = "Unable to resolve host"))
        rule.onNodeWithText("Stores").performClick()
        rule.waitForIdle()
        shot("6-stores-and-error")
    }
}
