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

**FoxHole Guard** は、Android 向けのネットワーク保護、プライバシー保護、ローカルな通信管理をまとめたアプリです。

公開バージョンと APK は [GitHub Releases](https://github.com/foxhole-team/foxhole-guard/releases) に掲載されます。

独自の **Rust 製ネイティブ FoxHole Core**、VPN、Tor、I2P、ローカルファイアウォール、DNS フィルタリング、通信監視、Web アプリ、プロキシサーバーを統合しています。

日常の通信保護、VPN と Tor/I2P 匿名ネットワークへの接続、アプリ別ルーティング、異常な通信や不正なインストールのローカル検知を、一つのアプリで提供することが目的です。システムイベント、アプリのインストールと削除、通信異常を記録します。

**FoxHole Guard** に広告、利用状況分析、テレメトリーはありません。アプリ一覧、統計、ログ、ルール、ローカル分析結果は、ユーザーが明示的に書き出さない限り端末内に保持されます。

| ![Main](../fastlane/metadata/android/en-US/images/phoneScreenshots/00_main_en.png) | ![Scenarios](../fastlane/metadata/android/en-US/images/phoneScreenshots/01_scenarios_en.png) |
| :---: | :---: |
| ![Map](../fastlane/metadata/android/en-US/images/phoneScreenshots/03_map_en.png) | ![Settings](../fastlane/metadata/android/en-US/images/phoneScreenshots/04_settings_en.png) |

---

## ✨ 主な機能

- **FoxHole Core**：独自の Rust 製ネットワークコア。
- **VPN プロトコル**：VLESS、VMess、Hysteria2、WireGuard、AmneziaWG（試験的サポート）、Trojan、Shadowsocks、Naive、TUIC、AnyTLS。
- **Tor 接続**：TCP VPN トンネル内の Tor、選択アプリのルーティング、ブリッジ、定期的な回線更新。
- **I2P 接続**：独立した `i2pd` プロセスと FoxHole Core のローカル SOCKS5 接続契約を使った `.i2p` へのアクセス。
- **ファイアウォール**：アプリの遮断、キルスイッチ、新規インストールアプリの隔離。
- **DNS 管理**：ローカルインターセプター、UDP/TCP/DoT/DoH、キャッシュと期限切れキャッシュ。
- **通信マップ**：現在の端末接続を世界地図に表示。
- **FoxHole Sentinel**：任意のローカル IOC・通信異常分析。初期状態は無効。
- **Web アプリ**：HTML5 アプリと通知配信。
- **プロキシサーバー**：認証付きのローカルネットワーク SOCKS5・HTTP CONNECT。
- **ルーター管理のパブリックベータ（OpenWrt）**：設定から初期設定、ペアリング、VPN プロファイル、端末ルーティング、プロトコルパッケージを管理します。
- **バックグラウンドサービス**：Web 通知の `foxhole watchdog web` と安全性監視の `foxhole watchdog guard`。
- **安全性**：インストール・削除記録、通信異常、新規アプリの脅威指標と要求権限の検査による侵害・干渉の兆候検知。独自のアプリデータ暗号化にも対応。

---

## 🧭 アーキテクチャと動作モデル

### 🔁 アプリの制御フロー

```text
application
        ↓
operating mode  →  scenario  →  traffic routing rules
        ↓
modules  ·  extensions  ·  background services
```

- **動作モード**は、VPN と Tor のどちらが Android のシステム VPN トンネルを使用するかを決めます。
- **シナリオ**は VPN と Tor の端末への適用方法を決めます。
- **ルール**はアプリとサイトの VPN/Tor 経路を決めます。
- **モジュールと拡張機能**は、どの動作モードとも並行して動作します。
- 有効な**バックグラウンドサービス**は Android の制約内で継続して動作します。

### 👤 ユーザーとエキスパートの画面

**ユーザー**はコンパクトなダッシュボード、接続操作、プロファイル選択を表示します。**エキスパート**は追加の診断と調整を表示します。この選択は表示を変えるもので、ルーティングルール、セキュリティ同意、詳細設定とは別です。

ユーザーまたはエキスパートは設定、または初回の外観設定で選べます。モード選択は内容に応じた高さで上向きに開き、軽い触覚フィードバックを使います。アプリのホーム画面の OpenWrt ショートカットは既定でオン、ドックのショートカットはオフです。独立した設定でアイコンの表示だけを変えます。OpenWrt 設定は引き続き開くことができ、ルーター・ルーティング・ペアリングの方針は変わりません。

### 🎛️ 動作モード

三つの基本モードがあります。

| モード | 説明 |
| --- | --- |
| **VPN** | 標準の VPN トンネル。 |
| **Tor** | Arti による Tor 接続。VPN プロファイルは不要ですが Tor モジュールを有効にする必要があります。 |
| **VPN と Tor** | 両方を同時に動作させ、それぞれ別のシナリオを使用。 |

### 🧩 モードのシナリオ

シナリオがモードの適用方法を制御します。**VPN と Tor** では別々のシナリオを使用します。

| シナリオ | 意味 | 利用可能な接続 |
| --- | --- | --- |
| **端末全体** | 端末の全通信をトンネルへ送る。 | VPN, Tor |
| **プロキシ：選択アプリ** | 選択したアプリだけを経由させる。 | VPN, Tor |
| **プロキシ：選択アプリ以外** | 選択したアプリ以外を経由させる。 | VPN |
| **プロキシサーバー** | 端末の有効な接続を使う Android プロキシ。 | VPN |
| **ローカルネットワークのプロキシ** | 端末の有効な接続を LAN に提供。 | VPN, Tor, Mixed |

VPN と同時に端末全体を Tor 経由にする場合には制限があります。有効な VPN トンネルの外側で Tor を起動した場合は、選択アプリのシナリオを使用します。ローカルプロキシの Mixed モードでは SOCKS5 を VPN、HTTP CONNECT を Tor 経由にします。

### 🔀 通信ルーティングルール

ルールは現在のモードとシナリオの中で適用されます。

- アプリ単位で VPN と Tor の経路を指定；
- ファイアウォールが有効なら、**インターネットを遮断**と **DNS フィルターから除外**は全三モードで有効；
- サイトのルールはドメイン、接尾辞、CIDR 範囲に対応；
- 独立した DNS ポリシー；
- 切断時の遮断、新規アプリの隔離、TTL ルール。

| ルール | 端末全体 | プロキシシナリオ |
| --- | --- | --- |
| アプリ → VPN | 適用しない | 適用 |
| アプリ → Tor | 適用しない | 適用 |
| アプリ → 除外 | 適用 | 適用 |
| アプリ → 遮断 | 適用 | 適用 |
| Web アドレスのルール | 適用 | 適用 |

**端末全体**では遮断と除外以外の通常の経路ルールを無視します。システムトンネルまたはファイアウォールが動いていないと遮断ルールは適用されません。

通信ルールはアトミックに適用されます。シナリオの変更も初期状態ではアトミックです。この動作は設定で無効にできます。

---

## 🧱 モジュール

モジュールはモードとは別に設定します。アクティブな経路を必要としない機能は独立して動作できます。

| モジュール | 説明 |
| --- | --- |
| **通信マップ** | 現在の接続を世界地図に表示。 |
| **統計** | アプリ、VPN プロファイル、DNS フィルタリング別のローカル集計。 |
| **ログ** | アプリ、ネットワーク、安全性のログ。 |
| **ファイアウォール** | アプリの遮断と新規アプリの隔離。 |
| **Tor** | Arti：TCP、`.onion`、ブリッジ、定期的な回線更新。 |
| **I2P** | 独立した `i2pd` とループバック SOCKS5 による `.i2p`。 |
| **FoxHole Sentinel** | アプリ監査、IOC 照合、ハッシュチェーンによるログ整合性確認。 |
| **DNS** | DoT/DoH とローカルフィルタリング。 |

### 🧅 Tor 接続

**Arti** を使用します。

対応機能：

- TCP VPN プロトコル内での Tor 接続；
- ブリッジ；
- 切断時のネットワーク遮断；
- 設定した間隔での回線更新；
- 選択アプリのルーティング。

Tor モジュールは FoxHole Core の標準 Android ビルドに含まれます。

#### 🌐 Onion サービス

FoxHole Core は onion サービスに対応します。

### 🕸️ I2P 接続

I2P 自体は FoxHole Core 内には実装されていません。

TCP 専用のループバック SOCKS5 接続契約で、独立した `i2pd` プロセスに接続します。

I2P の経路条件は、失敗時に閉じる方式で確認します。

- どのアプリからの `.i2p` リンクも捕捉；
- I2P ネットワークへの通信中継；
- モバイルネットワークの利用制限；
- 帯域管理。

### 🧱 ファイアウォール

FoxHole Core の共通経路ポリシーを使用します。

対応機能：

- アプリの遮断；
- 許可経路より `Block` を優先；
- アプリ別ルール；
- ネットワーク隔離；
- TTL ルール；
- キルスイッチ；
- 拒否ルール追加後、対象のアクティブなストリームプロキシ通信を即座に取り消し。L3 パケットトンネルのフローはアイドルタイムアウト後に再評価；
- 無効な再読み込みは型付きエラーとして報告。

### 🛡️ FoxHole Sentinel

FoxHole Guard の任意のローカル安全性監視モジュールです。初期状態では無効で、有効にすると `foxhole watchdog guard` でバックグラウンド処理を行います。

侵害指標、ネットワーク活動、静的解析、動作指標という独立した信号を組み合わせます。結果はローカル処理され、開発者には送信されません。

アンチウイルス製品として位置付けていません。観測可能な指標、その出所と信頼度を示し、確認された IOC とヒューリスティックな疑いを区別します。

#### ✅ 実装済み

現在の機能：

- **Echap Stalkerware Indicators**：既知のストーカーウェア指標をローカル照合；
- **ネットワーク IOC 照合**：Core のライブイベントを既知のドメイン・IP と照合。Sentinel 分析スイッチで有効化；
- 範囲を制限した APK/DEX 検査と任意の YARA-X ルール。検証済みの従来型 DEX バイト列を隔離スキャナープロセスで処理し、不完全な検査範囲を報告；
- コードを実行せずに DEX 構造と API 参照の静的指標を解析；
- アップロード、バックグラウンド、総通信量の急増、新しい送信先国、DNS 遮断比率、再接続の集中、遅延変化、休眠アプリの通信に対する統計アラート；
- IOC と通信所有アプリをローカルで関連付け；
- ファイアウォールと隔離への統合；
- 有界 Core イベントストリームによる監査。

IOC の一致は安全性の信号ですが、それだけで端末の侵害を断定する証拠ではありません。

FoxHole Core 自体は永続的な Guard ユーザーログを持ちません。有界ストリームを公開し、Android アプリが長期ログを保持します。受信側が追いつかない場合は、監査インターフェースが `dropped` イベントを明示します。

#### 🧪 計画中

ローカル分析を拡張できる構成です。次の機能は**未実装で、現在のバージョンには含まれません**。

- ネイティブライブラリの機械語解析と、より広いパッカー・解析妨害の検出；
- DEX と Android API シーケンスの動作解析；
- MITRE ATT&CK Mobile との関連付け；
- 高度なアプリ通信動作解析；
- ビーコン型活動の検出；
- DGA 型 DNS 指標；
- 現在の有界通信統計ウィンドウを超える動作ベースライン；
- 静的、IOC、Android、ネットワークの証拠を単一の検出結果にまとめる相関エンジン。

これらもローカル検出を予定しています。クラウドスキャンや APK、インストール一覧、通信履歴の送信は予定していません。

#### 📜 安全性ログ

暗号化されたローカルログに次を保存できます。

- アプリのインストール；
- 更新；
- 削除；
- 安全性イベント；
- 選択されたネットワークイベント。

ハッシュチェーンで整合性を確認します。

---

## 🔌 対応プロトコル

| プロトコル / モード | 対応範囲 |
| --- | --- |
| **VLESS** | raw, WebSocket, HTTP Upgrade, gRPC/HTTP-2, XHTTP stream-one (HTTP/2), TLS, Reality, Vision, UDP-over-stream, XUDP, packetaddr |
| **VMess** | AEAD (`alterId=0`), raw, WebSocket, HTTP Upgrade, gRPC/HTTP-2, XHTTP stream-one (HTTP/2), TLS, TCP/UDP |
| **Hysteria2** | QUIC/H3、認証、Brutal、TCP/UDP、Salamander obfs、単一の保護済みローカル UDP ソケットでの宛先ポート変更、再接続 |
| **WireGuard** | 独自 Noise_IKpsk2 ハンドシェイク、L3 データパス、`reserved`、`wg://` と `.conf` の取り込み |
| **AmneziaWG** (試験的サポート) | `Jc/Jmin/Jmax/S1..S4/H1..H4`、検証付きヘッダー範囲、2.0 テンプレート `I1..I5`、`RandomTrailers`、コンテンツパディング、`HeaderProtectionKey` |
| **Trojan** | TLS, WebSocket, HTTP Upgrade, gRPC/HTTP-2, XHTTP stream-one (HTTP/2), TCP/UDP |
| **Shadowsocks** | AEAD と AEAD-2022、TCP/UDP |
| **Outline** | Shadowsocks の派生。独立したプロトコルではない |
| **Naive** | プロトコルパディング付きネイティブ HTTP/2 CONNECT |
| **TUIC** | 独立実装 v5、QUIC、TLS-exporter 認証、TCP/UDP、分割、再接続 |
| **AnyTLS** | 独立実装 v2、TLS 認証、パディング、セッション再利用・多重化、TCP と UoT v2 UDP |
| **SOCKS5** | CONNECT、UDP ASSOCIATE、認証 |
| **HTTP プロキシ** | HTTP CONNECT、認証 |
| **Tor** | Arti、TCP、`.onion`、ブリッジ |
| **I2P** | 独立 `i2pd` プロセスへの TCP 専用ループバック SOCKS5 アダプター |

Reality は XHTTP stream-one を含む対応ストリームトランスポートの下位のセキュリティ層です。Vision には raw TCP が必要です。他の XHTTP モードと未対応設定は拒否します。

### ↔️ Shadowsocks と Outline

Outline は Shadowsocks の派生として扱います。

共有リンク、v2rayTun 購読、SIP008 JSON、互換 Clash/mihomo YAML を取り込めます。動的 Outline キー（`ssconf://`）は更新・共有用に取得元を保持します。非対応の項目やクライアント設定は報告し、外部 YAML プロバイダーは取得しません。

対応範囲：

- AEAD；
- AEAD-2022；
- 互換 AEAD 設定の Outline `prefix=`；
- WebSocket モードの SIP003 `v2ray-plugin`；
- `http`・`tls` モードの `simple-obfs`。

### 🚫 非対応

**ShadowsocksR（SSR）** は廃止され、対応していません。

---

## 🌐 DNS

### 🧭 DNS 管理

FoxHole Core は独自の DNS インターセプターを備えています。

対応範囲：

- UDP DNS；
- TCP DNS；
- DoT；
- DoH；
- 保護された DNS ソケット；
- 特定の Android `Network` を使う DNS ブートストラップ；
- キャッシュ；
- 期限切れキャッシュ；
- fake-IP；
- 経路を考慮した DNS。

### 🧹 DNS フィルタリング

ローカルルールで次をフィルタリングできます。

- 広告；
- トラッカー；
- テレメトリー；
- 悪意の可能性があるドメイン。

公開 AdGuard DNS フィルターからルールを生成します。上流の名前は固定され、別の取得元から生成されたフィードは拒否します。

マニフェスト：

```text
https://foxhole-team.github.io/foxhole-db/manifest.json
```

ダウンロードされる DNS ルールは**フィルターデータであり、実行コードではありません**。

使用前に、署名、サイズ、互換性、SHA-256 など形式で定めた値を検証します。

失敗した場合、既存の検証済みルールを保持します。新規インストールでは、検証を通過するまで DNS フィルターを利用できません。

---

## 🔐 TLS と保護されたソケット

FoxHole Core は証明書検証付きの Rust TLS スタックを使用します。

対応範囲：

- WebPKI 検証；
- SNI；
- ALPN；
- SPKI SHA-256 ピン；
- `insecure` 互換モード。

`insecure` は安全な既定値ではなく、ユーザーの明示的な同意が必要です。

FoxHole Core が作る Android の送信ソケットは、すべて次の手順に従います。

```text
protect(fd)
    ↓
bind to Android Network
    ↓
connect / send
```

---

## 🧩 拡張機能と追加機能

拡張機能で基本機能を補います。

| 拡張機能 | 説明 |
| --- | --- |
| **プロキシサーバー** | 現在の Wi-Fi の他端末に SOCKS5/HTTP CONNECT を提供。認証必須で、確認済みネットワークに結び付ける。 |
| **Web アプリ** | HTML5 サイトを全画面で表示し、それぞれ隔離された WebView プロファイルと通知を使用。 |

### 🔗 プロキシサーバー

現在の Wi-Fi の他端末とアクティブな経路を共有します。

- SOCKS5 と HTTP CONNECT；
- 必須認証；
- ネットワーク変更時はサーバーを無効にし、認証情報を失効。

VPN、Tor、Mixed のプリセットがあります。Mixed は SOCKS5 を VPN、HTTP CONNECT を Tor 経由にします。

### 🖥️ Web アプリ

各 Web アプリに独自の ASCII ID を作り、FoxHole Core の経路ポリシーで使います。

- アプリ別の保存領域を隔離。対応 WebView では Cookie・保存領域・キャッシュを持つ別プロファイルを使い、非対応の場合は共有プロファイルとブラウザーのオリジン隔離を使用；
- 独立したルーティング可能なリース；
- Web アプリ単位のネットワークルール；
- 正規オリジン ID に結び付いた通知；
- 許可済みリースの取り消し。

Cookie、保存領域、キャッシュは追加した Web アプリごとに消去できます。アプリを完全に削除すると、そのデータも削除します。

#### 🔔 Web アプリのバックグラウンドサービス：`foxhole watchdog web`

独立したサービスが通知を配信します。

**ポーリング方式。** 非表示 WebView に JS シムを挿入し、サイトのカウンター、なければタブタイトルの `(N)` を定期的に読み取ります。リアルタイム配信ではなく、間隔は設定で変更できます。プロセス内タイマーを基本に、プロセス終了時の代替として WorkManager を 15 分ごとに動作させます。同時実行をまとめ、画像は読み込みません。

**実行条件。** 次の場合はポーリングしません。

- 経路が使えない場合。VPN にはアクティブ接続とローカルプロキシブリッジが必要です。WireGuard/AmneziaWG はブリッジを持たないため、この処理には使いません。Tor には有効な回線、I2P には有効な接続が必要で、`BLOCK` は常に禁止します；
- WebView のプロキシ上書きが未設定の場合。上書きはプロセス全体に作用し、失敗時に閉じる検証を行います。利用可能なポートと両方の認証値を持つループバックホストだけを受け付け、それ以外では適用しません。

**処理後。** 上書きを解除し、解除を確認します。経路が変更されるまで Service Worker の通信を遮断し、新しい上書きを迂回させません。前景フレームが上書きを排他的に所有する間は背景処理を開始せず、所有権を取得できなければフレームを開きません。割り当てた経路の外側では動作しません。

## 🛜 ルーター管理のパブリックベータ

設定の OpenWrt を開くと、ルーターをペアリングし、VPN プロファイル、端末ルーティング、プロトコルパッケージ、FoxHole 更新を管理できます。端末互換性とインストール・更新手順は引き続き検証中です。管理スイッチは選択した保存済みペアリングに適用され、オフにしてもルーターの VPN は停止しません。

> [!IMPORTANT]
> 現在、自動インストールに対応するのは、OpenWrt の設定がすべて初期値のルーターのみです。
> 最新の [FoxHole OpenWrt Client](https://github.com/foxhole-team/foxhole-openwrt-client) を手動でインストールしたルーターともペアリングできます。
> セットアップとペアリングには、スマートフォンとルーターが同じ LAN に接続され、ルーターからインターネットにアクセスできる必要があります。

Wi-Fi アイコンは物理的な Wi-Fi 接続のみを示し、SSID や位置情報へのアクセスは使いません。検索と新規設定・ペアリングには Wi-Fi が必要です。手動入力には利用可能な現在の Wi-Fi のプライベート IPv4 ゲートウェイが一度だけ入り、その後も編集できます。

自宅 VPN 経由で管理は独立した選択で、既存のペアリングごとに既定でオフです。公開 IP アドレスの確認（ルーターに公開 IP と受信 VPN サービスがあるという利用者の申告）と、利用可能なアクティブ VPN 経路も必要です。この確認はアドレスの割り当てやファイアウォール設定を行いません。アプリはその VPN ネットワーク経由で保存済みのプライベート接続先に、既存の固定 TLS 識別情報とペアリング認証で管理を試みます。WAN 管理ポートを開いたり、モバイル回線で新たに検索・ペアリングしたりはしません。 電話の VPN 自動切替・ハンドオフは引き続き無効です。この選択はルーター管理だけに適用されます。

認証済みのペアリング済みルーターからアプリへ直接届く受信 VPN プロファイルにのみ、独立したアプリ連携メタデータが付きます。接続 URI、設定、QR 形式は変わりません。一般のウェブ・QR・プロバイダーのエクスポートには付きません。インポートしたヒントだけでは信頼を確立できません。

自動認識には、保存済みローカルプロファイル、現在の設定、選択したペアリングの一致に加え、そのプロファイルがアプリ自身の VPN で実際に接続中であることも必要です。実際の接続ごとに時間制限付き確認を一度行い、タイマー、自動再試行、プロバイダープロファイルの定期照会はありません。ペアリングを再試行は Wi-Fi・権限・PIN を使う明示的な手順です。認証成功までは元の記録を保持し、変更された証明書の識別情報を自動承認しません。

設定には APK を使う OpenWrt 25.12.5、管理者権限、正確な対象とアーキテクチャに対応した認証済みカタログが必要です。現在のパッケージ・更新ポリシーは `mediatek/filogic` と `x86/64` を許可しますが、すべてのルーターモデルとの互換性を示すものではありません。依存関係、カーネル ABI、フラッシュ、RAM の検査も必要です。

初回インストールには FoxHole OpenWRT Client ライセンスへの明示的な同意が必要で、同意欄は最初は未選択です。別の自動更新チェックボックスは新規インストールではオンです。希望しない場合はオフにするか、後からルーター設定で変更してください。既存ルーターのペアリングでは更新設定を保持します。CLI/backend から直接インストールした場合、自動更新は既定でオフです。

アプリと LuCI からの手動更新は同じ認証付きトランザクションを使います。自動更新を有効にすると、ルーターはアプリの更新チャンネルとは別に毎日確認し、検証済み FoxHole パッケージをインストールします。プロファイル、ペアリング、選択済みプロトコルモジュールは保持され、OpenWrt ファームウェアや他のシステムパッケージは更新しません。電話のペアリング解除でもルーターの更新設定は無効になりません。

---

## ⚙️ バックグラウンドサービス

二つの独立したサービスを使用します。

| サービス | 機能 |
| --- | --- |
| **`foxhole watchdog web`** | 追加済み Web アプリの通知確認と配信。 |
| **`foxhole watchdog guard`** | イベントを監視し、インストール・削除や通信異常を保護されたログへ記録。 |

強化モードでは、接続がない間に `foxhole watchdog guard` が前景サービスを起動して常駐し、接続検出後に停止します。省電力モードでは WorkManager による定期確認を使います。

---

## 🗃️ FoxHole DB とデータセット

[![FoxHole DB](https://img.shields.io/badge/GitHub-FoxHole_DB-181717?logo=github)](https://github.com/foxhole-team/foxhole-db)

FoxHole DB は五つの独立した署名付きデータセットを提供し、各機能が必要に応じて取得します。TLS フィードはテーブルのみです。ClientHello 生成は FoxHole Core にコンパイルされており、更新に失敗しても内蔵または最後の検証済みテーブルを使い続けます。

| データセット | 成果物 | 形式 | 取得元 | ライセンス | バージョン |
| --- | --- | --- | --- | --- | --- |
| **DNS：フィルター一覧** | `adguard-dns-filter.fhds` | `foxhole-dns-fst-v1` | [AdGuard DNS filter](https://github.com/AdguardTeam/AdGuardSDNSFilter) | GPL-3.0 | ビルドごとに上流コミットを固定。 |
| **Tor：内蔵ブリッジのミラー** | `bridges.json` | `tor-bridges-json` | [Tor Project built-in bridges (Moat)](https://bridges.torproject.org/moat/circumvention/builtin) | 公開の検閲回避データ | `generated_at`。キーを正規化し、上流の配列順序を維持。 |
| **FoxHole Sentinel：脅威情報** | `threat-intel.json` | `sentinel-threat-intel-json`、文書 `schema: 3` | [AssoEchap/stalkerware-indicators](https://github.com/AssoEchap/stalkerware-indicators) | CC-BY-4.0 | 上流コミットを固定し、`threat-intel-source-info.json` に記録。 |
| **位置情報：IP → 国** | `dbip-country-ipv4.csv`, `dbip-country-ipv6.csv` | `dbip-country-csv` | [DB-IP Lite via sapics/ip-location-db](https://github.com/sapics/ip-location-db) (`dbip-country`) | CC-BY-4.0 | 上流 `package.json` の `version` をマニフェストへ転記。 |
| **TLS 指紋テーブル：ClientHello プロファイル** | `fingerprints.json` | `tls-fingerprint-tables-json` | [FoxHole Core `fingerprints/`](https://github.com/foxhole-team/foxhole-core) | GPL-3.0-or-later | 上流リビジョンを固定し、各プロファイルの適用前に `fingerprint_sha256` を再計算。 |

---

## 🔒 権限とプライバシー

- `INTERNET`：VPN、プロキシ、Tor、I2P、DNS、Web アプリ、購読、更新可能な通信データ。
- `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE`：ネットワーク状態、ルール、プロキシ。
- `ACCESS_LOCAL_NETWORK` - ルーターの検索、設定、管理に使用します。Android 17（API 37）以降でアプリの対象 API も 37 以降の場合、LAN 操作の前にシステム権限を要求します。拒否すると該当する操作は行えません。
- `FOREGROUND_SERVICE` と対応する前景サービス権限：通知を表示する、ユーザー管理の長時間ネットワークモード。
- `QUERY_ALL_PACKAGES`：分割ルーティング、ファイアウォール、隔離、DNS 除外、ローカル監査用の一覧。
- `PACKAGE_USAGE_STATS`：ユーザーが有効にし、Android の該当アクセスを必要とするローカル統計・分析のみ。
- `CAMERA`：QR の取り込み。
- `POST_NOTIFICATIONS`：通信サービス、Sentinel、Web 通知。
- `RECEIVE_BOOT_COMPLETED`：再起動後に選択モードを復元。
- `WAKE_LOCK`：必要な通信セッションの維持。

次のデータは端末内に保持されます。

- インストール済みアプリ一覧；
- ローカル統計；
- 接続とログ；
- Sentinel の結果；
- 自動接続履歴；
- DNS 除外；
- ファイアウォールポリシー；
- 経路ポリシー；
- Web アプリのローカルデータ；
- モジュールと拡張機能の設定；
- Vault メタデータ。

これらを開発者へ送信することはありません。

### 🌍 ネットワーク要求

FoxHole DB の TLS 指紋テーブル更新は初期状態で有効です。起動後に確認する場合があり、その後はネットワークがある日に毎日確認するよう予定します。設定で無効にできます。他の署名付きデータセットと保存済み購読には、それぞれ更新設定があります。

接続確認と IP 検索は Google、Cloudflare、ipwho.is、その他の代替サービスに接続します。これらのサービスは要求と使用中の公開 IP を受け取ります。機能に応じた通信には次も含まれます。

- VPN；
- Tor；
- I2P；
- HTTPS 購読更新；
- 接続確認；
- ユーザー指定 DNS；
- GeoIP/DNS/Tor ブリッジ更新；
- Web アプリ；
- 有効な Web アプリのイベント配信。

---

## 🤖 Android / ネイティブ ABI

Android 8（API 26）以降が必要です。FoxHole Core はバージョン付き C/JNI ABI で接続し、Capabilities API がビルドに組み込まれたプロトコルと機能を示します。

配布プロファイル `android4` は `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` を対象にします。ABI ごとに対応するネイティブライブラリとライセンス情報が必要です。利用できる APK アーキテクチャとファイル名は実際の公開リリースで決まります。ビルド設定だけでは実機やランタイムの動作を証明できません。

ネイティブビルドは固定した Rust/NDK ツールチェーンを使用し、ELF 検査を必要とします。

---

## 🔏 リリースの検証

所有者が署名するリリース APK には、以下の証明書を使用します。

GitHub と F-Droid は別の更新チャンネルです。GitHub ビルドはアプリ内の APK 更新確認に対応します。F-Droid ビルドは APK 自己更新を無効にし、パッケージインストール権限も含めず、F-Droid クライアントが更新します。各 APK に配送用 SBOM があります。アーキテクチャ、提供状況、バージョンは実際のリリース成果物で確認してください。レシピだけでは F-Droid ビルド成功、再現性、リポジトリへの採用を証明しません。

公式リリースの APK はチェックサムと署名証明書の検証を推奨します。

```bash
sha256sum -c SHA256SUMS
```

```bash
apksigner verify --print-certs downloaded-apk.apk
```

証明書の期待する SHA-256 指紋：

```text
e59de2486084c38f3c77e9df0eb5eff9a4559f3c68024f1208e0e9c04b0df665
```

期待する SHA-1 指紋：

```text
9e00dc6b54461a4647cb11133b394cdfc986646d
```

---

## 🔗 関連プロジェクト

[![FoxHole Core](https://img.shields.io/badge/GitHub-FoxHole_Core-181717?logo=github)](https://github.com/foxhole-team/foxhole-core)
[![Version](https://img.shields.io/github/v/release/foxhole-team/foxhole-core?label=version)](https://github.com/foxhole-team/foxhole-core/releases)

[![FoxHole DB](https://img.shields.io/badge/GitHub-FoxHole_DB-181717?logo=github)](https://github.com/foxhole-team/foxhole-db)
[![Version](https://img.shields.io/github/v/release/foxhole-team/foxhole-db?label=version)](https://github.com/foxhole-team/foxhole-db/releases)


[![FoxHole VPN](https://img.shields.io/badge/GitHub-FoxHole_VPN-181717?logo=github&style=flat-square)](https://github.com/foxhole-team/foxhole-vpn)
[![Version](https://img.shields.io/github/v/release/foxhole-team/foxhole-vpn?label=version&style=flat-square)](https://github.com/foxhole-team/foxhole-vpn/releases)

[![FoxHole OpenWrt](https://img.shields.io/badge/GitHub-FoxHole_OpenWrt-181717?logo=github&style=flat-square)](https://github.com/foxhole-team/foxhole-openwrt-client)
[![Version](https://img.shields.io/github/v/release/foxhole-team/foxhole-openwrt-client?label=version&style=flat-square)](https://github.com/foxhole-team/foxhole-openwrt-client/releases)

---

## 📄 ライセンス

FoxHole Guard と FoxHole Core の配布ライセンス：

**GNU General Public License v3.0 以降（`GPL-3.0-or-later`）**

Copyright (C) 2026 FOXHOLE TEAM.

第三者依存関係のライセンスは次に記載します。

- `THIRD_PARTY_NOTICES.md`；
- プロジェクト SBOM（`*.cdx.json`）。

依存関係とライセンスのグラフは CI で検証します。

---

## 💖 開発を支援する

私たちはAIツールの助けを借りながら、すべて自分たちの時間と資源でFoxHole Guardを開発・保守しています。皆さまのご支援により、プロジェクトにより多くの時間を充て、開発を加速できます。

[プロジェクトを支援する - 暗号資産ウォレット](#cryptocurrency-wallets)

<a id="cryptocurrency-wallets"></a>

### 🪙 暗号資産ウォレット

**XMR（Monero）：**

```text
48yBVPTdcyJ1WoJtnKmVpEZziEsDy4HvbCW7eQDS9mfdiWPFXwZ8F5h9YZ2UTTBLxPcJgQgvth7iqLZM2yMCaQ432qaouqr
```

**BTC（Bitcoin）：**

```text
bc1qatnyy7jcpqrp0d3dk9rta9vqfejgh4mysd6m2f
```

**ETH（Ethereum）：**

```text
0xDEBA357Cc8f5E865ea7FFa98E138C8241A16A465
```

---

## ⚠️ 免責事項

- Tor は The Tor Project の商標です。FoxHole Guard は同団体の製品ではなく、承認、支援、提携を受けていません。
- FoxHole Guard は AdGuard の製品ではなく、AdGuard Software Ltd. とは提携していません。
- FoxHole Guard は I2P または PurpleI2P の公式製品ではありません。
