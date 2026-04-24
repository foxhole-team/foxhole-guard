<p align="center">
  <img src="fastlane/metadata/android/images/icon.png" alt="Foxhole" width="120" height="120">
</p>

<p align="center">
  <a href="README.md">English</a> |
  <strong>Русский</strong>
</p>

<p align="center">
  <a href="https://f-droid.org/">
    <img src="media/fdroid.png" alt="F-Droid" height="68">
  </a>
</p>

<p align="center">
  <strong>1.0.0-beta1</strong>
</p>

<p align="center">
  Версии для iOS, macOS и Windows - maybe.
</p>

Foxhole это простой Android-клиент для подключения и управления профилями sing-box. Поддерживает Tunnel и Proxy, а также Split Tunnel по приложениям и сайтам и LAN Proxy. Поддерживает импорт профилей из файла, буфера обмена, QR-кода и HTTPS-подписок, включая совместимые подписки по типу v2raytun. Без рекламы, аналитики и телеметрии. Поддерживает совместимые конфигурации и кастом смарт конфиг для быстрого Smart start с автовыбором протоколов VPN.

<table style="border: none; border-collapse: collapse;">
  <tr>
    <td align="center" style="border: none;">
      <img src="fastlane/metadata/android/ru-RU/images/phoneScreenshots/img_foxhole_0.png" alt="Foxhole dashboard" width="260">
    </td>
    <td align="center" style="border: none;">
      <img src="fastlane/metadata/android/ru-RU/images/phoneScreenshots/img_foxhole_1.png" alt="Foxhole settings" width="260">
    </td>
    <td align="center" style="border: none;">
      <img src="fastlane/metadata/android/ru-RU/images/phoneScreenshots/img_foxhole_2.png" alt="Foxhole settings" width="260">
    </td>
  </tr>
</table>

Ключевые функции:

- Режимы: Tunnel, Proxy
- Split Tunnel по приложениям и сайтам
- LAN Proxy по Wi‑Fi для доступа с других устройств
- Авторизация прокси
- Правила маршрутизации по сайтам
- Импорт из файла, буфера обмена, QR-кода и HTTPS-подписок
- Multi-protocol профили с выбором протокола и Smart start
- Локальная статистика трафика
- Отображение даты окончания подписки
- Зашифрованное локальное хранение профилей
- Без рекламы
- Без аналитики
- Без телеметрии

Поддерживаемые протоколы:

- VLESS
- Trojan
- Shadowsocks
- VMess
- Hysteria2
- WireGuard
- Outline

<hr style="border: none; height: 1px; background: #ccc;">

<table style="border: none; border-collapse: collapse;">
  <tr>
    <td style="border: none;">
      <img src="media/have_the_courage_use_your_own_reason-fuck_you-1984.png" alt="1984" width="80" height="80">
    </td>
    <td style="border: none;">
      <h2>Foxhole smart config</h2>
    </td>
  </tr>
</table>

Одна подписка может содержать один или больше профилей. Foxhole превращает каждую такую route group в отдельный профиль, а все записи внутри группы становятся протоколами VPN этого профиля.

#### Foxhole Smart start:

- Проверяет поддерживаемые протоколы VPN.
- Учитывает удачный результат по хешу текущей сети.
- Анализирует:
  - success/failure history
  - last known good
  - network-scoped memory
  - metered/roaming/private DNS/upstream validation
  - remembered latency
  - connect duration
  - validation/traffic evidence
  - cooldown

Пример формата лежит в [foxhole-smart-config.sample.txt](foxhole-sample-smart-config/foxhole-smart-config.sample.txt).

<hr style="border: none; height: 1px; background: #ccc;">

## Проверка подписи релиза

- Скачайте APK и `SHA256SUMS` из одного GitHub Release, затем выполните `sha256sum -c SHA256SUMS`.
- Signing cert SHA-256: `fcbf14862040fbe26726f06f3016ec3b027d8431c76cb4c25c13c5a06837177c`
- Signing cert SHA-1: `ae609bb369398071cc5ac53c9ac10f20f43c4d01`

<hr style="border: none; height: 1px; background: #ccc;">

## Дисклеймер

> Разработка Android-приложений не является нашим профильным направлением. Наш ключевой опыт сосредоточен в backend-разработке, безопасности, машинном обучении, криптографии и других инженерных областях.

<hr style="border: none; height: 1px; background: #ccc;">

## Donate

- **XMR (Monero):** `48yBVPTdcyJ1WoJtnKmVpEZziEsDy4HvbCW7eQDS9mfdiWPFXwZ8F5h9YZ2UTTBLxPcJgQgvth7iqLZM2yMCaQ432qaouqr`
- **BTC (Bitcoin):** `bc1qatnyy7jcpqrp0d3dk9rta9vqfejgh4mysd6m2f`
