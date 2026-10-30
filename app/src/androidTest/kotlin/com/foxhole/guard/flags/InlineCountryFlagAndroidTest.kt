package com.foxhole.guard.flags

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.ui.cli.CliTheme
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.widget.styledWidgetFlagSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class InlineCountryFlagAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun flagHeightMatchesVisibleCapitalInkAtEachTypefaceAndFontScale() {
        val scale = mutableFloatStateOf(1f)
        val pixel = mutableStateOf(false)
        compose.setContent {
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(deviceDensity, scale.floatValue)) {
                CliTheme(pixelArtEnabled = pixel.value) {
                    val style = (if (pixel.value) CliType.button else CliType.body).copy(fontSize = 18.sp)
                    Row(Modifier.background(Color.Black), verticalAlignment = Alignment.CenterVertically) {
                        Text("H", Modifier.testTag("capital"), color = Color.White, style = style)
                        CliFlagIcon("gb", Modifier.testTag("flag"), style)
                    }
                }
            }
        }
        for (pixels in listOf(false, true)) for (fontScale in listOf(.85f, 1f, 1.3f, 2f)) {
            compose.runOnIdle { pixel.value = pixels; scale.floatValue = fontScale }
            val image = compose.onNodeWithTag("capital").captureToImage().toPixelMap()
            val inkRows = (0 until image.height).filter { y ->
                (0 until image.width).any { x -> image[x, y].let { it.alpha > .5f && it.red > .5f && it.green > .5f && it.blue > .5f } }
            }
            assertTrue(inkRows.isNotEmpty())
            val capitalHeight = inkRows.last() - inkRows.first() + 1
            val flag = compose.onNodeWithTag("flag").fetchSemanticsNode().boundsInRoot
            assertTrue("Flag ${flag.height}px differs from capital ${capitalHeight}px", abs(flag.height - capitalHeight) <= 1.5f)
            assertTrue(abs(flag.width - flag.height * 4f / 3f) <= 1f)
        }
    }

    @Test fun languageFlagsHaveNativeVectorsAndWidgetFlagsReuseCanonicalBitmaps() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf("gb", "ru", "de", "fr", "es", "jp", "cn").forEach { code ->
            val resource = countryFlagVectorResource(code)
            assertNotNull(resource)
            val vector = requireNotNull(context.getDrawable(requireNotNull(resource)))
            val preview = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
            vector.setBounds(0, 0, preview.width, preview.height)
            vector.draw(Canvas(preview))
            assertTrue(preview.getPixel(32, 24) ushr 24 != 0)
            val bitmap = requireNotNull(countryFlagBitmap(context, code))
            assertEquals(256, bitmap.width)
            assertEquals(192, bitmap.height)
            for (pixelArt in listOf(false, true)) {
                val size = styledWidgetFlagSize(context, bitmap, 13, pixelArt)
                assertTrue(size.height.value > 0f)
                assertEquals(4f / 3f, size.width.value / size.height.value, .0001f)
            }
        }
    }
}
