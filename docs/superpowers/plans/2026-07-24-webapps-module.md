# Web Apps Module Implementation Plan (FoxHole Guard Final)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Модуль «web apps» — добавление HTML5-веб-аппов с открытием в полноэкранном WebView-фрейме, вотчдогом уведомлений (опрос через невидимый WebView + JS-шим), бейджами, вкладкой в доке между map и stats, и двумя Glance-виджетами (web apps + статус подключения) с конфигом чёрный/белый + прозрачность 0–100.

**Architecture:** Всё в модуле `:app` (кроме модели настроек — `:core:model`), по существующим паттернам: настройки — extension-функции на едином `SettingsRepository` + нормализация-инвариант «пуш требует фаервол»; данные — Room `ProfileDatabase` v9→v10 с явной миграцией; вотчдог — экземпляр `RuntimeSessionTicker` в app-процессе + страховочный WorkManager-worker; шим — `WebViewCompat.addDocumentStartJavaScript` + `addWebMessageListener`; виджеты — Glance 1.1.1.

**Tech Stack:** Kotlin 2.4.10, Compose BOM 2026.06.01, Room+SQLCipher, WorkManager 2.11.2, OkHttp (`BoundedPublicHttpFetch`), НОВЫЕ зависимости: `androidx.webkit` (последняя стабильная), `androidx.glance:glance-appwidget:1.1.1`.

**Проект:** `exp/foxhole_guard_final_build`, ветка на момент анализа `front-final-polish` (HEAD `5741a4ba`). Рабочую ветку/место назначает пользователь перед стартом.

## Global Constraints

- `minSdk 26`, `targetSdk 37`, `compileSdk 37`; AGP 9.3.0; Gradle 9.6.1; версии — только через `gradle/libs.versions.toml`.
- Никаких новых Gradle-модулей: модель — в `:core:model` (чистый JVM, без Android-типов!), всё остальное — в `:app`.
- Room: `fallbackToDestructiveMigration(false)` — только явная миграция + запись в `ALL_MIGRATIONS`, `exportSchema = true`, миграционный чейн-тест обязан пройти.
- Строки: lowercase CLI-стиль, обязательно `values/` + `values-ru/` (`cli_webapps_*`, `cli_dock_webapps`).
- Иконки дока: препроцессированный 1-bit PNG в `app/src/main/res/drawable-nodpi/` (пайплайн в `ex/`), НЕ vector XML.
- Консент на включение фаервола: CLI спрашивает всегда, «тихого» пути нет (комментарий в `CliLockSection.kt:186`) — не добавлять silent-путь.
- Порядок объявления `CliScreen` = порядок дока; WEBAPPS — между MAP и STATS.
- Ограничение платформы: Web Push API в Android WebView отсутствует — только опрос; честно фиксируем в справке.
- Каждый таск = отдельный коммит; TDD где тестируемо (JVM-логика), UI — сборка + ручная проверка.

## Зафиксированные решения (из брейншторма с пользователем)

1. Вотчдог = опрос невидимым WebView с JS-шимом (Notification/setAppBadge/`(N)` в title).
2. Пуш вкл → фаервол вкл (через существующий консент); фаервол выкл вручную → пуш выкл (инвариант в `normalized()`).
3. Интервал опроса: дропдаун 1/5/15/30 мин, дефолт 5.
4. Дефолты без возражений пользователя: лимита на число аппов нет (виджет берёт первые 4/8 по sortOrder), long-press меню — только delete/rename, внешние ссылки из фрейма блокируются с заметкой в терминал, ru-метка дока — `webapps` (команды не локализуются).

---

### Task 1: Модель настроек + нормализация

**Files:**
- Create: `core/model/src/main/kotlin/com/foxhole/core/model/WebAppsModels.kt`
- Modify: `core/model/src/main/kotlin/com/foxhole/core/model/SettingsModels.kt:354-374` (класс `Settings`)
- Modify: `app/src/main/kotlin/com/foxhole/guard/core/settings/SettingsNormalizationSupport.kt` (внутрь `Settings.normalized()`)
- Test: `app/src/test/kotlin/com/foxhole/guard/core/settings/WebAppsNormalizationTest.kt`

**Interfaces:**
- Produces: `WebAppsSettings(enabled, pushServiceEnabled, dockScreenEnabled, pollIntervalMinutes)`, `Settings.webApps`, `WEB_APPS_POLL_OPTIONS = listOf(1, 5, 15, 30)`, `WEB_APPS_POLL_DEFAULT_MINUTES = 5`.

- [ ] **Step 1: Написать красный тест нормализации**

```kotlin
class WebAppsNormalizationTest {
    @Test
    fun `push is forced off when firewall is off`() {
        val s = Settings(
            webApps = WebAppsSettings(enabled = true, pushServiceEnabled = true),
            expert = ExpertSettings(firewallEnabled = false),
        ).normalized()
        assertFalse(s.webApps.pushServiceEnabled)
    }

    @Test
    fun `push survives when firewall is on`() {
        val s = Settings(
            webApps = WebAppsSettings(enabled = true, pushServiceEnabled = true),
            expert = ExpertSettings(firewallEnabled = true),
        ).normalized()
        assertTrue(s.webApps.pushServiceEnabled)
    }

    @Test
    fun `poll interval snaps to nearest allowed option`() {
        val s = Settings(webApps = WebAppsSettings(pollIntervalMinutes = 7)).normalized()
        assertEquals(5, s.webApps.pollIntervalMinutes)
        val s2 = Settings(webApps = WebAppsSettings(pollIntervalMinutes = -3)).normalized()
        assertEquals(1, s2.webApps.pollIntervalMinutes)
    }
}
```

- [ ] **Step 2: Запустить — убедиться, что не компилируется/падает** (`./gradlew :app:testDebugUnitTest --tests '*WebAppsNormalizationTest*'` → FAIL: `WebAppsSettings` не существует)

- [ ] **Step 3: Реализовать модель**

`WebAppsModels.kt` (паттерн — `I2pModels.kt`, тот же пакет):

```kotlin
package com.foxhole.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

const val WEB_APPS_POLL_DEFAULT_MINUTES = 5
val WEB_APPS_POLL_OPTIONS: List<Int> = listOf(1, 5, 15, 30)

/**
 * Модуль web apps («Дополнения»). Пуш-сервис — вотчдог-опрос сайтов невидимым WebView;
 * настоящих Web Push в Android WebView нет, поэтому pushServiceEnabled валиден только
 * при включённом фаерволе (инвариант держит Settings.normalized()).
 */
@Serializable
@Immutable
data class WebAppsSettings(
    val enabled: Boolean = false,
    val pushServiceEnabled: Boolean = false,
    val dockScreenEnabled: Boolean = false,
    val pollIntervalMinutes: Int = WEB_APPS_POLL_DEFAULT_MINUTES,
)
```

ВНИМАНИЕ: `:core:model` — чистый JVM-модуль; `@Immutable` там уже используется (см. соседние классы) — импорт совпадает с существующими файлами. В `Settings` добавить поле после `appLock`:

```kotlin
    val webApps: WebAppsSettings = WebAppsSettings(),
```

Проверить конвенцию бампа: `git log -S 'val i2p: I2pSettings' --oneline` — если добавление блока сопровождалось бампом `SETTINGS_SCHEMA_VERSION` (`core/model/.../Models.kt:6`, сейчас 18), поднять до 19 тем же способом; если нет — не трогать (kotlinx-дефолт обратно совместим).

В `SettingsNormalizationSupport.kt` добавить в цепочку `normalized()` шаг:

```kotlin
private fun Settings.normalizedWebApps(): Settings {
    val interval = WEB_APPS_POLL_OPTIONS.minByOrNull { option ->
        kotlin.math.abs(option - webApps.pollIntervalMinutes)
    } ?: WEB_APPS_POLL_DEFAULT_MINUTES
    val push = webApps.pushServiceEnabled && expert.firewallEnabled
    val next = webApps.copy(pushServiceEnabled = push, pollIntervalMinutes = interval)
    return if (next == webApps) this else copy(webApps = next)
}
```

и вызвать его так же, как существующие `normalizedXxx()`-шаги в том файле (встроить в общую цепочку вызовов).

- [ ] **Step 4: Тесты зелёные** (`./gradlew :app:testDebugUnitTest --tests '*WebAppsNormalizationTest*'` → PASS)
- [ ] **Step 5: Commit** — `feat(webapps): settings model + push/firewall invariant`

---

### Task 2: Репозиторий настроек + VM-support

**Files:**
- Create: `app/src/main/kotlin/com/foxhole/guard/core/settings/SettingsRepositoryWebApps.kt`
- Create: `app/src/main/kotlin/com/foxhole/guard/ui/HomeViewModelWebAppsSupport.kt`

**Interfaces:**
- Consumes: `SettingsRepository.update {}` (`SettingsRepository.kt:225`), `HomeViewModel.syncLocalGuardWithPermissionRequest()` (`HomeViewModelSecuritySettingsSupport.kt:330`).
- Produces: `SettingsRepository.updateWebAppsEnabled/updateWebAppsPushService/updateWebAppsDockScreen/updateWebAppsPollIntervalMinutes`; `HomeViewModel.onWebAppsEnabledChanged(Boolean)`, `onWebAppsPushServiceChanged(Boolean)`, `onWebAppsDockScreenChanged(Boolean)`, `onWebAppsPollIntervalChanged(Int)`.

- [ ] **Step 1: Extension-функции** (паттерн `SettingsRepositoryI2p.kt` — файл extension-функций на единственном `SettingsRepository`; НЕ отдельный класс):

```kotlin
package com.foxhole.guard.core.settings

import com.foxhole.core.model.WebAppsSettings

suspend fun SettingsRepository.updateWebAppsEnabled(value: Boolean) =
    update { current -> current.copy(webApps = current.webApps.copy(enabled = value)) }

/** Включение пуш-сервиса тянет фаервол в том же атомарном апдейте (UI уже показал консент). */
suspend fun SettingsRepository.updateWebAppsPushService(value: Boolean) =
    update { current ->
        current.copy(
            webApps = current.webApps.copy(pushServiceEnabled = value),
            expert = if (value) current.expert.copy(firewallEnabled = true) else current.expert,
        )
    }

suspend fun SettingsRepository.updateWebAppsDockScreen(value: Boolean) =
    update { current -> current.copy(webApps = current.webApps.copy(dockScreenEnabled = value)) }

suspend fun SettingsRepository.updateWebAppsPollIntervalMinutes(value: Int) =
    update { current -> current.copy(webApps = current.webApps.copy(pollIntervalMinutes = value)) }
```

Клампы не дублируем — `normalized()` из Task 1 отработает на каждом `update`.

- [ ] **Step 2: VM-support** (паттерн любого `HomeViewModel*Support.kt` — extension-функции на `HomeViewModel`):

```kotlin
package com.foxhole.guard.ui

import com.foxhole.guard.core.settings.updateWebAppsDockScreen
import com.foxhole.guard.core.settings.updateWebAppsEnabled
import com.foxhole.guard.core.settings.updateWebAppsPollIntervalMinutes
import com.foxhole.guard.core.settings.updateWebAppsPushService
import kotlinx.coroutines.launch

fun HomeViewModel.onWebAppsEnabledChanged(value: Boolean) = viewModelScope.launch {
    settingsRepository.updateWebAppsEnabled(value)
}

/** Вызывается ПОСЛЕ консента на фаервол (UI-форма y/n): пишет настройку и реконсилирует guard. */
fun HomeViewModel.onWebAppsPushServiceChanged(value: Boolean) = viewModelScope.launch {
    settingsRepository.updateWebAppsPushService(value)
    syncLocalGuardWithPermissionRequest()
}

fun HomeViewModel.onWebAppsDockScreenChanged(value: Boolean) = viewModelScope.launch {
    settingsRepository.updateWebAppsDockScreen(value)
}

fun HomeViewModel.onWebAppsPollIntervalChanged(value: Int) = viewModelScope.launch {
    settingsRepository.updateWebAppsPollIntervalMinutes(value)
}
```

Точные модификаторы доступа (`settingsRepository`, `viewModelScope`) взять из соседнего support-файла — повторить как там.
Обратное направление (фаервол выкл → пуш выкл) уже закрыто Task 1: `onFirewallEnabledChanged(false)` пройдёт через `normalized()`.

- [ ] **Step 3: Сборка** `./gradlew :app:compileDebugKotlin` → OK
- [ ] **Step 4: Commit** — `feat(webapps): settings repository extensions + VM support`

---

### Task 3: UI настроек — секция «Дополнения» + суб-экран

**Files:**
- Modify: `app/src/main/kotlin/com/foxhole/guard/ui/cli/settings/CliExtrasSection.kt`
- Modify: `app/src/main/kotlin/com/foxhole/guard/ui/cli/settings/CliSettingsScreen.kt` (ключ `SUB_WEBAPPS = "webapps"` рядом с 241-251, ветка в `CliCfgSubScreen` 201-216, проброс колбэка в `CliExtrasSection` 175-181)
- Create: `app/src/main/kotlin/com/foxhole/guard/ui/cli/settings/CliWebAppsSubScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-ru/strings.xml`

**Interfaces:**
- Consumes: Task 2 колбэки; `CliToggleRow`/`CliActionRow`/`CliDropdownRow`/`CliChip`/`CliElbowLine` (существующие CLI-компоненты); консент-паттерн `CliFirewallRows` (`CliLockSection.kt:160-203`).
- Produces: суб-экран `SUB_WEBAPPS`; строки `cli_extras_webapps`, `cli_webapps_push`, `cli_webapps_push_note`, `cli_webapps_interval`, `cli_webapps_dock`, `cli_webapps_firewall_warning`.

- [ ] **Step 1:** В `CliExtrasSection` добавить параметры `onOpenWebApps: () -> Unit` и заменить локальный гейт-паттерн file-share на НАСТОЯЩИЙ настройко-backed тумблер (у file share гейт локальный, потому что фича-заглушка; webapps — реальная):

```kotlin
val webAppsEnabled = settings.webApps.enabled
CliToggleRow(
    label = stringResource(R.string.cli_extras_webapps),
    checked = webAppsEnabled,
    onToggle = onWebAppsEnabledChanged,
)
if (webAppsEnabled) {
    CliActionRow(
        label = stringResource(R.string.cli_extras_open_settings),
        onTap = onOpenWebApps,
    )
}
```

(колбэк `onWebAppsEnabledChanged: (Boolean) -> Unit` — новый параметр секции; в `CliSettingsScreen` прокинуть `viewModel::onWebAppsEnabledChanged` и `onOpenWebApps = { onOpenSub(SUB_WEBAPPS) }`.)

- [ ] **Step 2:** `CliWebAppsSubScreen.kt` — back-row сверху (паттерн `CliLogsSubScreen`, `CliSettingsScreen.kt:220-239`), затем:
  - `CliToggleRow` «использовать пуш-сервис» с note `cli_webapps_push_note` («поднимет фаервол; проверка по расписанию, не мгновенно»). Включение при выключенном фаерволе → консент-форма y/n по образцу `CliFirewallRows` (`CliElbowLine` предупреждение + `CliChip` yes/no; текст — `cli_webapps_firewall_warning`); yes → `viewModel.onWebAppsPushServiceChanged(true)`; при уже включённом фаерволе — сразу. Выключение — сразу `onWebAppsPushServiceChanged(false)`.
  - `CliDropdownRow` «интервал проверки» c опциями из `WEB_APPS_POLL_OPTIONS` (метки «1 мин»/«5 мин»/…), значение `settings.webApps.pollIntervalMinutes`, колбэк `onWebAppsPollIntervalChanged`.
  - `CliToggleRow` «экран и значок в доке» → `onWebAppsDockScreenChanged`.
- [ ] **Step 3:** Строки в `values/strings.xml` + `values-ru/strings.xml` (lowercase; en: `web apps`, `push service`, `poll interval`, `dock screen`; ru: `веб-аппы`, `пуш-сервис`, `интервал проверки`, `экран в доке`; warning — как у фаервола, с упоминанием что фаервол будет включён).
- [ ] **Step 4:** Сборка + ручная проверка на эмуляторе/Realme: тумблер, провал, консент, дропдаун. Скрин-чек: секция и суб-экран в каноне (чёрный/оранж/циан, Silkscreen).
- [ ] **Step 5: Commit** — `feat(webapps): extras toggle + webapps settings sub-screen`

---

### Task 4: Room — таблица web_apps, миграция 9→10

**Files:**
- Create: `app/src/main/kotlin/com/foxhole/guard/core/data/WebAppEntity.kt` (entity + dao)
- Modify: `app/src/main/kotlin/com/foxhole/guard/core/data/ProfileDatabase.kt` (версия 10 на строке 17, entity в списке 20-35, `MIGRATION_9_10` + `ALL_MIGRATIONS` 408-418, abstract dao)
- Test: расширить существующий миграционный чейн-тест (найти по `ALL_MIGRATIONS` в `app/src/androidTest` или `app/src/test`)

**Interfaces:**
- Produces:

```kotlin
@Entity(tableName = "web_apps")
data class WebAppEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,          // нормализованный https-origin (+ стартовый путь)
    val name: String,
    val iconPath: String?,    // относительный путь в filesDir, null = заглушка
    val sortOrder: Int,
    val badgeCount: Int = 0,
    val lastPolledAt: Long? = null,
    val createdAt: Long,
)

@Dao
interface WebAppDao {
    @Query("select * from web_apps order by sortOrder asc, id asc")
    fun observeAll(): Flow<List<WebAppEntity>>
    @Query("select * from web_apps order by sortOrder asc, id asc")
    suspend fun listAll(): List<WebAppEntity>
    @Insert suspend fun insert(entity: WebAppEntity): Long
    @Query("update web_apps set name = :name where id = :id")
    suspend fun rename(id: Long, name: String)
    @Query("delete from web_apps where id = :id")
    suspend fun delete(id: Long)
    @Query("update web_apps set badgeCount = :count, lastPolledAt = :polledAt where id = :id")
    suspend fun updateBadge(id: Long, count: Int, polledAt: Long)
    @Query("update web_apps set badgeCount = 0 where id = :id")
    suspend fun resetBadge(id: Long)
}
```

- [ ] **Step 1:** Миграция (стиль — lowercase SQL как `MIGRATION_5_6`, `ProfileDatabase.kt:352-359`):

```kotlin
private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "create table if not exists `web_apps` (" +
                "`id` integer primary key autoincrement not null, " +
                "`url` text not null, " +
                "`name` text not null, " +
                "`iconPath` text, " +
                "`sortOrder` integer not null, " +
                "`badgeCount` integer not null default 0, " +
                "`lastPolledAt` integer, " +
                "`createdAt` integer not null)",
        )
    }
}
```

`PROFILE_DATABASE_VERSION = 10`, entity в `@Database`, `MIGRATION_9_10` в `ALL_MIGRATIONS`, `abstract fun webAppDao(): WebAppDao`. Схему 10 закоммитить в exported schemas.
ВНИМАНИЕ: DDL в миграции обязан бит-в-бит совпасть со схемой, которую Room сгенерит из entity (nullability/default) — сверить по exported JSON schema 10.

- [ ] **Step 2:** Прогнать миграционный чейн-тест → PASS.
- [ ] **Step 3: Commit** — `feat(webapps): room table + migration 9->10`

---

### Task 5: WebAppsRepository — метаданные, иконки, бейджи

**Files:**
- Create: `app/src/main/kotlin/com/foxhole/guard/core/data/WebAppsRepository.kt`
- Create: `app/src/main/kotlin/com/foxhole/guard/core/data/WebAppMetadata.kt` (чистый парсер)
- Modify: `app/src/main/kotlin/com/foxhole/guard/FoxholeAppGraphModules.kt` (lazy-синглтон, паттерн `settingsRepository` на :81) + интерфейс графа в `FoxholeAppGraph.kt`
- Test: `app/src/test/kotlin/com/foxhole/guard/core/data/WebAppMetadataTest.kt`

**Interfaces:**
- Consumes: `WebAppDao` (Task 4); `executeBoundedPublicGet(...)` и `withBoundedRemoteFetchTimeouts(...)` (`BoundedPublicHttpFetch.kt:28,41` — `internal`, тот же модуль).
- Produces:

```kotlin
data class WebAppPreview(val url: String, val name: String, val iconBytes: ByteArray?)

class WebAppsRepository(
    private val context: Context,
    private val dao: WebAppDao,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
) {
    val webApps: Flow<List<WebAppEntity>> = dao.observeAll()
    suspend fun preview(rawUrl: String): Result<WebAppPreview>
    suspend fun add(preview: WebAppPreview): Long        // пишет иконку в filesDir/webapps/icons/<id>.img
    suspend fun rename(id: Long, name: String)
    suspend fun remove(id: Long)                          // удаляет и файл иконки
    suspend fun resetBadge(id: Long)
    suspend fun setBadge(id: Long, count: Int, polledAt: Long)
    fun iconFile(entity: WebAppEntity): File?
    fun onBadgesChanged(listener: () -> Unit)             // для updateAll() виджета (Task 11)
}
```

Чистый парсер (тестируемый на JVM, без Android):

```kotlin
data class WebAppMetadata(
    val manifestUrl: String?,
    val iconCandidates: List<String>,  // по приоритету: manifest icons > apple-touch-icon > icon > /favicon.ico
    val siteName: String?,             // og:site_name > <title>
)
fun parseWebAppHtml(baseUrl: String, html: String): WebAppMetadata
fun parseManifestJson(baseUrl: String, json: String): Pair<String?, List<String>>  // name, icon urls (крупнейшая сначала)
fun resolveUrl(base: String, href: String): String
fun parseTitleBadge(title: String): Int?   // "(3) Inbox" / "Inbox (12)" -> 3/12, иначе null
```

- [ ] **Step 1: Красные тесты парсера** — `parseWebAppHtml` (link rel="manifest", apple-touch-icon, rel=icon с sizes, og:site_name, title, относительные href); `parseTitleBadge` («(3) inbox»→3, «chat (12)»→12, «no badge»→null, «(0)»→0, защита от «(2026)» в годах — берём только префиксные/суффиксные скобки со значением ≤ 9999); `resolveUrl` (абсолютный, //host, /path, path).
- [ ] **Step 2:** FAIL → реализация парсеров на regex (без Jsoup — новых парс-зависимостей не заводим; manifest JSON — через уже подключённый kotlinx.serialization `Json { ignoreUnknownKeys = true; isLenient = true }`).
- [ ] **Step 3:** `preview()`: нормализовать ввод (без схемы → `https://`), `executeBoundedPublicGet(client, url, allowHttp = false, maxBytes = 512 * 1024)`; распарсить; если есть manifest — догрузить (256 KiB) и взять name/icons оттуда приоритетно; иконку качать до 512 KiB, первая успешная из кандидатов. Ошибки → `Result.failure` (UI покажет в терминал-стиле). Иконка пишется в `File(context.filesDir, "webapps/icons/<rowId>.img")` при `add()`.
- [ ] **Step 4:** Тесты PASS; регистрация в графе; сборка.
- [ ] **Step 5: Commit** — `feat(webapps): repository + metadata/icon fetch`

---

### Task 6: Вкладка WEBAPPS в доке

**Files:**
- Modify: `app/src/main/kotlin/com/foxhole/guard/ui/cli/CliScreen.kt:8-15`
- Modify: `app/src/main/kotlin/com/foxhole/guard/ui/cli/components/CliHintBar.kt:29-80`
- Modify: `app/src/main/kotlin/com/foxhole/guard/ui/cli/CliApp.kt` (87-92 restore, 146-157 visibility-гейт, 194-201 when, 211 hint bar)
- Create: `app/src/main/res/drawable-nodpi/pix_webapps.png` (пайплайн `ex/`, 1-bit, глиф «сетка/глобус» из пака nikoichu)
- Modify: strings (`cli_dock_webapps` = `webapps` в обеих локалях)

**Interfaces:**
- Consumes: `settingsRouteState` (`StateFlow<SettingsRouteUiState>`, поле `.settings`) — уже есть у `HomeViewModel`.
- Produces: `CliScreen.WEBAPPS`; `CliHintBar(current, onSelect, screens: List<CliScreen>)`; видимость = `settings.webApps.enabled && settings.webApps.dockScreenEnabled`.

- [ ] **Step 1:** Enum: `HOME, PROFILES, APPS, MAP, WEBAPPS, STATS, SETTINGS` (между map и stats — порядок объявления = порядок дока и направление слайда).
- [ ] **Step 2:** `CliHintBar`: новый параметр `screens: List<CliScreen> = CliScreen.entries`, итерировать по нему вместо `CliScreen.entries` (строка 44). В `dockIcon`/`dockLabel` добавить ветки `WEBAPPS -> R.drawable.pix_webapps` / `R.string.cli_dock_webapps`.
- [ ] **Step 3:** `CliApp`:

```kotlin
val settingsState by viewModel.settingsRouteState.collectAsStateWithLifecycle()
val webAppsVisible = settingsState.settings.webApps.enabled &&
    settingsState.settings.webApps.dockScreenEnabled
val dockScreens = if (webAppsVisible) CliScreen.entries.toList()
    else CliScreen.entries.filter { it != CliScreen.WEBAPPS }
// Вкладку выключили, пока она была активна (или restore со старого стейта) — мягкий откат.
LaunchedEffect(webAppsVisible) {
    if (!webAppsVisible && screen == CliScreen.WEBAPPS) screen = CliScreen.HOME
}
```

`when` (194-201): `CliScreen.WEBAPPS -> CliWebAppsScreen(viewModel = viewModel)` (экран из Task 7; в этом таске — временный `CliScreenHeader`-заглушечный composable в том же файле Task 7 создаётся сразу, см. порядок: Task 7 можно вести параллельно, но коммит Task 6 должен собираться — поэтому в Task 6 создать `CliWebAppsScreen.kt` с каркасом: header + пустое состояние).
`DisposableEffect` (146-157): по образцу APPS — хука видимости не добавлять (экран читает только warm-состояние Room/settings), оставить комментарий.
`CliHintBar(current = screen, onSelect = { screen = it }, screens = dockScreens)`.

- [ ] **Step 4:** Иконка: прогнать глиф через пиксель-пайплайн в `ex/` → `pix_webapps.png` в `drawable-nodpi` (как остальные 37).
- [ ] **Step 5:** Сборка + ручная проверка: 7 вкладок на узком экране (Realme) не разваливаются (`weight(1f)` делит поровну — визуально проверить тап-таргеты ≥48dp), появление/скрытие вкладки тумблером, активная вкладка при скрытии откатывается на home.
- [ ] **Step 6: Commit** — `feat(webapps): dock tab between map and stats`

---

### Task 7: Экран WEBAPPS — сетка, бейджи, добавление

**Files:**
- Create/Expand: `app/src/main/kotlin/com/foxhole/guard/ui/cli/webapps/CliWebAppsScreen.kt`
- Modify: strings (`cli_webapps_title`, `cli_webapps_add`, `cli_webapps_add_hint`, `cli_webapps_empty`, `cli_webapps_rename`, `cli_webapps_delete`, `cli_common_cancel` reuse)

**Interfaces:**
- Consumes: `WebAppsRepository` через новый support: `HomeViewModelWebAppsSupport.kt` дополнить (`val HomeViewModel.webApps: StateFlow<List<WebAppEntity>>`, `fun addWebApp(url)`, `renameWebApp(id, name)`, `removeWebApp(id)`, `openWebApp(id)` — см. Task 8/10), `WebAppsRepository.iconFile`.
- Produces: `CliWebAppsScreen(viewModel, modifier)`; `WebAppBadge` composable (переиспользует Task 11 логику числа: cap «99+»).

- [ ] **Step 1:** Сетка: `LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 56.dp))` с `contentPadding` `CliSpacing.md` — на типовых ширинах даёт 4-6 в строке, на узких 3 (требование «от 3 до 6»). Ячейка: иконка 40dp (`BitmapFactory.decodeFile` в `remember(path)` → `Image(bitmap.asImageBitmap(), filterQuality = FilterQuality.None)`; null → рамка с первой буквой имени в Silkscreen), под ней имя (1 строка, ellipsis, `colors.dim`), бейдж — оранжевый квадрат с числом в правом-верхнем углу иконки (CLI-канон: прямые углы, фон `colors.accent`, текст `colors.bg`).
- [ ] **Step 2:** Тап → `viewModel.openWebApp(id)` (Task 8). Long-press (`combinedClickable`) → инлайн-строка действий под сеткой в терминал-стиле: `> app: <name>` + `CliChip` `rename` / `delete` / `cancel`; rename разворачивает поле ввода (`BasicTextField` в CLI-обвязке, как поля в существующих суб-экранах) + `ok`.
- [ ] **Step 3:** Блок добавления ниже сетки: строка `add webapp`, поле URL, кнопка `fetch` → `viewModel.previewWebApp(url)` → превью (иконка+имя+редактируемое имя) → `save` вызывает `addWebApp`; ошибки — красная `CliElbowLine`. Пустое состояние: `cli_webapps_empty` + сразу блок добавления.
- [ ] **Step 4:** Сборка + ручная проверка: добавить 2-3 реальных сайта (web.telegram.org, mastodon-инстанс), иконки/имена подтянулись, rename/delete работают, поворот экрана не теряет состояние формы (`rememberSaveable` на url/имя).
- [ ] **Step 5: Commit** — `feat(webapps): webapps screen with grid, badges, add flow`

---

### Task 8: Полноэкранный фрейм WebView

**Files:**
- Create: `app/src/main/kotlin/com/foxhole/guard/ui/cli/webapps/CliWebAppFrame.kt`
- Modify: `app/src/main/kotlin/com/foxhole/guard/ui/cli/CliApp.kt` (обернуть Column в Box, фрейм — верхним слоем)
- Modify: `app/src/main/kotlin/com/foxhole/guard/ui/HomeViewModelWebAppsSupport.kt` (`openWebAppState: StateFlow<WebAppEntity?>`, `openWebApp(id)`, `closeWebApp()`)
- Modify: `gradle/libs.versions.toml` + `app/build.gradle.kts` (добавить `androidx.webkit:webkit` — последняя стабильная из каталога релизов)

**Interfaces:**
- Consumes: `WebAppsRepository.resetBadge(id)`; настройки-состояние для фрейма.
- Produces: `CliWebAppFrame(app: WebAppEntity, onClose: () -> Unit)`; открытие фрейма = `openWebAppState != null`, рендер ПОВЕРХ дока (Box на уровне CliApp — требование «на весь экран, вверху кнопка закрыть»).

- [ ] **Step 1:** В `CliApp` обернуть основной `Column` (строки 175-212) в `Box(Modifier.fillMaxSize())`; после Column:

```kotlin
val openWebApp by viewModel.openWebAppState.collectAsStateWithLifecycle()
openWebApp?.let { app ->
    CliWebAppFrame(app = app, onClose = viewModel::closeWebApp)
}
```

Состояние во VM (не в `rememberSaveable`): переживает поворот, живёт над lock-гейтом как остальной стейт CliApp — но при `lockState != UNLOCKED` фрейм НЕ компонуется (ранний return на 116-140 это уже гарантирует).

- [ ] **Step 2:** `CliWebAppFrame`: `Column(Modifier.fillMaxSize().background(colors.bg).statusBarsPadding().navigationBarsPadding())`; верхняя строка: `CliActionRow(label = app.name, value = "[x] close", onTap = onClose)` (канон закрывашек CLI); ниже `AndroidView({ WebView(it) })` c `Modifier.weight(1f)`:
  - `settings.javaScriptEnabled = true; domStorageEnabled = true; mixedContentMode = MIXED_CONTENT_NEVER_ALLOW; allowFileAccess = false; allowContentAccess = false; mediaPlaybackRequiresUserGesture = true`
  - `CookieManager.getInstance().setAcceptCookie(true)`; на dispose — `flush()` (куки общие с вотчдогом: один процесс, один default-профиль WebView).
  - `WebViewClient.shouldOverrideUrlLoading`: хост совпадает с registrable-доменом `app.url` (суффикс-матч по точке) → false (грузим); иначе → true + заметка в терминал «external link blocked: <host>» (доступ к `CliTerminalState` пробросить не выйдет — вместо этого `viewModel.emitSnackbar(...)`-путь, каким пользуются остальные фичи; точный API взять из существующего использования `viewModel.snackbars`).
  - `BackHandler { onClose() }` внутри фрейма (выигрывает у PredictiveBackHandler CliApp — глубже в композиции).
- [ ] **Step 3:** `openWebApp(id)`: `resetBadge(id)` + выставить state; `closeWebApp()`: `webView` уничтожается через dispose `AndroidView` (v1: инстанс на открытие, сессии живут в куках).
- [ ] **Step 4:** Ручная проверка: открытие/закрытие, логин в telegram web сохраняется после закрытия и повторного открытия, системный back закрывает фрейм (не приложение), док под фреймом не просвечивает, внешняя ссылка блокируется с заметкой.
- [ ] **Step 5: Commit** — `feat(webapps): fullscreen webview frame`

---

### Task 9: Вотчдог (пуш-сервис)

**Files:**
- Create: `app/src/main/kotlin/com/foxhole/guard/core/webapps/WebAppsWatchdog.kt`
- Create: `app/src/main/kotlin/com/foxhole/guard/core/webapps/WebAppsWatchdogWorker.kt`
- Create: `app/src/main/kotlin/com/foxhole/guard/core/webapps/WebAppShim.kt` (JS-константа + чистая `computeBadge`)
- Modify: `FoxholeAppGraphModules.kt`/`FoxholeAppGraph.kt` (синглтон), `FoxholeApplication.kt` (start + enqueue паттерном строк 253/336/…)
- Test: `app/src/test/kotlin/com/foxhole/guard/core/webapps/WebAppShimTest.kt`

**Interfaces:**
- Consumes: `RuntimeSessionTicker` (`runtime/RuntimeSessionTicker.kt:42`, internal — модуль тот же; СВОЙ экземпляр, конструктор повторить по образцу `FoxholeVpnService.kt:162`); `FoxholeVpnRuntimeBridge.snapshot` (гейт «guard поднят»); `WebAppsRepository`; `SettingsRepository.settings`; `WebAppsNotifier` (Task 10).
- Produces: `WebAppsWatchdog.start()` (вызвать из `FoxholeApplication`), `suspend fun pollOnce(trigger: String)`; `computeBadge(shimCount: Int?, title: String?, previous: Int): Int`.

- [ ] **Step 1: Красный тест `computeBadge`:** shim-значение приоритетно (`shimCount=4, title="(2) x"` → 4); нет шима — title-бейдж (`null,"(2) x"` → 2); ничего — previous сохраняется (`null,null,3` → 3); shim=0 сбрасывает (`0,"(5)x"` → 0).
- [ ] **Step 2:** Реализация `WebAppShim.kt`:

```kotlin
internal const val WEB_APP_SHIM_JS = """
(function() {
  if (window.__fhgShim) return; window.__fhgShim = true;
  var count = null;
  function post() {
    try { window.__fhgBridge.postMessage(JSON.stringify({ badge: count })); } catch (e) {}
  }
  try {
    var N = function(title, opts) { count = (count || 0) + 1; post(); };
    N.requestPermission = function() { return Promise.resolve('granted'); };
    N.permission = 'granted';
    Object.defineProperty(window, 'Notification', { value: N, configurable: false });
  } catch (e) {}
  try {
    navigator.setAppBadge = function(n) { count = (typeof n === 'number' ? n : 0); post(); return Promise.resolve(); };
    navigator.clearAppBadge = function() { count = 0; post(); return Promise.resolve(); };
  } catch (e) {}
  try {
    if (window.ServiceWorkerRegistration) {
      ServiceWorkerRegistration.prototype.showNotification = function() { count = (count || 0) + 1; post(); return Promise.resolve(); };
    }
  } catch (e) {}
})();
"""

internal fun computeBadge(shimCount: Int?, title: String?, previous: Int): Int {
    if (shimCount != null) return shimCount.coerceIn(0, 9999)
    val fromTitle = title?.let(::parseTitleBadge)
    return fromTitle?.coerceIn(0, 9999) ?: previous
}
```

- [ ] **Step 3:** `WebAppsWatchdog` (главный поток для WebView через `Handler(Looper.getMainLooper())`/`withContext(Dispatchers.Main)`):
  - один скрытый переиспользуемый `WebView` (те же security-настройки, что во фрейме Task 8), создаётся лениво при первом опросе, `destroy()` при остановке сервиса;
  - шим: если `WebViewFeature.isFeatureSupported(DOCUMENT_START_SCRIPT)` → `WebViewCompat.addDocumentStartJavaScript(webView, WEB_APP_SHIM_JS, setOf("*"))` один раз; иначе `evaluateJavascript(WEB_APP_SHIM_JS, null)` в `onPageStarted`; мост: `WEB_MESSAGE_LISTENER` → `addWebMessageListener(webView, "__fhgBridge", setOf("*")) { _, message, _, _, _ -> … }`, парс `{"badge":N}`;
  - `pollOnce`: сайты строго по очереди; на сайт: `loadUrl`, ждать `onPageFinished` + грейс 5с на пост-JS, общий таймаут 20с (`withTimeoutOrNull`); собрать `shimCount` (последнее сообщение моста) и `webView.title`; `newCount = computeBadge(...)`; если `newCount > previous` → `notifier.notify(app, newCount)`; всегда `repository.setBadge(id, newCount, now)`; между сайтами `loadUrl("about:blank")`;
  - гейтинг: `combine(settings, runtimeBridge.snapshot)` → активен при `webApps.enabled && webApps.pushServiceEnabled && <туннель/guard активен>`; при активации — `ticker.register("webapps-watchdog", fireImmediately = true, intervalMs = minutes * 60_000L) { pollOnce("ticker") }` (сигнатуру повторить по существующим регистрациям в `FoxholeVpnServiceTrafficSupport.kt`); при деактивации — `unregister` + `destroy` WebView; смена интервала — переregister.
- [ ] **Step 4:** `WebAppsWatchdogWorker`: periodic 15 мин (минимум WorkManager), `WORK_NAME = "webapps-watchdog"`, паттерн enqueue/cancel — как `applyGuardReconcileSchedule` (армится при пуш-вкл, снимается при выкл; из воркера — `graph.webAppsWatchdog.pollOnce("worker")`, no-op если гейт закрыт). Это страховка после смерти процесса, основной ритм — тикер.
- [ ] **Step 5:** Тесты PASS, сборка; ручная проверка (Pixel): включить пуш, свернуть апп, дождаться тика — бейдж и системное уведомление пришли.
- [ ] **Step 6: Commit** — `feat(webapps): notification watchdog with webview shim`

---

### Task 10: Уведомления + deep-link в фрейм

**Files:**
- Create: `app/src/main/kotlin/com/foxhole/guard/core/webapps/WebAppsNotifier.kt`
- Modify: `app/src/main/kotlin/com/foxhole/guard/ui/cli/CliMainActivity.kt` (66-67 onCreate, 110-117 onNewIntent)
- Modify: `HomeViewModelWebAppsSupport.kt` (`applyWebAppOpenIntent(intent)`)
- Modify: strings (`cli_webapps_channel_name`, notification title/body шаблоны)

**Interfaces:**
- Consumes: паттерн `AnomalyNotifier.kt` (канал 143-154, POST_NOTIFICATIONS-гейт 27-32); `openWebApp(id)` из Task 8.
- Produces: `WebAppsNotifier.notify(app: WebAppEntity, count: Int)`; канал `foxhole_webapps`; id = `8600 + (app.id % 200)`; `CliMainActivity.EXTRA_OPEN_WEB_APP_ID = "open_web_app_id"`.

- [ ] **Step 1:** `WebAppsNotifier` по образцу `AnomalyNotifier`, отличия: `CHANNEL_ID = "foxhole_webapps"`, `IMPORTANCE_DEFAULT`, contentIntent:

```kotlin
val intent = Intent(context, CliMainActivity::class.java)
    .putExtra(CliMainActivity.EXTRA_OPEN_WEB_APP_ID, app.id)
    .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
val pending = PendingIntent.getActivity(
    context, (8600 + (app.id % 200)).toInt(), intent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)
```

title = имя аппа, text = «notifications: N» (строкой из ресурсов), `setAutoCancel(true)`, иконка — переиспользовать существующую нотификационную (`ic_notification_anomaly`-сосед; если делается своя — тем же способом).

- [ ] **Step 2:** `applyWebAppOpenIntent(intent)`: прочитать extra (`-1L` → игнор), `openWebApp(id)` (он же ставит `screen`-независимое состояние фрейма — фрейм рендерится поверх любой вкладки, отдельного перевода дока не нужно; бейдж сбросится в `openWebApp`). Вызвать в `onCreate` рядом с `applyBenchmarkIntent(intent)` и в `onNewIntent` под тем же `lockState == UNLOCKED`-гейтом (110-117). Cold start под локом: состояние VM переживёт гейт — фрейм откроется после разблокировки (стейт выше lock-гейта, `CliApp.kt:77-78`).
- [ ] **Step 3:** Ручная проверка: тап по уведомлению из шторки — холодный старт и тёплый; из-под лока — фрейм после разблокировки.
- [ ] **Step 4: Commit** — `feat(webapps): notifications + open-frame deep link`

---

### Task 11: Виджет web apps (Glance)

**Files:**
- Modify: `gradle/libs.versions.toml` (`glance = "1.1.1"`, alias `androidx-glance-appwidget`), `app/build.gradle.kts` (implementation)
- Create: `app/src/main/kotlin/com/foxhole/guard/widget/WebAppsWidget.kt` (+ `WebAppsWidgetReceiver`)
- Create: `app/src/main/res/xml/widget_webapps_info.xml`
- Modify: `app/src/main/AndroidManifest.xml` (receiver + APPWIDGET_UPDATE + configure)
- Test: `app/src/test/kotlin/com/foxhole/guard/widget/WebAppsWidgetLayoutTest.kt`

**Interfaces:**
- Consumes: `WebAppDao.listAll()`, `WebAppsRepository.iconFile`, `WidgetPrefs` (Task 13), `EXTRA_OPEN_WEB_APP_ID` (Task 10).
- Produces: `fun webAppSlots(size: DpSize): Int` (= 4 при ширине < 200.dp, иначе 8); `WebAppsWidget : GlanceAppWidget` с `SizeMode.Responsive(setOf(DpSize(110.dp, 110.dp), DpSize(250.dp, 110.dp)))`.

- [ ] **Step 1: Красный тест** `webAppSlots(DpSize(110.dp,110.dp)) == 4`, `webAppSlots(DpSize(250.dp,110.dp)) == 8` → реализация → PASS.
- [ ] **Step 2:** `WebAppsWidget.provideGlance`: прочитать prefs фона (Task 13: `black/white` + alpha 0–100 → `ColorProvider(Color.Black.copy(alpha = a/100f))`), `listAll()` первые N по `sortOrder`; layout: `Column` — заголовок-лого «web apps» (текст в стиле виджета, Glance кастомные шрифты не умеет — текст + при желании маленький bitmap-логотип из drawable), затем 1 ряд × 4 (квадрат) или 2 ряда × 4 (прямоугольник); ячейка: `Image(ImageProvider(bitmap))` 40dp + бейдж (`Box` с угловым текстом на accent-фоне, «99+» cap) + имя 1 строкой; `clickable(actionStartActivity<CliMainActivity>(actionParametersOf(openAppKey to app.id)))` — параметр мапится на `EXTRA_OPEN_WEB_APP_ID`.
- [ ] **Step 3:** Receiver + `widget_webapps_info.xml`: `resizeMode="horizontal|vertical"`, `minWidth/minHeight` под 2×2, `targetCellWidth=2 targetCellHeight=2` (+`maxResizeWidth` под 4×2), `configure` = `WidgetConfigActivity` (Task 13; в этом таске виджет обязан работать и БЕЗ конфига — дефолт чёрный/alpha 100, чтобы коммит был самодостаточен), `updatePeriodMillis="1800000"`.
- [ ] **Step 4:** Пуш обновлений: в `WebAppsRepository` после каждой записи бейджа/CRUD — `scope.launch { WebAppsWidget().updateAll(context) }` (хук `onBadgesChanged` из Task 5).
- [ ] **Step 5:** Ручная проверка на лаунчере (Pixel): 2×2 → лого + 4 иконки с бейджами, растяжение до 4×2 → 8 в два ряда, тап открывает фрейм.
- [ ] **Step 6: Commit** — `feat(webapps): glance widget`

---

### Task 12: Виджет статуса подключения + быстрые команды

**Files:**
- Create: `app/src/main/kotlin/com/foxhole/guard/widget/StatusWidget.kt` (+ receiver, + `StatusWidgetCommandReceiver`)
- Create: `app/src/main/res/xml/widget_status_info.xml`
- Modify: `AndroidManifest.xml`, `FoxholeApplication.kt` (коллектор состояния → `updateAll`)

**Interfaces:**
- Consumes: `FoxholeVpnRuntimeBridge.snapshot` (то же, что тайл — `FoxholeTileService.kt`); `FoxholeConnectionServiceContract.startForegroundService(context, mode, action)` + `ACTION_RESTORE` (start) / `ACTION_DISCONNECT` (stop) / `ACTION_RELOAD` (restart) (`FoxholeConnectionServiceContract.kt:40-55`, механика вызова — скопировать `handleTileClick`, `FoxholeTileService.kt:56-107`).
- Produces: виджет 2×1..4×1: строка состояния (`vpn: connected` / `tor: bootstrapping…` / `guard: firewall` / `idle` — тексты из снапшота бриджа, лоуеркейс) + три кнопки `[start] [stop] [restart]`.

- [ ] **Step 1:** Кнопки — `actionSendBroadcast` на внутренний `StatusWidgetCommandReceiver` (`exported=false`), который вызывает контрактный `startForegroundService` (виджет-PendingIntent даёт FGS-exemption; путь ровно как в тайле, включая оптимистичный апдейт бриджа). Никакой своей логики подключения — только те же три экшена.
- [ ] **Step 2:** Состояние: `provideGlance` читает текущий `snapshot`; live-обновление — коллектор в `FoxholeApplication` (`snapshot.distinctUntilChanged { статусные поля }` → `StatusWidget().updateAll`), плюс `updatePeriodMillis="1800000"` как фолбэк.
- [ ] **Step 3:** Фон/прозрачность — те же prefs (Task 13), дефолт чёрный/100.
- [ ] **Step 4:** Ручная проверка: старт из виджета при выключенном VPN (первый раз — VpnService.prepare диалог: старт должен открыть активити запроса, как делает тайл; проверить), стоп, рестарт при активном туннеле; статус меняется живьём.
- [ ] **Step 5: Commit** — `feat(webapps): connection status widget with quick actions`

---

### Task 13: Конфигурация виджетов (чёрный/белый + прозрачность)

**Files:**
- Create: `app/src/main/kotlin/com/foxhole/guard/widget/WidgetPrefs.kt`
- Create: `app/src/main/kotlin/com/foxhole/guard/widget/WidgetConfigActivity.kt`
- Modify: `AndroidManifest.xml` (activity + `APPWIDGET_CONFIGURE` в обоих provider-info), `widget_webapps_info.xml`, `widget_status_info.xml` (`android:configure`)
- Modify: strings (`cli_widget_bg_black`, `cli_widget_bg_white`, `cli_widget_alpha`)

**Interfaces:**
- Produces:

```kotlin
object WidgetPrefs {
    // SharedPreferences "widget_prefs": "bg_<appWidgetId>" -> "black"|"white", "alpha_<appWidgetId>" -> 0..100
    fun background(context: Context, widgetId: Int): WidgetBackground // (isBlack: Boolean, alphaPercent: Int)
    fun save(context: Context, widgetId: Int, isBlack: Boolean, alphaPercent: Int)
    fun clear(context: Context, widgetId: Int)  // из onDeleted ресиверов
}
```

- [ ] **Step 1:** `WidgetConfigActivity` (ComponentActivity, CLI-тема): `EXTRA_APPWIDGET_ID` из intent (нет → `finish`), UI: два `CliChip` black/white + строка прозрачности 0–100 шагом 10 (CLI-степпер `«- 40% +»` — слайдеров в каноне нет), кнопка `apply` → `WidgetPrefs.save` → `updateAll` соответствующего виджета → `setResult(RESULT_OK, …)` → finish. `setResult(RESULT_CANCELED)` в `onCreate` сразу (отмена конфигурации до apply = виджет не добавлен — стандартный контракт).
- [ ] **Step 2:** Оба виджета читают `WidgetPrefs.background` в `provideGlance` (фон: черный/белый с alpha; цвет текста/акцентов — инверсия под фон: на белом — чёрный текст, accent остаётся оранжевым).
- [ ] **Step 3:** `onDeleted` в обоих ресиверах → `WidgetPrefs.clear`.
- [ ] **Step 4:** Ручная проверка: добавление виджета открывает конфиг, отмена не добавляет виджет, белый/чёрный/alpha применяются, переконфигурация (Android 12+ reconfigure) работает.
- [ ] **Step 5: Commit** — `feat(webapps): widget configuration (bg + transparency)`

---

### Task 14: Полировка, справка, приёмка

**Files:**
- Modify: справка `CliHelpSubScreen`-контент (честное ограничение пушей: «уведомления проверяются по расписанию, мгновенной доставки у веб-аппов нет»), strings
- Modify: мелочи по итогам прогона

- [ ] **Step 1:** Пустые/ошибочные состояния: нет сети при добавлении (красная строка), нет иконки (буква-заглушка), вотчдог при выключенном guard (в суб-экране настроек — dim-строка `push idle: guard down`).
- [ ] **Step 2:** Полный прогон юнитов: `./gradlew :app:testDebugUnitTest` → PASS; миграционный чейн-тест → PASS; сборка `./gradlew :app:assembleDebug` → OK.
- [ ] **Step 3:** Ручная приёмка (Realme = UI, Pixel = бек): чек-лист — тумблеры/консент; 7 вкладок; добавление 3 сайтов; фрейм + логин-персист; вотчдог тик на 1 мин с реальным «(N)»-сайтом; уведомление → фрейм; оба виджета 2×2/4×2 + конфиг; выключение фаервола гасит пуш; выключение модуля прячет вкладку и виджет показывает пустое состояние.
- [ ] **Step 4: Commit** — `feat(webapps): polish + help notes` (+ при необходимости fix-коммиты по приёмке).

---

## Риски и явные ограничения

- Web Push в WebView невозможен — вотчдог видит изменения только в момент опроса; интервал 1 мин доступен лишь при живом guard-процессе (тикер), WorkManager-страховка не чаще 15 мин.
- Фоновый WebView на тяжёлых сайтах: лечится одним инстансом, строгой очередью, 20с таймаутом и `about:blank` между сайтами; анти-бот отдельных сайтов может не пустить headless-опрос (сайт просто даст 0 сигналов — бейдж не изменится).
- Room-миграция: DDL обязан совпасть с exported schema 10 бит-в-бит (проверяется чейн-тестом).
- Док на 7 вкладок на узких экранах: `weight(1f)`-ячейки сжимаются — визуальная проверка обязательна (Realme).
- `:core:model` — чистый JVM: в `WebAppsModels.kt` никаких Android-импортов.
- `Object.defineProperty(window,'Notification')` может не сработать на отдельных движко-версиях — шим обёрнут в try/catch, фолбэк — title-эвристика.

## Self-Review (прогнан)

- Покрытие спеки: тумблер модуля ✓ (T3), провал в настройки ✓ (T3), пуш-сервис + фаервол ✓ (T1/T2/T3), экран+значок в доке между stats и map ✓ (T6), 3–6 в строку ✓ (T7), фрейм на весь экран с close сверху ✓ (T8), добавление сайтов ✓ (T5/T7), вотчдог ✓ (T9), бейджи у значков ✓ (T7/T9), иконка+имя ✓ (T5), виджет лого+4/8 с бейджами ✓ (T11), виджет состояния + start/stop/restart ✓ (T12), конфиг чёрный/белый + прозрачность 0–100 ✓ (T13).
- Типы согласованы: `WebAppsSettings` поля (T1↔T2↔T3↔T6↔T9), `WebAppEntity` поля (T4↔T5↔T7↔T9↔T11), API `WebAppsRepository` (T5↔T7↔T8↔T9↔T10↔T11), `EXTRA_OPEN_WEB_APP_ID` (T10↔T11), `computeBadge`/`parseTitleBadge` (T5↔T9), `WidgetPrefs` (T11↔T12↔T13).
- Известная мягкость (намеренная): точные сигнатуры `RuntimeSessionTicker.register` и снапшота `FoxholeVpnRuntimeBridge` исполнитель сверяет по указанным файлам-образцам (`FoxholeVpnServiceTrafficSupport.kt`, `FoxholeTileService.kt:56-107`) — файлы и строки даны.
