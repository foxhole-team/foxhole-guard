package com.foxhole.guard.core.settings

import com.foxhole.core.model.ConnectionSettings
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Где связки «фаервол → автозапуск» НЕТ, и почему её там нет намеренно.
 *
 * Владелец решил: включение фаервола включает и тумблер автозапуска. Сделано это в обработчике
 * переключателя (HomeViewModelSecuritySettingsSupport.onFirewallEnabledChangedInternal) — то есть
 * в момент, когда пользователь его нажал, — а НЕ в нормализации, и этот тест закрепляет разницу.
 *
 * Разница существенная. Нормализация выполняется на каждой загрузке настроек: связка там означала
 * бы, что состояние «фаервол включён, автозапуск выключен» недостижимо в принципе — пользователь
 * выключает автозапуск, а следующая загрузка молча включает его обратно. Настройка, которую нельзя
 * выключить, хуже отсутствующей: она врёт. Поэтому связка одноразовая, при нажатии, и после неё
 * автозапуск остаётся обычным тумблером.
 *
 * Обратной связки нет тоже намеренно: выключение фаервола не трогает автозапуск, потому что к тому
 * моменту он может нести восстановление VPN-профиля — обещание, которого фаервол не давал и
 * отзывать не вправе.
 *
 * Отдельно: автоподъём фаервола после перезагрузки работал и до связки, через ветку плана загрузки
 * (BootReceiverTest: «boot restore starts local guard when auto start is disabled»). Именно поэтому
 * тумблер и показывал «выкл» при включённом по факту поведении — связка это расхождение убирает.
 */
internal class FirewallAutoStartCouplingTest {
    @Test
    fun `normalization does not couple the firewall to auto start, so auto start stays switchable off`() {
        val normalized =
            Settings(
                connection = ConnectionSettings(autoStartOnBoot = false),
                expert = ExpertSettings(firewallEnabled = true),
            ).normalized()

        assertTrue(normalized.expert.firewallEnabled)
        assertFalse(normalized.connection.autoStartOnBoot)
    }

    @Test
    fun `disabling the firewall never revokes auto start`() {
        val normalized =
            Settings(
                connection = ConnectionSettings(autoStartOnBoot = true),
                expert = ExpertSettings(firewallEnabled = false),
            ).normalized()

        assertFalse(normalized.expert.firewallEnabled)
        assertTrue(normalized.connection.autoStartOnBoot)
    }

    @Test
    fun `auto start does not arm the firewall either`() {
        val normalized =
            Settings(
                connection = ConnectionSettings(autoStartOnBoot = true),
                expert = ExpertSettings(firewallEnabled = false),
            ).normalized()

        assertFalse(normalized.expert.firewallEnabled)
    }
}
