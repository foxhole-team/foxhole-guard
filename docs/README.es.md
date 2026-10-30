<p align="center">
    <img
      src="../media/fhg.gif"
      alt="FoxHole Guard"
      width="180"
      height="180"
    >
</p>

<div align="center">

[![English](https://img.shields.io/badge/%F0%9F%87%AC%F0%9F%87%A7-English-ff7a00?style=flat-square)](../README.md)
[![Русский](https://img.shields.io/badge/%F0%9F%87%B7%F0%9F%87%BA-%D0%A0%D1%83%D1%81%D1%81%D0%BA%D0%B8%D0%B9-ff7a00?style=flat-square)](README.ru.md)
[![Deutsch](https://img.shields.io/badge/%F0%9F%87%A9%F0%9F%87%AA-Deutsch-ff7a00?style=flat-square)](README.de.md)
[![Français](https://img.shields.io/badge/%F0%9F%87%AB%F0%9F%87%B7-Fran%C3%A7ais-ff7a00?style=flat-square)](README.fr.md)
[![Español](https://img.shields.io/badge/%F0%9F%87%AA%F0%9F%87%B8-Espa%C3%B1ol-ff7a00?style=flat-square)](README.es.md)
[![日本語](https://img.shields.io/badge/%F0%9F%87%AF%F0%9F%87%B5-%E6%97%A5%E6%9C%AC%E8%AA%9E-ff7a00?style=flat-square)](README.ja.md)
[![简体中文](https://img.shields.io/badge/%F0%9F%87%A8%F0%9F%87%B3-%E7%AE%80%E4%BD%93%E4%B8%AD%E6%96%87-ff7a00?style=flat-square)](README.zh.md)

</div>

<p align="center">
  <a href="https://github.com/foxhole-team/foxhole-guard/releases">
    <img src="https://img.shields.io/github/v/release/foxhole-team/foxhole-guard?label=GitHub%20version&style=flat-square" alt="GitHub Version">
  </a>
  <a href="https://f-droid.org/packages/com.foxhole.guard/">
    <img src="https://img.shields.io/f-droid/v/com.foxhole.guard?label=F-Droid&logo=f-droid&style=flat-square" alt="F-Droid Version">
  </a>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.foxhole.guard%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Ffoxhole-team%2Ffoxhole-guard%22%2C%22author%22%3A%22FoxHole%20Team%22%2C%22name%22%3A%22FoxHole%20Guard%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22arm64-v8a-release%5C%5C%5C%5C.apk%24%5C%22%2C%5C%22invertAPKFilter%5C%22%3Afalse%2C%5C%22verifyLatestTag%5C%22%3Atrue%7D%22%2C%22overrideSource%22%3A%22GitHub%22%7D">
    <img src="https://img.shields.io/badge/Obtainium-add%20app-1A73E8?style=flat-square" alt="Get it on Obtainium">
  </a>
</p>

# FoxHole Guard
![Platform](https://img.shields.io/badge/platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white)
![Status](https://img.shields.io/badge/status-STABLE-success?style=flat-square)
![Core](https://img.shields.io/badge/core-Rust-000000?style=flat-square&logo=rust&logoColor=white)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-007ec6?style=flat-square)](https://www.gnu.org/licenses/gpl-3.0.html)
![Telemetry](https://img.shields.io/badge/telemetry-none-success?style=flat-square)
![❤️ We support I2P](https://img.shields.io/badge/❤️_We_support-I2P-7B1FA2?style=flat-square)

**FoxHole Guard** reúne protección de red, privacidad y control local de la actividad de red en Android.

Las versiones publicadas y los APK están en [GitHub Releases](https://github.com/foxhole-team/foxhole-guard/releases).

La aplicación combina su propio **FoxHole Core nativo escrito en Rust**, VPN, Tor, I2P, cortafuegos local, filtrado DNS, supervisión de red, aplicaciones web y servidor proxy.

El objetivo es ofrecer en una sola aplicación protección cotidiana, acceso a VPN y a las redes anónimas Tor e I2P, enrutamiento del tráfico de aplicaciones y detección local de actividad inusual e instalaciones no autorizadas. FoxHole Guard registra eventos del sistema, instalaciones, desinstalaciones y anomalías del tráfico.

**FoxHole Guard** no contiene publicidad, análisis de uso ni telemetría. Las listas de aplicaciones, estadísticas, registros, reglas y resultados locales permanecen en el dispositivo, salvo exportación explícita del usuario.

| ![Main](../fastlane/metadata/android/en-US/images/phoneScreenshots/00_main_en.png) | ![Scenarios](../fastlane/metadata/android/en-US/images/phoneScreenshots/01_scenarios_en.png) |
| :---: | :---: |
| ![Map](../fastlane/metadata/android/en-US/images/phoneScreenshots/03_map_en.png) | ![Settings](../fastlane/metadata/android/en-US/images/phoneScreenshots/04_settings_en.png) |

---

## ✨ Funciones principales

- **FoxHole Core**: núcleo de red propio en Rust.
- **Protocolos VPN**: VLESS, VMess, Hysteria2, WireGuard, AmneziaWG (experimental), Trojan, Shadowsocks, Naive, TUIC y AnyTLS.
- **Acceso Tor**: Tor dentro de un túnel VPN TCP, aplicaciones seleccionadas, puentes y rotación programada de circuitos.
- **Acceso I2P**: recursos `.i2p` mediante un proceso `i2pd` independiente y un contrato SOCKS5 local con FoxHole Core.
- **Cortafuegos**: bloqueo de aplicaciones, interruptor de corte y cuarentena de nuevas instalaciones.
- **Gestión DNS**: interceptor local, UDP/TCP/DoT/DoH, caché y caché caducada.
- **Mapa de tráfico**: conexiones actuales del dispositivo en un mapa mundial.
- **FoxHole Sentinel**: análisis local opcional de IOC y anomalías, desactivado por defecto.
- **Aplicaciones web**: aplicaciones HTML5 y entrega de notificaciones.
- **Servidor proxy**: SOCKS5 y HTTP CONNECT autenticados en la red local.
- **Gestión del router en beta pública (OpenWrt)**: configuración, vinculación, perfiles VPN, rutas de dispositivos y paquetes de protocolos desde Ajustes.
- **Servicios en segundo plano**: `foxhole watchdog web` y `foxhole watchdog guard` para notificaciones web y eventos de seguridad.
- **Seguridad**: indicios de compromiso e interferencias mediante registros de instalación/desinstalación, anomalías de red y comprobaciones de indicadores y permisos de nuevas aplicaciones. Se admite cifrado personalizado de los datos.

---

## 🧭 Arquitectura y funcionamiento

### 🔁 Flujo de control

```text
application
        ↓
operating mode  →  scenario  →  traffic routing rules
        ↓
modules  ·  extensions  ·  background services
```

- El **modo** determina qué conexión, VPN y/o Tor, utiliza el túnel VPN del sistema Android.
- El **escenario** determina cómo se aplican VPN y Tor al dispositivo.
- Las **reglas** eligen las rutas VPN/Tor de aplicaciones y sitios.
- Los **módulos y extensiones** funcionan junto a cualquier modo activo.
- Los **servicios en segundo plano** habilitados funcionan dentro de los límites de Android.

### 👤 Interfaz de usuario y experto

**Usuario** muestra el panel compacto, los controles de conexión y el selector de perfiles. **Experto** añade diagnóstico y ajustes. Esta elección cambia la presentación; las reglas de enrutamiento, el consentimiento de seguridad y los ajustes avanzados son independientes.

Elija Usuario o Experto en Ajustes o en la configuración inicial de apariencia. El selector de modo se abre hacia arriba, ajustado al contenido, con respuesta háptica suave. El acceso a OpenWrt en la pantalla de inicio de la aplicación está ACTIVADO por defecto; el del dock, DESACTIVADO. Estas opciones independientes solo muestran u ocultan iconos: Ajustes de OpenWrt sigue accesible y no cambian las políticas del router, enrutamiento ni vinculación.

### 🎛️ Modos de funcionamiento

Se admiten tres modos principales:

| Modo | Descripción |
| --- | --- |
| **VPN** | Túnel VPN estándar. |
| **Tor** | Conexión Tor mediante Arti. No requiere perfil VPN; el módulo Tor debe estar habilitado. |
| **VPN y Tor** | Ambas conexiones simultáneas, cada una con su escenario. |

### 🧩 Escenarios

Un escenario controla el modo. En **VPN y Tor**, sus escenarios son independientes.

| Escenario | Significado | Disponible para |
| --- | --- | --- |
| **Dispositivo completo** | Todo el tráfico atraviesa el túnel. | VPN, Tor |
| **Proxy: aplicaciones seleccionadas** | Solo se enrutan las aplicaciones elegidas. | VPN, Tor |
| **Proxy: todas salvo las seleccionadas** | Se enruta el resto. | VPN |
| **Servidor proxy** | Proxy Android mediante la conexión activa del dispositivo. | VPN |
| **Servidor proxy en la red local** | Proxy local mediante la conexión activa del dispositivo. | VPN, Tor, Mixed |

Existen restricciones al enviar todo el tráfico por Tor con una VPN activa. Si Tor funciona fuera del túnel durante una conexión VPN, se utiliza el escenario de aplicaciones seleccionadas. En Mixed del proxy local, SOCKS5 pasa por VPN y HTTP CONNECT por Tor.

### 🔀 Reglas de enrutamiento

Las reglas se aplican dentro del modo y escenario activos:

- reglas por aplicación para elegir VPN o Tor;
- **bloquear Internet** y **excluir del filtrado DNS** siguen activos en los tres modos si el cortafuegos está habilitado;
- reglas de sitios por dominios, sufijos y rangos CIDR;
- políticas DNS independientes;
- bloqueo al perder la conexión, cuarentena de nuevas aplicaciones y reglas TTL.

| Regla | Dispositivo completo | Escenarios proxy |
| --- | --- | --- |
| aplicación → VPN | no aplicada | aplicada |
| aplicación → Tor | no aplicada | aplicada |
| aplicación → excluir | aplicada | aplicada |
| aplicación → bloquear | aplicada | aplicada |
| reglas de direcciones web | aplicadas | aplicadas |

**Dispositivo completo** ignora las reglas ordinarias excepto bloqueos y exclusiones. Sin túnel del sistema o cortafuegos en ejecución, la regla de bloqueo no se aplica.

Las reglas se aplican atómicamente. Los cambios de escenario también son atómicos por defecto; puede desactivarse en Ajustes.

---

## 🧱 Módulos

Los módulos se configuran aparte del modo y pueden funcionar independientemente si no necesitan una ruta activa.

| Módulo | Descripción |
| --- | --- |
| **Mapa de tráfico** | Conexiones actuales en un mapa mundial. |
| **Estadísticas** | Contabilidad local por aplicación, perfil VPN y filtrado DNS. |
| **Registros** | Registros de aplicación, red y seguridad. |
| **Cortafuegos** | Bloqueo y cuarentena de nuevas aplicaciones. |
| **Tor** | Arti: TCP, `.onion`, puentes y rotación programada. |
| **I2P** | `.i2p` mediante `i2pd` independiente y SOCKS5 de bucle local. |
| **FoxHole Sentinel** | Auditoría, coincidencias IOC y registro íntegro mediante cadena de hashes. |
| **DNS** | DoT/DoH y filtrado local. |

### 🧅 Acceso Tor

El acceso utiliza **Arti**.

Se admiten:

- Tor dentro de un protocolo VPN TCP;
- puentes;
- bloqueo de red al perder la conexión;
- rotación de circuitos con intervalo configurable;
- enrutamiento de aplicaciones seleccionadas.

El módulo Tor está incluido en la compilación Android estándar de FoxHole Core.

#### 🌐 Servicio onion

FoxHole Core admite servicios onion.

### 🕸️ Acceso I2P

I2P no está implementado dentro de FoxHole Core.

Se utiliza un contrato SOCKS5 de bucle local exclusivamente TCP con un proceso `i2pd` independiente.

Las condiciones I2P se verifican con cierre seguro ante fallos:

- interceptar enlaces `.i2p` desde cualquier aplicación;
- retransmitir tráfico a I2P;
- restricciones del uso de redes móviles;
- gestión del ancho de banda.

### 🧱 Cortafuegos

Utiliza la política de rutas compartida de FoxHole Core.

Se admiten:

- bloqueo de aplicaciones;
- prioridad de `Block` sobre rutas permitidas;
- reglas por aplicación;
- cuarentena de red;
- reglas TTL;
- interruptor de corte;
- revocación inmediata y selectiva de flujos proxy activos tras una regla de denegación; los flujos L3 del túnel de paquetes reevalúan la política tras su tiempo de inactividad;
- errores tipados ante recargas inválidas.

### 🛡️ FoxHole Sentinel

Es el módulo opcional de supervisión local de FoxHole Guard. Está desactivado por defecto; al habilitarlo, funciona en `foxhole watchdog guard`.

Combina señales independientes: indicadores de compromiso, actividad de red, análisis estático y comportamiento. Los resultados se procesan localmente y no se envían al desarrollador.

No se presenta como un antivirus. Muestra indicadores observables, sus fuentes y confianza, separando coincidencias IOC confirmadas de sospechas heurísticas.

#### ✅ Implementado

Funciones actuales:

- **Echap Stalkerware Indicators**: comprobación local de indicadores conocidos;
- **comparador IOC de red**: contrasta eventos Core en directo con dominios e IP conocidos; se activa con el interruptor de análisis Sentinel;
- inspección APK/DEX acotada y reglas YARA-X opcionales sobre DEX clásico verificado en un proceso aislado; informa de cobertura incompleta;
- indicadores estáticos de estructura DEX y referencias API sin ejecutar código;
- alertas estadísticas por picos de subida, tráfico en segundo plano o total, nuevos países de destino, proporciones de bloqueo DNS, tormentas de reconexión, cambios de latencia y actividad de aplicaciones inactivas;
- correlación local de IOC con la aplicación propietaria del flujo;
- integración con cortafuegos y cuarentena;
- auditoría mediante un flujo Core acotado.

Una coincidencia IOC es una señal de seguridad, no prueba concluyente de compromiso por sí sola.

FoxHole Core no conserva un registro Guard persistente. Expone un flujo acotado y Android mantiene el registro a largo plazo. Si el consumidor no puede seguir el ritmo, la interfaz informa explícitamente de eventos `dropped`.

#### 🧪 Previsto

La arquitectura permite ampliar el análisis local. Estas funciones **todavía no están implementadas ni forman parte de la versión actual**:

- análisis de código máquina nativo y detección más amplia de empaquetadores y técnicas antianálisis;
- análisis de comportamiento DEX y secuencias API de Android;
- correlación con MITRE ATT&CK Mobile;
- análisis avanzado del comportamiento de red;
- detección de actividad periódica de tipo baliza;
- indicadores DNS de tipo DGA;
- perfiles de comportamiento más amplios que las ventanas estadísticas acotadas actuales;
- motor que reúna pruebas estáticas, IOC, Android y de red en un hallazgo.

Se prevén detectores locales, sin análisis en la nube ni envío de APK, listas de aplicaciones o historial de red.

#### 📜 Registro de seguridad

La aplicación puede mantener un registro local cifrado con:

- instalaciones;
- actualizaciones;
- desinstalaciones;
- eventos de seguridad;
- eventos de red seleccionados.

Una cadena de hashes permite verificar su integridad.

---

## 🔌 Protocolos compatibles

| Protocolo / modo | Compatibilidad |
| --- | --- |
| **VLESS** | raw, WebSocket, HTTP Upgrade, gRPC/HTTP-2, XHTTP stream-one (HTTP/2), TLS, Reality, Vision, UDP-over-stream, XUDP, packetaddr |
| **VMess** | AEAD (`alterId=0`), raw, WebSocket, HTTP Upgrade, gRPC/HTTP-2, XHTTP stream-one (HTTP/2), TLS, TCP/UDP |
| **Hysteria2** | QUIC/H3, autenticación, Brutal, TCP/UDP, Salamander obfs, salto de puerto de destino sobre un socket UDP local protegido, reconexión |
| **WireGuard** | negociación Noise_IKpsk2 propia, ruta L3, `reserved`, importación `wg://` y `.conf` |
| **AmneziaWG** (experimental) | `Jc/Jmin/Jmax/S1..S4/H1..H4`, rangos de cabecera comprobados, plantillas 2.0 `I1..I5`, `RandomTrailers`, relleno de contenido y `HeaderProtectionKey` |
| **Trojan** | TLS, WebSocket, HTTP Upgrade, gRPC/HTTP-2, XHTTP stream-one (HTTP/2), TCP/UDP |
| **Shadowsocks** | AEAD y AEAD-2022, TCP/UDP |
| **Outline** | variante Shadowsocks, no protocolo independiente |
| **Naive** | HTTP/2 CONNECT nativo con relleno de protocolo |
| **TUIC** | v5 desarrollada independientemente, QUIC, autenticación TLS-exporter, TCP/UDP, fragmentación, reconexión |
| **AnyTLS** | v2 independiente, autenticación TLS, relleno, reutilización/multiplexación de sesiones, TCP y UDP UoT v2 |
| **SOCKS5** | CONNECT, UDP ASSOCIATE, autenticación |
| **Proxy HTTP** | HTTP CONNECT, autenticación |
| **Tor** | Arti, TCP, `.onion`, puentes |
| **I2P** | adaptador SOCKS5 TCP de bucle local a un proceso `i2pd` separado |

Reality es una capa de seguridad bajo los transportes de flujo compatibles, incluido XHTTP stream-one. Vision requiere TCP raw. Se rechazan otros modos XHTTP y los ajustes no compatibles.

### ↔️ Shadowsocks y Outline

Outline se trata como una variante Shadowsocks.

Se aceptan enlaces compartidos, suscripciones v2rayTun, JSON SIP008 y YAML Clash/mihomo compatible. Las claves Outline dinámicas (`ssconf://`) conservan su origen para actualizarse y compartirse. Se notifican entradas y ajustes no compatibles; no se descargan proveedores YAML externos.

Se admiten:

- AEAD;
- AEAD-2022;
- Outline `prefix=` para configuraciones AEAD compatibles;
- SIP003 `v2ray-plugin` en modo WebSocket;
- `simple-obfs` en modos `http` y `tls`.

### 🚫 No compatible

**ShadowsocksR (SSR)** está obsoleto y no se admite.

---

## 🌐 DNS

### 🧭 Gestión DNS

FoxHole Core incluye su propio interceptor DNS.

Se admiten:

- DNS UDP;
- DNS TCP;
- DoT;
- DoH;
- sockets DNS protegidos;
- arranque DNS mediante un `Network` Android concreto;
- caché;
- caché caducada;
- fake-IP;
- DNS consciente de la ruta.

### 🧹 Filtrado DNS

FoxHole Guard aplica reglas locales contra:

- publicidad;
- rastreadores;
- telemetría;
- dominios potencialmente maliciosos.

Las reglas parten del filtro DNS abierto de AdGuard. El origen se fija por nombre y se rechazan fuentes diferentes.

Manifiesto:

```text
https://foxhole-team.github.io/foxhole-db/manifest.json
```

Las reglas descargadas son **datos de filtrado, no código ejecutable**.

Antes de usarlas, se comprueban los parámetros del formato: firma, tamaño, compatibilidad y SHA-256.

Ante un fallo se conserva el conjunto instalado y verificado. En una instalación nueva, el filtrado no está disponible hasta verificar reglas válidas.

---

## 🔐 TLS y sockets protegidos

FoxHole Core utiliza TLS en Rust con validación de certificados.

Se admiten:

- validación WebPKI;
- SNI;
- ALPN;
- fijación SPKI SHA-256;
- modo de compatibilidad `insecure`.

`insecure` no es una configuración segura y exige consentimiento explícito.

Cada socket saliente Android creado por FoxHole Core sigue:

```text
protect(fd)
    ↓
bind to Android Network
    ↓
connect / send
```

---

## 🧩 Extensiones y otras funciones

Las extensiones amplían la aplicación principal.

| Extensión | Descripción |
| --- | --- |
| **Servidor proxy** | Acceso de otros dispositivos del Wi-Fi actual mediante SOCKS5 y HTTP CONNECT, autenticación obligatoria y vinculación a una red confirmada. |
| **Aplicaciones web** | Sitios HTML5 a pantalla completa, cada uno en un perfil WebView aislado con notificaciones. |

### 🔗 Servidor proxy

Comparte la ruta activa con otros dispositivos del Wi-Fi actual.

- SOCKS5 y HTTP CONNECT;
- autenticación obligatoria;
- cambiar de red lo deshabilita e invalida sus credenciales.

Preajustes: VPN, Tor, Mixed; SOCKS5 mediante VPN y HTTP CONNECT mediante Tor.

### 🖥️ Aplicaciones web

Cada aplicación web recibe un identificador ASCII independiente que FoxHole Core utiliza en su política de rutas.

- identidad y almacenamiento aislados por aplicación; si WebView lo permite, cada una dispone de cookies, almacenamiento y caché propios; sin perfiles múltiples se utiliza un perfil compartido con aislamiento de origen del navegador;
- concesiones enrutables independientes;
- reglas de red por aplicación web;
- notificaciones ligadas a la identidad de origen canónica;
- revocación de concesiones.

Cookies, almacenamiento y caché pueden borrarse para cada aplicación por separado. Eliminarla completamente también elimina sus datos.

#### 🔔 Servicio web en segundo plano: `foxhole watchdog web`

Un servicio independiente entrega las notificaciones.

**Modelo de consulta.** Según un horario, un WebView oculto recibe un adaptador JS que lee el contador del sitio o, si no existe, `(N)` en el título. La entrega es periódica, no en tiempo real; el intervalo se configura en Ajustes. Un temporizador del proceso se complementa con WorkManager cada 15 minutos si el proceso termina. Se agrupan consultas simultáneas y no se cargan imágenes.

**Condiciones.** No se consulta cuando:

- la ruta no está disponible: VPN requiere conexión activa y puente proxy local; WireGuard y AmneziaWG no lo proporcionan y no se usan para estas consultas; Tor requiere circuito activo e I2P conexión activa; `BLOCK` siempre prohíbe consultar;
- falta la sustitución de proxy WebView: es global al proceso y se valida con cierre seguro; solo se acepta un host de bucle local con puerto disponible y ambos valores de credenciales; en otro caso no se aplica.

**Después.** Se retira la sustitución y se verifica su retirada. La red de los service workers queda bloqueada hasta cambiar la ruta, para evitar accesos fuera de la nueva sustitución. Un marco en primer plano tiene propiedad exclusiva: mientras lo posee no hay consultas de fondo; sin obtenerla no se abre. Nunca funciona fuera de su ruta asignada.

## 🛜 Gestión del router en beta pública

Abra OpenWrt en Ajustes para vincular un router y gestionar sus perfiles VPN, rutas de dispositivos, paquetes de protocolos y actualizaciones de FoxHole. La compatibilidad y los procesos de instalación y actualización siguen en validación. El interruptor de gestión se aplica al vínculo guardado seleccionado; apagarlo no detiene la VPN del router.

> [!IMPORTANT]
> La instalación automática actualmente solo admite routers con la configuración predeterminada de OpenWrt, sin modificaciones.
> También puede vincular un router después de instalar manualmente la última versión de [FoxHole OpenWrt Client](https://github.com/foxhole-team/foxhole-openwrt-client).
> Para la instalación y la vinculación, el teléfono y el router deben estar en la misma red local, y el router debe tener acceso a Internet.

El icono Wi-Fi indica solo una conexión Wi-Fi física, sin SSID ni acceso a la ubicación. Buscar y realizar una nueva configuración/vinculación requiere Wi-Fi. La dirección manual se rellena una sola vez con la puerta de enlace IPv4 privada del Wi-Fi actual, si está disponible, y sigue siendo editable.

Gestionar mediante la VPN de casa es una opción independiente, DESACTIVADA por defecto para cada vínculo existente. También exige su confirmación Dirección IP pública (el router tiene una IP pública y un servicio VPN entrante) y una ruta VPN activa utilizable. Esa confirmación no asigna una dirección ni configura el cortafuegos. La aplicación puede intentar gestionar el destino privado guardado a través de esa red VPN con la identidad TLS fijada y la autenticación vinculada existentes; no abre puertos de gestión WAN ni permite nuevas búsquedas/vinculaciones por red móvil. El cambio/handoff automático de la VPN del teléfono sigue desactivado; esta opción solo gestiona el router.

Solo los perfiles de VPN entrante enviados directamente a la aplicación por un router vinculado y autenticado reciben metadatos separados de enlace con la aplicación. No cambian el URI de conexión, la configuración ni los formatos QR. Las exportaciones genéricas web/QR/de proveedores siguen sin marcar; las indicaciones importadas no establecen confianza por sí solas.

El reconocimiento automático requiere además que coincidan el perfil local guardado, su configuración actual y el vínculo seleccionado, y que ese perfil esté realmente conectado mediante la VPN de la propia aplicación. Se realiza una comprobación limitada por conexión real, sin temporizador, reintentos automáticos ni sondeo de perfiles de proveedores. Repetir vinculación es un proceso explícito con Wi-Fi/permiso/PIN: conserva el registro anterior hasta autenticarse correctamente y nunca acepta silenciosamente una identidad de certificado distinta.

La configuración requiere OpenWrt 25.12.5 con APK, acceso de administrador y un catálogo autenticado para el destino y la arquitectura exactos. La política actual de paquetes y actualizaciones admite `mediatek/filogic` y `x86/64`; esto no valida todos los modelos de router. También deben superarse las comprobaciones de dependencias, ABI del kernel, flash y RAM.

La primera instalación exige aceptar expresamente la licencia de FoxHole OpenWRT Client; esta opción empieza desmarcada. La casilla independiente de actualizaciones automáticas está activada en una instalación nueva: desmárquela para rechazarlas o cámbiela después en Ajustes del router. Vincular un router existente conserva su política. En una instalación directa por CLI/backend, las actualizaciones automáticas están desactivadas por defecto.

Las actualizaciones manuales desde la aplicación y LuCI usan la misma transacción autenticada. Si las automáticas están activadas, el router comprueba cada día e instala paquetes FoxHole verificados, independientemente del canal de actualización de la aplicación. Se conservan perfiles, vínculo y módulos de protocolos seleccionados; no se actualizan el firmware de OpenWrt ni otros paquetes del sistema. Desvincular el teléfono no desactiva la política del router.

---

## ⚙️ Servicios en segundo plano

Se utilizan dos servicios independientes.

| Servicio | Función |
| --- | --- |
| **`foxhole watchdog web`** | Comprueba y entrega notificaciones de aplicaciones web añadidas. |
| **`foxhole watchdog guard`** | Supervisa eventos y registra instalaciones/desinstalaciones y anomalías de tráfico de forma protegida. |

En modo reforzado, `foxhole watchdog guard` inicia un servicio en primer plano si no existe conexión activa y lo detiene al detectarla. El modo de ahorro usa consultas periódicas WorkManager.

---

## 🗃️ FoxHole DB y conjuntos de datos

[![FoxHole DB](https://img.shields.io/badge/GitHub-FoxHole_DB-181717?logo=github)](https://github.com/foxhole-team/foxhole-db)

FoxHole DB contiene cinco conjuntos firmados independientes que se descargan cuando la función los necesita. El flujo TLS solo lleva tablas: ClientHello sigue compilado en FoxHole Core. Un fallo conserva las tablas integradas o las últimas verificadas.

| Conjunto | Artefacto | Formato | Fuente | Licencia | Versión |
| --- | --- | --- | --- | --- | --- |
| **DNS: listas de filtrado** | `adguard-dns-filter.fhds` | `foxhole-dns-fst-v1` | [AdGuard DNS filter](https://github.com/AdguardTeam/AdGuardSDNSFilter) | GPL-3.0 | Commit de origen fijado por compilación. |
| **Tor: copia de puentes integrados** | `bridges.json` | `tor-bridges-json` | [Tor Project built-in bridges (Moat)](https://bridges.torproject.org/moat/circumvention/builtin) | datos públicos para eludir la censura | Marcador `generated_at`; claves normalizadas, orden original de los arrays conservado. |
| **FoxHole Sentinel: inteligencia de amenazas** | `threat-intel.json` | `sentinel-threat-intel-json`, documento `schema: 3` | [AssoEchap/stalkerware-indicators](https://github.com/AssoEchap/stalkerware-indicators) | CC-BY-4.0 | Commit fijado por compilación y registrado en `threat-intel-source-info.json`. |
| **Geolocalización: IP → país** | `dbip-country-ipv4.csv`, `dbip-country-ipv6.csv` | `dbip-country-csv` | [DB-IP Lite via sapics/ip-location-db](https://github.com/sapics/ip-location-db) (`dbip-country`) | CC-BY-4.0 | `version` de origen en `package.json` copiada al manifiesto. |
| **Tablas de huellas TLS: perfiles ClientHello** | `fingerprints.json` | `tls-fingerprint-tables-json` | [FoxHole Core `fingerprints/`](https://github.com/foxhole-team/foxhole-core) | GPL-3.0-or-later | Revisión fijada por compilación; cada perfil recalcula `fingerprint_sha256` antes de aplicarse. |

---

## 🔒 Permisos y privacidad

- `INTERNET`: VPN, proxy, Tor, I2P, DNS, aplicaciones web, suscripciones y datos actualizables.
- `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE`: estado y reglas de red, servidor proxy.
- `ACCESS_LOCAL_NETWORK` - buscar, configurar y gestionar routers. En Android 17 (API 37) o posterior, si la aplicación tiene como objetivo API 37 o posterior, solicita el permiso del sistema antes de las acciones LAN. Denegarlo bloquea esas acciones.
- `FOREGROUND_SERVICE` y permisos correspondientes: modos de red prolongados controlados por el usuario, con notificación visible.
- `QUERY_ALL_PACKAGES`: lista para rutas selectivas, cortafuegos, cuarentena, exclusiones DNS y auditoría local.
- `PACKAGE_USAGE_STATS`: solo estadísticas y análisis locales habilitados que requieren ese acceso Android.
- `CAMERA`: importar QR.
- `POST_NOTIFICATIONS`: servicios de red, Sentinel y notificaciones web.
- `RECEIVE_BOOT_COMPLETED`: restaurar el modo elegido tras reiniciar.
- `WAKE_LOCK`: mantener sesiones cuando resulte necesario.

Permanecen en el dispositivo:

- lista de aplicaciones instaladas;
- estadísticas locales;
- conexiones y registros;
- resultados Sentinel;
- historial de conexión automática;
- exclusiones DNS;
- política del cortafuegos;
- política de rutas;
- datos locales de aplicaciones web;
- ajustes de módulos y extensiones;
- metadatos de Vault.

FoxHole Guard no envía estos datos al desarrollador.

### 🌍 Solicitudes de red

Las actualizaciones de las tablas TLS de FoxHole DB están habilitadas por defecto. Puede ejecutarse una comprobación tras iniciar y programarse después a diario si hay red. Se desactivan en Ajustes. Los demás conjuntos firmados y suscripciones guardadas tienen controles propios.

Las pruebas de conectividad y consultas de IP contactan con Google, Cloudflare, ipwho.is y otros servicios alternativos. Reciben la solicitud y la IP pública utilizada. También hay actividad relacionada con:

- VPN;
- Tor;
- I2P;
- actualizaciones HTTPS de suscripciones;
- pruebas de conectividad;
- DNS configurado por el usuario;
- actualizaciones GeoIP/DNS/puentes Tor;
- aplicaciones web;
- entrega de eventos para aplicaciones web habilitadas.

---

## 🤖 Android / ABI nativa

Se requiere Android 8 (API 26) o posterior. FoxHole Core se conecta mediante una ABI C/JNI versionada; la API Capabilities informa de los protocolos y funciones compilados en la aplicación.

El perfil de entrega `android4` tiene como destinos `arm64-v8a`, `armeabi-v7a`, `x86` y `x86_64`. Cada ABI necesita bibliotecas nativas y licencias correspondientes. Las arquitecturas APK y los nombres de archivo disponibles se determinan en la versión realmente publicada; una opción de compilación no demuestra compatibilidad con dispositivos o ejecución.

Las compilaciones nativas usan herramientas Rust/NDK fijadas y requieren comprobaciones ELF.

---

## 🔏 Verificación de publicaciones

Los APK de versión firmados por el propietario utilizan el certificado que figura a continuación.

GitHub y F-Droid son canales separados. La compilación GitHub admite buscar actualizaciones APK dentro de la aplicación. La de F-Droid deshabilita esas actualizaciones y omite el permiso para instalar paquetes; las gestiona el cliente F-Droid. Cada APK tiene su SBOM de entrega. Arquitectura, disponibilidad y versión deben verificarse en los artefactos reales. Una receta no demuestra compilación F-Droid exitosa, reproducibilidad ni aceptación en el repositorio.

Se recomienda comprobar la suma y el certificado de los APK de una publicación oficial.

```bash
sha256sum -c SHA256SUMS
```

```bash
apksigner verify --print-certs downloaded-apk.apk
```

Huella SHA-256 esperada del certificado:

```text
e59de2486084c38f3c77e9df0eb5eff9a4559f3c68024f1208e0e9c04b0df665
```

Huella SHA-1 esperada:

```text
9e00dc6b54461a4647cb11133b394cdfc986646d
```

---

## 🔗 Proyectos relacionados

[![FoxHole Core](https://img.shields.io/badge/GitHub-FoxHole_Core-181717?logo=github)](https://github.com/foxhole-team/foxhole-core)
[![Version](https://img.shields.io/github/v/release/foxhole-team/foxhole-core?label=version)](https://github.com/foxhole-team/foxhole-core/releases)

[![FoxHole DB](https://img.shields.io/badge/GitHub-FoxHole_DB-181717?logo=github)](https://github.com/foxhole-team/foxhole-db)
[![Version](https://img.shields.io/github/v/release/foxhole-team/foxhole-db?label=version)](https://github.com/foxhole-team/foxhole-db/releases)


[![FoxHole VPN](https://img.shields.io/badge/GitHub-FoxHole_VPN-181717?logo=github&style=flat-square)](https://github.com/foxhole-team/foxhole-vpn)
[![Version](https://img.shields.io/github/v/release/foxhole-team/foxhole-vpn?label=version&style=flat-square)](https://github.com/foxhole-team/foxhole-vpn/releases)

[![FoxHole OpenWrt](https://img.shields.io/badge/GitHub-FoxHole_OpenWrt-181717?logo=github&style=flat-square)](https://github.com/foxhole-team/foxhole-openwrt-client)
[![Version](https://img.shields.io/github/v/release/foxhole-team/foxhole-openwrt-client?label=version&style=flat-square)](https://github.com/foxhole-team/foxhole-openwrt-client/releases)

---

## 📄 Licencias

FoxHole Guard y FoxHole Core se distribuyen bajo:

**GNU General Public License v3.0 o posterior (`GPL-3.0-or-later`)**

Copyright (C) 2026 FOXHOLE TEAM.

Las licencias de terceros figuran en:

- `THIRD_PARTY_NOTICES.md`;
- la SBOM del proyecto (`*.cdx.json`).

El grafo de dependencias y licencias se valida en CI.

---

## 💖 Apoyar el desarrollo

Desarrollamos y mantenemos FoxHole Guard íntegramente con nuestro propio tiempo y recursos, con ayuda de herramientas de IA. Su apoyo nos ayuda a dedicar más tiempo al proyecto y acelerar el desarrollo.

[Apoyar el proyecto - monederos de criptomonedas](#cryptocurrency-wallets)

<a id="cryptocurrency-wallets"></a>

### 🪙 Monederos de criptomonedas

**XMR (Monero):**

```text
48yBVPTdcyJ1WoJtnKmVpEZziEsDy4HvbCW7eQDS9mfdiWPFXwZ8F5h9YZ2UTTBLxPcJgQgvth7iqLZM2yMCaQ432qaouqr
```

**BTC (Bitcoin):**

```text
bc1qatnyy7jcpqrp0d3dk9rta9vqfejgh4mysd6m2f
```

**ETH (Ethereum):**

```text
0xDEBA357Cc8f5E865ea7FFa98E138C8241A16A465
```

---

## ⚠️ Aviso

- Tor es una marca de The Tor Project. FoxHole Guard no es su producto, no está respaldado ni patrocinado por él y no tiene afiliación con él.
- FoxHole Guard no es un producto de AdGuard ni está afiliado a AdGuard Software Ltd.
- FoxHole Guard no es un producto oficial de I2P o PurpleI2P.
