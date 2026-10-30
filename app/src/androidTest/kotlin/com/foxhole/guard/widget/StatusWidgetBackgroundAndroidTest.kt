package com.foxhole.guard.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class StatusWidgetBackgroundAndroidTest {
    @Test
    fun actionSurfacesHaveRealRoundedCornersAndOpaqueCenters() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = context.resources.displayMetrics.density
        val width = (168 * density).roundToInt()
        val height = (48 * density).roundToInt()
        val cornerSample = (4 * density).roundToInt()
        listOf(
            R.drawable.widget_simple_action_surface_dark to Color.BLACK,
            R.drawable.widget_simple_action_surface_light to Color.WHITE,
        ).forEach { (resource, expectedColor) ->
            val drawable = checkNotNull(context.getDrawable(resource)).mutate()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                drawable.setBounds(0, 0, width, height)
                drawable.draw(Canvas(bitmap))
                assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
                assertEquals(0, Color.alpha(bitmap.getPixel(cornerSample, cornerSample)))
                assertEquals(expectedColor, bitmap.getPixel(width / 2, height / 2))
                assertEquals(expectedColor, bitmap.getPixel(width / 2, height - cornerSample))
            } finally {
                bitmap.recycle()
            }
        }
    }
}
