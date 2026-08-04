# Матрица компонентов: что с чем сочетается и кто это держит

Составлена 2026-08-04. Смысл документа не в том, чтобы перечислить компоненты, а в
том, чтобы у **каждой межкомпонентной клаузулы** был назван тест, который её держит.
Клаузула без теста — это соглашение, которое живёт до первого рефактора.

## Компоненты, которые включаются независимо

| # | компонент | ключ настройки | работает без VPN-профиля? |
|---|---|---|---|
| 1 | VPN-туннель | `lastActiveProfile`, `traffic.*` | — (он и есть профиль) |
| 2 | Tor | `privacyRoute.{permitted,mode,bypassVpnTunnel,scope}` | **да** — `TOR_ONLY_PROFILE_ID` |
| 3 | I2P | `i2p.enabled` + `i2p.engaged` | **да** — поднимает прозрачный guard |
| 4 | Локальный фаервол / guard | `expert.firewallEnabled` | **да**, это его назначение |
| 5 | DNS-фильтрация | `dns.filteringEnabled` + списки | **да**, через guard |
| 6 | Перехват DNS / замена системного | `dns.{interceptDnsRequests,replaceSystemDns}` | **да**, DNS-guard для этого и есть |
| 7 | Сплит per-app | `expert.perAppRoutingMode` + `appAssignments` | да |
| 8 | LAN-прокси | `expert.localSurfaces.allowLanAccess` | **функции нет** — см. ниже |
| 9 | Раздача файлов / onion | без флага, только живой рантайм | **нет**, требует Tor |
| 10 | Sentinel | `statistics.*`, `anomaly.*`, `expert.newAppQuarantineEnabled` | да, по бродкастам |
| 11 | Журнал guard | `appLock.{eventMonitoringEnabled,guardHosting}` | да |
| 12 | Веб-аппы | `webApps.{enabled,pushServiceEnabled,dockScreenEnabled}` | да |
| 13 | Блокировка приложения | `appLock.mode` | да, чисто локальный |
| 14 | Always-on / lockdown | **не наша настройка** — системная | — |
| 15 | Автозапуск при загрузке | `connection.autoStartOnBoot` | да |
| 16 | Safe mode | `connection.safeModeEnabled`, по умолчанию **true** | да, мета-переключатель |

## Клаузулы: сочетание → обязательный исход → чем держится

Это те клетки, которые должны быть **отказом**, а не успехом. Успех в любой из них —
дефект, и почти всегда молчаливый.

| сочетание | обязательный исход | тест |
|---|---|---|
| WireGuard-профиль + Tor-over-VPN (не bypass) | `OVERLAY_CONFIGURATION_UNSUPPORTED` на `$.outbounds[n].detour` | `FoxCoreConfigTranslatorRejectionTest` |
| Private DNS STRICT + I2P | `.i2p`-развилка **молча отсутствует** (fakeip + принудительный DoT — известно фатальная пара) | `RuntimeI2pOutboundsTest` |
| Private DNS STRICT + `replaceSystemDns` | принудительно выключается, сессия рвётся с `error_system_dns_private_dns_conflict` | `PrivateDnsModeTest`, `LocalGuardStartResolutionTest` |
| фаервол ON + `replaceSystemDns` ON | режим guard обязан быть `FIREWALL`, не `DNS` | `RuntimeSupervisorTest` |
| веб-аппы ON + сплит EXCLUDE с выбранным FoxHole | FoxHole всё равно **захвачен** в туннель | `RuntimeWebAppsTunIsolationTest` |
| `newAppQuarantineEnabled` ON + фаервол OFF | нормализуется в OFF | `SafeModeExpertInvariantTest` |
| `webApps.pushServiceEnabled` ON + фаервол OFF | нормализуется в OFF | `WebAppsNormalizationTest` |
| route-исключения на API < 33 или сверх лимита | fail closed | `RouteExcludeCompatibilityTest` |
| раздача при `torActive == false` | `FileShareFailureReason.TOR_REQUIRED` | `FileSharePolicyTest` ✱ |
| safe mode | сбрасывает ровно четыре поля Tor, полосу BLOCK не трогает | `AppLaneSafeModeTest` |
| смена режима guard (FIREWALL ↔ DNS) | полный рестарт, не reload | `LocalGuardStartResolutionTest` |
| VPN стартует первым, Tor доезжает reload'ом | неудачный старт VPN не запускает ничего другого | `FoxholeVpnServiceTorOrderingSupport` + его тесты |

✱ добавлен в этом проходе: предикат `torRouteReady` был приватным и проверялся
только живой Tor-сессией. Теперь это конъюнкция двух фактов, которые легко спутать —
туннель подключён **и** применённый конфиг реально несёт Tor, — и она под тестом.
`torActive` намеренно берётся из применённого конфига, а не из тумблера настроек,
иначе взведённый-но-не-задействованный маршрут читался бы как готовый.

## Что известно и не закрыто

**LAN-прокси (#8) — функции нет.** JNI-поверхность удалена из ядра,
`FoxCoreTunTranslator` принимает ровно один не-tun инбаунд, поэтому все живые пути
передают `includeLocalProxy = false`. Тумблер убран с экрана в этом проходе;
настройка, писатель и обвязка оставлены, чтобы возврат был правкой UI. Клаузулы для
него нет, потому что нечего сочетать.

**`eventMonitoringEnabled` без PIN (#11) — контракт не под тестом.**
`isEventMonitoringActive()` это конъюнкция `isPasswordProtectionActive() &&
eventMonitoringEnabled`, то есть запечатанный журнал не пишется без PIN. Проверить
это юнит-тестом сейчас нельзя без Robolectric: `FoxholeSecurityComponents` требует
`Context`. Клаузула верна и видна в коде, но держится соглашением, а не тестом.

**Приёмка сочетаний на живом устройстве** прошла частично: Tor-over-VPN, веб-аппы
поверх туннеля и подключение по подписке — зелёные; раздача через onion недоказуема
на фильтрующей сети, сплит отверг собственный замер. Подробности в
`PUBLIC-BETA-PLAN.md` §7.
