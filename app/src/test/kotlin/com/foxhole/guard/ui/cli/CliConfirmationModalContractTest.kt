package com.foxhole.guard.ui.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт «одна форма подтверждения»: любой да/нет во фронте поднимается нижней модалкой
 * [CliConfirmSheet], а не разворачивается инлайном под строкой, которая спросила.
 *
 * Тест исходников, а не композиции: проверяется именно то, что легко отрастает обратно при
 * копипасте соседнего экрана — пара чипов «y/n» прямо в теле панели.
 */
class CliConfirmationModalContractTest {

    @Test
    fun `the inline yes-no row no longer exists`() {
        assertFalse(
            "CliYesNoRow вернулся — инлайн-подтверждения снова возможны",
            cli("components/CliRows.kt").contains("fun CliYesNoRow"),
        )
    }

    @Test
    fun `the confirm sheet rides the shared bottom sheet with cancel first`() {
        val source = cli("components/CliConfirmSheet.kt")

        assertTrue(source.contains("CliBottomSheet("))
        assertTrue(source.contains("CliSheetActionsRow("))
        // Цветовой код: отмена в err-тоне, подтверждение залитое в ok-тоне.
        assertTrue(source.contains("color = colors.err"))
        assertTrue(source.contains("filled = true"))
        // Порядок в ряду с одним действием: сначала отмена, потом «да».
        val singleAction = source.substringAfter("actions.size == 1 ->").substringBefore("else ->")
        assertTrue(singleAction.indexOf("cancelButton(") < singleAction.indexOf("actionButton("))
    }

    @Test
    fun `the destructive row asks in the modal and keeps its row in place`() {
        val row = cli("components/CliRows.kt").substringAfter("internal fun CliDestructiveRow")

        assertTrue(row.contains("CliActionRow(label = label, icon = icon, onTap = { onArm(key) })"))
        assertTrue(row.contains("if (confirmAction == key)"))
        assertTrue(row.contains("CliConfirmSheet("))
        assertTrue(row.contains("onArm(null)"))
        assertTrue(row.contains("onConfirm()"))
    }

    @Test
    fun `the firewall consent is a modal, not an inline chip pair`() {
        // Фаервол переехал из группы «безопасность» в раздел модулей вместе с обеими своими
        // модалками — включение спрашивает, выключение называет, что вместе с ним встанет.
        val source = cli("settings/CliModulesSection.kt")

        assertTrue(source.contains("CliConfirmSheet("))
        assertTrue(source.contains("R.string.cli_cfg_firewall_consent_body"))
        assertTrue(source.contains("R.string.cli_cfg_firewall_disable_body"))
        assertFalse(source.contains("CliChip("))
    }

    @Test
    fun `module activation sheets outlive collapsible panel content`() {
        val source = cli("settings/CliModulesSection.kt")
        val section = source.substringAfter("internal fun CliModulesSection")
            .substringBefore("private fun CliTorModuleBlock")

        assertTrue(section.contains("var torActivation by remember"))
        assertTrue(section.contains("var sentinelActivation by remember"))
        assertTrue(section.indexOf("torActivation?.let") > section.indexOf("CliPanel("))
        assertTrue(section.indexOf("sentinelActivation?.let") > section.indexOf("CliPanel("))
    }

    @Test
    fun `the encryption consent is a modal on both directions`() {
        val panel = cli("settings/CliPinPanel.kt")
        val section = cli("settings/CliLockSection.kt")

        // Сама модалка: включение несёт спутников, выключение — предупреждение.
        assertTrue(panel.contains("internal fun CliEncryptionConsentSheet"))
        assertTrue(panel.contains("R.string.cli_lock_consent_body"))
        assertTrue(panel.contains("R.string.cli_lock_disable_warning"))
        // Обе стороны идут через consentFlow, смена пароля — мимо: это не вкл/выкл шифрования.
        assertTrue(section.contains("consentFlow = CliPinFlow.ENABLE"))
        assertTrue(section.contains("consentFlow = CliPinFlow.DISABLE"))
        assertTrue(section.contains("pinFlow = CliPinFlow.CHANGE"))
    }

    @Test
    fun `every remaining inline confirmation moved to the modal`() {
        listOf(
            "profiles/CliProfileListItem.kt" to "R.string.cli_prof_delete_confirm",
            "profiles/CliProfileEditorProtocolForm.kt" to "R.string.cli_data_destructive_confirm",
            "settings/CliWebAppsSubScreen.kt" to "R.string.cli_webapps_firewall_consent",
            "settings/CliDataSubScreen.kt" to "R.string.cli_data_restore_confirm",
        ).forEach { (relative, question) ->
            val source = cli(relative)
            assertTrue("$relative потерял вопрос $question", source.contains(question))
            assertTrue("$relative спрашивает не модалкой", source.contains("CliConfirmSheet("))
        }
    }

    @Test
    fun `no control row is start-aligned`() {
        // Два законных исхода для ряда кнопок: инлайновый — к правому краю (Alignment/Arrangement
        // .End), модальный — общий CliSheetActionsRow во всю ширину. Прижатых влево не осталось.
        listOf(
            "home/CliTorPromptPanel.kt",
            "profiles/CliProfileDialogs.kt",
            "profiles/CliProfileEditorProtocolForm.kt",
            "profiles/CliProfileListItem.kt",
            "settings/CliPinPanel.kt",
            "webapps/CliWebAppsScreen.kt",
        ).forEach { relative ->
            val source = cli(relative)
            val aligned =
                source.contains("Alignment.End") ||
                    source.contains("Arrangement.End") ||
                    // Центрированный ряд глифов профиля — решение, а не лево-прижатый регресс.
                    source.contains("Alignment.CenterHorizontally") ||
                    source.contains("CliSheetActionsRow(") ||
                    // Импорт профиля теперь использует вертикальные канонические кнопки на всю
                    // ширину: это не ряд и потому не имеет горизонтального выравнивания.
                    source.contains("modifier = Modifier.fillMaxWidth()")
            assertTrue("$relative: остался ряд контролов, прижатый к левому краю", aligned)
        }
    }

    @Test
    fun `the log tabs deliberately keep reading order`() {
        // Не регрессия, а решение: вкладки журнала — навигация, они читаются слева направо.
        val source = cli("logs/CliLogsScreen.kt")
        val tabs = source.substringAfter("CliLogTab.entries.forEach")

        assertFalse(tabs.take(TAB_ROW_WINDOW).contains("Alignment.End"))
    }

    /**
     * Согласие на TOR больше не живёт на главном экране, и это не потеря модалки, а снятие
     * недостижимого пути: кнопка режимов существует только при включённом модуле, поэтому спросить
     * разрешение с этого экрана было нечем. Разрешение выдаётся там же, где включается модуль.
     */
    @Test
    fun `the home screen no longer carries a tor consent`() {
        val facts = cli("home/CliHomeFacts.kt")
        val screen = cli("home/CliHomeScreen.kt")

        assertFalse(facts.contains("CliTorConsentPanel"))
        assertFalse(screen.contains("CliTorConsentPanel"))
        assertFalse(screen.contains("torConsentOpen"))
    }

    @Test
    fun `the confirm sheet is the only bottom confirmation shape`() {
        // Ровно один компонент рисует пару «отмена/подтвердить» — иначе форма расползётся.
        val components = File(cliRoot(), "components").listFiles().orEmpty()
        val declaring =
            components.filter { file ->
                file.isFile && file.readText().contains("confirmLabel ?: stringResource")
            }

        assertEquals(1, declaring.size)
    }

    private fun cli(relative: String): String = File(cliRoot(), relative).readText()

    private fun cliRoot(): File =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/ui/cli"),
            File("app/src/main/kotlin/com/foxhole/guard/ui/cli"),
            File("../app/src/main/kotlin/com/foxhole/guard/ui/cli"),
        ).first(File::isDirectory)
}

private const val TAB_ROW_WINDOW = 400
