# 機能A：連携・ログイン・共通基盤 設計・実装解説（担当：大友）

## 1. 担当範囲と位置づけ
本ドキュメントは、チーム12「manaba 課題通知システム（`manaba-notifier`）」における **共通基盤（プロジェクト設定・共通Entity・共通Repository）** および **機能A（ブラウザ経由 GAS「自分のみ」連携・Google OAuth 2.0 連携・manaba 小テストメール解析・Web Push 購読）** の設計と実装内容をまとめた資料である。

大学 Google Workspace（`@chibatech.ac.jp` 等）の組織制限により、以下の制約が存在する環境でも確実に実 Gmail から小テスト情報を取得できるよう設計した：
1. アプリパスワード・Google Cloud OAuth クライアント ID 作成（HTTPS 制約含む）が制限されている
2. GAS ウェブアプリの「アクセスできるユーザー：**全員**」が選択できない（「**自分のみ**」のみ選択可能）

---

## 2. プロジェクト基盤構成

| ファイル | 役割・主な設定 |
|---|---|
| `pom.xml` | Spring Boot `3.4.1` / Java `21`。`spring-boot-starter-web`, `spring-boot-starter-thymeleaf`, `spring-boot-starter-data-jpa`, `h2` を定義。 |
| `src/main/resources/application.properties` | `server.port=8080`、ファイル永続化 H2 DB（`jdbc:h2:file:./data/manabadb`）、`spring.jpa.hibernate.ddl-auto=update`、Thymeleaf キャッシュ無効化を設定。 |
| `ManabaNotifierApplication.java` | `@SpringBootApplication` に加え、機能Dの定期通知スケジューラを有効化するための `@EnableScheduling` を付与。 |
| `.gitignore` | `target/`, `data/`, `*.mv.db`, および画面保存するローカル OAuth 秘密鍵ファイル `config/google-oauth.properties` を除外。 |

---

## 3. 共通データモデル（`com.example.manabanotifier.entity`）

1. **`User` (`users`)**
   - `userId` (`user_id`, PK), `email`, `displayName`
   - `accessToken`, `refreshToken` (`TEXT`): Google OAuth 2.0 トークン
   - `gasUrl` (`TEXT`), `gasToken`: Google Apps Script (GAS) ウェブアプリ URL / トークン
   - `authType`: `"GAS_BROWSER"` / `"GAS_JSON"` / `"OAUTH2"` / `"GAS"` / `"MANUAL"`
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

### 4.1 ブラウザ経由 GAS 連携（アクセス権限：「自分のみ」で動作・最推奨）

従来の「Spring Boot サーバ → GAS URL 直接アクセス」方式では、サーバ側が Google にログインしていないため GAS を「全員」に公開する必要があった。  
本システムではこれを解決するため、**既に Google にログイン済みのユーザー自身のブラウザを経由する設計**を採用している：

1. **`POST /auth/gas-browser-start`** (`AuthController.java`)
   - ログイン画面で入力された GAS ウェブアプリ URL（`https://script.google.com/macros/s/.../exec`）と任意メールアドレスを `HttpSession`（`pending_gas_url`, `pending_email`）へ保持。
   - 実行中オリジンから `callback = <origin>/auth/gas-callback` を組み立て、ブラウザを `gasUrl?callback=...&gasUrl=...` へ `302` リダイレクトする。
2. **GAS 側 (`doGet(e)`)**
   - ユーザー自身のブラウザ権限で実行されるため、「アクセスできるユーザー：**自分のみ**」のまま動作する。
   - `GmailApp.search('from:manaba OR "do-not-reply@manaba.jp" OR (manaba 小テスト) OR manaba', 0, 40)` で Gmail を検索し、`email`, `gasUrl`, `emails[]` を JSON 化する。
   - `HtmlService` で `<form id="syncForm" method="POST" action="<callback>" target="_top"><input type="hidden" name="payload" value="..."></form>` を出力し、自動送信（＋手動送信ボタン・「JSONをコピー」ボタン）により `POST /auth/gas-callback` へフォーム送信する（トップレベル POST のため CORS 制約を受けない）。
3. **`POST /auth/gas-callback`** (`AuthController.java`)
   - `payload` JSON から `email` / `gasUrl` を抽出して `User`（`authType = "GAS_BROWSER"`）を upsert し、セッションと Cookie を発行する。
   - `TaskFetchService.importFromGasPayload(userId, payload)` を実行し、スキャン件数・登録件数・解析できなかった manaba メールサンプル（`lastSyncScannedCount`, `lastSyncSyncedCount`, `lastSyncSkippedSamples`）をセッションへ保存して `/tasks?synced=true` へリダイレクトする。
4. **`POST /auth/fetch-tasks` でのワンクリック再同期**
   - `authType = "GAS_BROWSER"` のユーザーが課題一覧画面等から「Gmailから再同期」を押すと、保存済みの `gasUrl` へ自動でブラウザリダイレクトし、最新メールを再取得する。

### 4.2 補助連携方式（Google OAuth 2.0 / GAS JSON 貼り付け / スキップログイン）
- **Google OAuth 2.0 + Gmail API** (`GET /auth/google` → `/auth/callback`, `POST /auth/oauth-config`, `POST /auth/oauth-token`): `config/google-oauth.properties` への動的設定保存、および `401` 時の `refresh_token` 自動更新に対応。
- **GAS JSON 直接ログイン** (`POST /auth/gas-json-login`) & **メール本文/JSON 貼り付け** (`POST /auth/import-email`): ブラウザ遷移が利用できない場合でも `exportManabaJson()` 出力やメール本文から一括取り込み可能。
- **スキップログイン** (`POST /auth/login`): メールアドレスのみでログインして手動登録・貼り付けを利用可能。

### 4.3 manaba 小テスト通知メール解析強化（`TaskFetchService.parseManabaEmail`）
- `[コース名] : ...`, `[小テスト名] : ...`, `[受付開始日時] : ...`, `[受付終了日時] : ...` などの角括弧形式に加え、`■コース名：...`, `科目名：...`, `公開開始：...`, `公開終了：...`, `締切：...`, `受付期間：... ～ ...`（`から`・`まで`含む）に対応。
- ラベルが見当たらない manaba メールでも、本文中の日時を2件見つければ「1件目=公開時間・2件目=締切時間」、1件なら「締切時間」としてフォールバック抽出し、件名からタイトル・科目名を補完する。
- さらに manaba 関連メールでありながら自動解析できなかったものは `ImportResult.skippedSamples` に最大5件記録し、画面上のデバッグパネルで形式を確認できるようにした。
