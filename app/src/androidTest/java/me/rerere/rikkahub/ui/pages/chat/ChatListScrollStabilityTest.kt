package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatListScrollStabilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun normalLayoutKeepsReadingPositionWhenNewMessageArrivesAtBottom() {
        lateinit var listState: LazyListState
        val messages = mutableStateListOf<String>().apply {
            addAll((0 until 20).map { "msg$it" })
        }

        composeRule.setContent {
            listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("chat_list"),
            ) {
                items(messages) { text ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = text)
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat_list").performTouchInput {
            swipeDown()
        }
        composeRule.waitForIdle()

        val firstIndexBefore = listState.firstVisibleItemIndex
        val offsetBefore = listState.firstVisibleItemScrollOffset
        assertTrue("swipe should leave bottom", firstIndexBefore > 0)

        val visibleTextBefore = messages[firstIndexBefore]

        // New message appended at the bottom, same as generation appending content.
        composeRule.runOnUiThread {
            messages.add("new")
        }
        composeRule.waitForIdle()

        assertEquals(firstIndexBefore, listState.firstVisibleItemIndex)
        assertEquals(offsetBefore, listState.firstVisibleItemScrollOffset)
        composeRule.onNodeWithText(visibleTextBefore).assertIsDisplayed()
    }
}
