package com.mytracksapp.ui.export

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mytracksapp.domain.export.ExportFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T10 — instrumented Compose UI test for the standalone [ExportFormatDialog] composable, in
 * isolation from any screen that might (or, since T10, might not) host it: renders its own
 * `AlertDialog` with GPX/CSV options and a Cancel action, invoking [ExportFormatDialog]'s own
 * callbacks with no dependency on [com.mytracksapp.ui.history.SessionDetailScreen] or any
 * ViewModel/DAO — [SessionDetailScreen]'s export-button visibility now lives in
 * `SessionDetailScreenTest`, and the direct-export-without-a-dialog flow lives in
 * `SessionDetailExportTest`.
 */
@RunWith(AndroidJUnit4::class)
class ExportFormatDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun rendersGpxAndCsvOptionsPlusCancel() {
        composeTestRule.setContent {
            ExportFormatDialog(onDismissRequest = {}, onFormatSelected = {})
        }

        composeTestRule.onNodeWithTag(ExportFormatDialogTestTags.DIALOG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(ExportFormatDialogTestTags.GPX_OPTION).assertIsDisplayed()
        composeTestRule.onNodeWithTag(ExportFormatDialogTestTags.CSV_OPTION).assertIsDisplayed()
        composeTestRule.onNodeWithTag(ExportFormatDialogTestTags.CANCEL).assertIsDisplayed()
    }

    @Test
    fun tappingGpxInvokesOnFormatSelectedWithGpx() {
        var selected: ExportFormat? = null
        composeTestRule.setContent {
            ExportFormatDialog(onDismissRequest = {}, onFormatSelected = { selected = it })
        }

        composeTestRule.onNodeWithTag(ExportFormatDialogTestTags.GPX_OPTION).performClick()

        assertEquals(ExportFormat.GPX, selected)
    }

    @Test
    fun tappingCsvInvokesOnFormatSelectedWithCsv() {
        var selected: ExportFormat? = null
        composeTestRule.setContent {
            ExportFormatDialog(onDismissRequest = {}, onFormatSelected = { selected = it })
        }

        composeTestRule.onNodeWithTag(ExportFormatDialogTestTags.CSV_OPTION).performClick()

        assertEquals(ExportFormat.CSV, selected)
    }

    @Test
    fun tappingCancelInvokesOnDismissRequestWithoutSelectingAFormat() {
        var dismissed = false
        var selected: ExportFormat? = null
        composeTestRule.setContent {
            ExportFormatDialog(
                onDismissRequest = { dismissed = true },
                onFormatSelected = { selected = it },
            )
        }

        composeTestRule.onNodeWithTag(ExportFormatDialogTestTags.CANCEL).performClick()

        assertEquals(true, dismissed)
        assertNull(selected)
    }

    @Test
    fun exactlyOneDialogAndOneOfEachOptionAreRendered() {
        composeTestRule.setContent {
            ExportFormatDialog(onDismissRequest = {}, onFormatSelected = {})
        }

        composeTestRule.onAllNodesWithTag(ExportFormatDialogTestTags.DIALOG).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(ExportFormatDialogTestTags.GPX_OPTION).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(ExportFormatDialogTestTags.CSV_OPTION).assertCountEquals(1)
    }
}
