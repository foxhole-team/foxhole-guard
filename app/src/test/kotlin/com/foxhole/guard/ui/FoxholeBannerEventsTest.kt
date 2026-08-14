package com.foxhole.guard.ui

import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Владелец поймал ровно этот сценарий: телефон в кармане, активность уничтожена, сервис VPN пишет
 * ошибки и реконнекты — и в терминале потом ничего. Баннеры ехали через `MutableSharedFlow` с
 * `replay = 0`, а единственный подписчик живёт в композиции, так что всё, что случилось без UI,
 * пропадало молча.
 *
 * Тесты ниже падают на прежней шине: у SharedFlow без подписчиков эмиссия — это no-op.
 */
class FoxholeBannerEventsTest {

    private fun banner(message: String) =
        FoxholeBannerEvent(message = message, tone = FoxholeBannerTone.INFO)

    @Test
    fun `an event emitted with no subscriber reaches the next subscriber`() {
        val events = FoxholeBannerEvents()

        // Никто не слушает: активность уничтожена, сервис продолжает работать.
        assertTrue(events.tryEmit(banner("reconnect: handshake timeout")))
        assertTrue(events.tryEmit(banner("tunnel restored")))

        val delivered = runBlocking {
            withTimeout(TIMEOUT_MS) { events.stream.take(2).toList() }
        }

        assertEquals(
            listOf("reconnect: handshake timeout", "tunnel restored"),
            delivered.map { it.message },
        )
    }

    @Test
    fun `a subscriber that goes away does not take the buffer with it`() {
        val events = FoxholeBannerEvents()

        runBlocking {
            // Жизнь первая: активность подписана и получает свою строку.
            val first = async { withTimeout(TIMEOUT_MS) { events.stream.take(1).toList() } }
            events.emit(banner("first life"))
            assertEquals(listOf("first life"), first.await().map { it.message })

            // Поворот/сворачивание: подписчик умер вместе с композицией.
            val orphaned: Job = launch { events.stream.collect { } }
            orphaned.cancelAndJoin()

            // Между жизнями сервис продолжает писать.
            events.emit(banner("emitted with the ui destroyed"))

            // Жизнь вторая доигрывает пропущенное.
            val second = withTimeout(TIMEOUT_MS) { events.stream.take(1).toList() }
            assertEquals(listOf("emitted with the ui destroyed"), second.map { it.message })
        }
    }

    @Test
    fun `a full buffer drops the oldest line instead of blocking the producer`() {
        val capacity = 4
        val events = FoxholeBannerEvents(capacity = capacity)

        runBlocking {
            // Продюсеры — корутины вью-модели; заблокировать их на закрытом UI хуже, чем потерять
            // голову бэклога, поэтому withTimeout здесь и есть проверка «emit не подвисает».
            withTimeout(TIMEOUT_MS) {
                repeat(capacity + 2) { index -> events.emit(banner("line $index")) }
            }

            val delivered = withTimeout(TIMEOUT_MS) { events.stream.take(capacity).toList() }
            assertEquals(
                listOf("line 2", "line 3", "line 4", "line 5"),
                delivered.map { it.message },
            )
        }
    }

    /** Шина заведена в вью-модели, а не только существует рядом с ней. */
    @Test
    fun `the view model routes banners through the buffered bus`() {
        val field = HomeViewModel::class.java.getDeclaredField("snackbars")

        assertEquals(FoxholeBannerEvents::class.java, field.type)
    }

    /**
     * Инвариант одного потребителя: `receiveAsFlow` отдаёт элемент ровно одному коллектору, так что
     * второй подписчик не продублирует журнал, а разрежет его пополам. Единственный законный
     * коллектор — CliBannerBridge.
     */
    @Test
    fun `the buffered bus keeps exactly one consumer`() {
        val collectSites = mainKotlinSources()
            .filter { file -> file.readText().contains("snackbars.stream") }
            .map { file -> file.name }
            .sorted()

        assertEquals(listOf("CliApp.kt"), collectSites)
    }

    private fun mainKotlinSources(): List<File> =
        repoRoot()
            .resolve("app/src/main/kotlin")
            .walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .toList()

    private fun repoRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { dir -> dir.resolve("settings.gradle.kts").isFile }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
