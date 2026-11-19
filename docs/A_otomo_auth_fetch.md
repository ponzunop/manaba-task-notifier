# 機能A：連携・ログイン・共通基盤 設計・実装解説（担当：大友）

## 1. 担当範囲と位置づけ
本ドキュメントは、チーム12「manaba 課題通知システム（`manaba-notifier`）」における **共通基盤（プロジェクト設定・共通Entity・共通Repository）** および **機能A（Google OAuth 2.0 連携・Google Apps Script 連携・実 Gmail 小テストメール解析・Web Push 購読）** の設計と実装内容をまとめた資料である。

大学 Google Workspace（`@chibatech.ac.jp` 等）ではアプリパスワード（IMAP）が利用できない・利用を避けたいケースが多いため、**アプリパスワードを一切使用せず**、以下の **2大連携方式＋メール本文/JSON 貼り付け** により実 Gmail から直接 manaba 小テスト・課題情報を取得する設計としている。

---

## 2. プロジェクト基盤構成

| ファイル | 役割・主な設定 |
|---|---|
| `pom.xml` | Spring Boot `3.4.1` / Java `21`。`spring-boot-starter-web`, `spring-boot-starter-thymeleaf`, `spring-boot-starter-data-jpa`, `h2` を定義（メール外部通信には Java 標準 `java.net.http.HttpClient` を使用し、不要な IMAP 依存は持たない）。 |
| `src/main/resources/application.properties` | `server.port=8080`、ファイル永続化 H2 DB（`jdbc:h2:file:./data/manabadb`）、`spring.jpa.hibernate.ddl-auto=update`、Thymeleaf キャッシュ無効化（`spring.thymeleaf.cache=false`）を設定。 |
| `ManabaNotifierApplication.java` | `@SpringBootApplication` に加え、機能Dの定期通知スケジューラを有効化するための `@EnableScheduling` を付与。 |
| `.gitignore` | `target/`, `data/`, `*.mv.db`, および画面保存するローカル OAuth 秘密鍵ファイル `config/google-oauth.properties` を除外。 |

---

## 3. 共通データモデル（`com.example.manabanotifier.entity`）

1. **`User` (`users`)**
   - `userId` (`user_id`, PK), `email`, `displayName`
   - `accessToken`, `refreshToken` (`TEXT`): Google OAuth 2.0 トークン
   - `gasUrl` (`TEXT`), `gasToken`: Google Apps Script (GAS) ウェブアプリ連携情報
   - `authType`: `"OAUTH2"` / `"GAS"` / `"MANUAL"`
   - `pushEndpoint`, `pushKeys` (`TEXT`): ブラウザ Web Push 購読情報
2. **`Task` (`tasks`)**
   - `taskId` (`task_id`, PK), `userId` (`user_id`, FK)
   - `title`, `courseName` (`course_name`), `openTime` (`open_time`), `deadline` (`LocalDateTime`)
   - `taskType` (`task_type`, 既定 `"小テスト"`), `taskUrl` (`task_url`)
   - `submitted` (`is_submitted`, `boolean`): `isSubmitted()` / `getSubmitted()` 双方を提供。
3. **`NotificationSetting` (`notification_settings`)**
   - `settingId` (`setting_id`, PK), `userId` (`user_id`, FK), `notifyTimings` (`"24,5,1"`), `courseName`, `enabled` (`is_enabled`)
4. **`NotificationLog` (`notification_logs`)**
   - `logId` (`log_id`, PK), `taskId`, `userId`, `sentAt`, `status` (`"SUCCESS"` / `"FAILED"`), `message`

---

## 4. 機能Aの実装詳細

### 4.1 2つのアプリパスワード不要 Gmail 連携方式（`AuthController.java` / `TaskFetchService.java`）

#### 方法①：Google OAuth 2.0 + Gmail REST API（設計書準拠）
- **`POST /auth/oauth-config`**: ログイン画面から Google Cloud Console で発行した `clientId` と `clientSecret` を `config/google-oauth.properties` に保存し、サーバ再起動なしで即座にメモリ反映する。
- **`GET /auth/google` → `GET /auth/callback`**: `https://accounts.google.com/o/oauth2/v2/auth`（スコープ `https://www.googleapis.com/auth/gmail.readonly openid email profile`）へリダイレクトし、認可コードを `https://oauth2.googleapis.com/token` で `access_token` と `refresh_token` に交換、Gmail REST API から実メールを取得する。
- **リフレッシュトークン自動更新**: `TaskFetchService` で Gmail API 呼び出し時に `401` が返った場合、`refreshToken` と OAuth 設定があれば自動で `grant_type=refresh_token` によりトークンを再取得して再試行する。
- **`POST /auth/oauth-token`**: Google OAuth 2.0 Playground 等で取得したアクセストークン（`ya29...`）を直接入力して即時連携する補助フォームも備えている。

#### 方法②：Google Apps Script (GAS) ウェブアプリ連携（Google Cloud 登録不要）
- **`POST /auth/gas-login`**: ログイン画面に表示されたコピー用 GAS コードをユーザー自身の Google アカウントでウェブアプリとしてデプロイし、その URL と同期トークンを入力してログインする。
- **`TaskFetchService.fetchTasksFromGas(...)`**:
  - `HttpClient` に `.followRedirects(HttpClient.Redirect.NORMAL)` を設定し、`script.google.com` → `script.googleusercontent.com` の `302` リダイレクトを追従。
  - `?token=...` を付与して GET し、`{ "email": "...", "emails": [ { "subject", "body", "date" } ] }` を解析・登録する。
  - 組織制限等で HTML（Google ログイン画面）が返された場合は、原因と代替手段（GAS エディタで `exportManabaJson()` を実行して出力 JSON を貼り付ける方法、または Google OAuth 2.0）を分かりやすく案内する。

#### 補助：スキップログイン & メール本文 / GAS JSON 貼り付け
- **`POST /auth/login`**: メールアドレスのみ入力（パスワード欄なし）でログインし、メール本文貼り付けや手動登録へ進める。
- **`POST /auth/import-email`**: 通常の manaba 通知メール本文（1通／複数通）だけでなく、`{` または `[` で始まる **GAS `exportManabaJson()` 出力 JSON** も自動判定して一括取り込みする。
- **`POST /auth/fetch-tasks`**: ユーザーの `authType`（`OAUTH2` / `GAS`）に応じて適切な実メール同期を再実行する。

### 4.2 manaba 小テスト通知メール解析ロジック（`TaskFetchService.parseManabaEmail`）
- **対応フォーマット**:
  - `[コース名] : ...`, `[小テスト名] : ...`, `[課題名] : ...`, `[受付開始日時] : ...`, `[受付終了日時] : ...`, `PC : https://...`
  - `■コース名：...`, `受付期間：... ～ ...`（開始・終了両方）、曜日カッコ付き（`2026/11/18(水) 23:59`）、`2026年11月18日 23時59分`
- **データ保護**: ダミーデータの自動投入は一切行わず、再同期時も既存の `is_submitted = true` 状態を上書きしない。

### 4.3 画面・Web Push クライアント（`login.html`, `style.css`, `push-client.js`, `sw.js`）
- アプリパスワードの入力欄・文言を廃し、OAuth 2.0（設定パネル＋Playground トークン入力付き）と GAS 連携（1クリックコピー用 GAS コード付き）、および Web Push 通知許可ステータス表示を分かりやすく配置した。
