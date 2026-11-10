package com.foxhole.guard.ui.cli

import org.junit.Assert.*
import org.junit.Test

class InlineNoticeTextTest {
    @Test fun onlyOneLeadingIconLeavesLocalizedNewlinesAndLongTextIntact() {
        listOf("First line\nSecond line", "Ошибка подключения.\nПроверьте настройки.", "تنبيه طويل\nالسطر التالي", "Long text ".repeat(100)).forEach { message ->
            val value = inlineNoticeMessage(message)
            assertEquals(message, value.text.substring(2))
            assertEquals(1, value.getStringAnnotations(0, value.length).size)
            assertEquals(0, value.getStringAnnotations(0, value.length).single().start)
            assertEquals(1, value.getStringAnnotations(0, value.length).single().end)
        }
    }
}
