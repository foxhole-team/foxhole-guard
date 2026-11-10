package com.foxhole.guard.ui.cli

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import com.foxhole.guard.R

class InlineNoticeFlowAndroidTest {
    @get:Rule val compose = createComposeRule()
    @Test fun wrappedLinesResumeAtFullWidthAtLargeFontScale() {
        var result: TextLayoutResult? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                MaterialTheme { Box(Modifier.width(180.dp)) {
                    InlineNoticeText("A long notice message that wraps over several full width lines.",
                        R.drawable.lin_info, Color.Black, MaterialTheme.typography.bodyMedium,
                        onTextLayout = { result = it })
                } }
            }
        }
        compose.runOnIdle {
            val layout = requireNotNull(result)
            assertTrue(layout.lineCount > 1)
            assertEquals(0f, layout.getLineLeft(1), 1f)
            assertEquals(1, layout.placeholderRects.size)
            assertNotNull(layout.placeholderRects.single())
            assertTrue(requireNotNull(layout.placeholderRects.single()).height > 0f)
        }
    }
}
