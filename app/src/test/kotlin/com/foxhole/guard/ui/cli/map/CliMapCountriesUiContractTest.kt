package com.foxhole.guard.ui.cli.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliMapCountriesUiContractTest {
    @Test
    fun `country count footer is last and starts at the left edge`() {
        val map = source("main/kotlin/com/foxhole/guard/ui/cli/map/CliMapScreen.kt")
        val panel = map
            .substringAfter("private fun CliMapCountriesPanel(")
            .substringBefore("selectedCountryCode?.let")
        val row = map
            .substringAfter("private fun CliMapCountryTableRow(")
            .substringBefore("private fun CliMapCountryCountFooter(")

        assertTrue(panel.indexOf("val topCountries") < panel.indexOf("snapshot.hiddenCountryCount"))
        assertTrue(panel.indexOf("snapshot.hiddenCountryCount") < panel.indexOf("CliMapCountryCountFooter("))
        assertTrue(panel.contains("countryCount = snapshot.countryCount"))
        assertFalse(panel.contains("bytes = snapshot.totalBytes"))
        assertTrue(panel.contains("topCountries.forEachIndexed { index, point ->"))
        assertTrue(panel.contains("if (index > 0)"))
        assertFalse(panel.contains("topCountries.forEach { point ->"))
        assertFalse(row.contains("COUNTRY_FLAG_SLOT_WIDTH"))
    }

    @Test
    fun `country count label is natural and capitalized in both locales`() {
        val english = source("main/res/values/strings.xml")
        val russian = source("main/res/values-ru/strings.xml")

        assertTrue(english.contains(">Total number of countries</string>"))
        assertTrue(russian.contains(">Общее количество стран</string>"))
        assertTrue(english.contains("<string name=\"cli_map_column_connections\">connections</string>"))
        assertTrue(russian.contains("<string name=\"cli_map_column_connections\">подключений</string>"))
    }

    @Test
    fun `country detail table lifts only its section header icon`() {
        val sheet = source("main/kotlin/com/foxhole/guard/ui/cli/map/CliMapCountrySheet.kt")
        val section = sheet
            .substringAfter("private fun CliMapCountrySection(")
            .substringBefore("private fun CliMapCountryAppRow(")
        val titleCell = section
            .substringAfter("text = title")
            .substringBefore("R.string.cli_map_column_traffic")

        assertTrue(section.contains("modifier = Modifier.offset(y = CLI_HEADER_ICON_LIFT)"))
        assertFalse(titleCell.contains("CLI_HEADER_ICON_LIFT"))
    }

    @Test
    fun `empty country details keep the journal hint in one info block`() {
        val sheet = source("main/kotlin/com/foxhole/guard/ui/cli/map/CliMapCountrySheet.kt")
        val emptyDetails = sheet
            .substringAfter("if (appRows.isEmpty() && hostRows.isEmpty()) {")
            .substringBefore("CliDashedInfoNote(text = infoText)")

        assertTrue(emptyDetails.contains("R.string.cli_map_country_sheet_empty"))
        assertTrue(emptyDetails.contains("R.string.cli_map_country_sheet_journal_hint"))
        assertTrue(sheet.contains("CliDashedInfoNote(text = infoText)"))
        assertFalse(emptyDetails.contains("CliElbowLine"))
        assertFalse(sheet.contains("import com.foxhole.guard.ui.cli.components.CliElbowLine"))
    }

    private fun source(relative: String): String =
        listOf(File("src/$relative"), File("app/src/$relative"), File("../app/src/$relative"))
            .first(File::isFile)
            .readText()
}
