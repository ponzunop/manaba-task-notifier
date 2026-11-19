# セキュリティ診断・脆弱性修正レポート（manaba 課題通知システム）

本ドキュメントは、チーム12で実装した「manaba 課題通知システム（Spring Boot）」に対するコード全体のセキュリティ監査で発見された脆弱性と、実施した修正内容・自動回帰テストの結果をまとめたものです。

---

## 発見・修正した脆弱性一覧（全 10 件）

| # | 深刻度 | 脆弱性カテゴリ | 該当箇所 | 修正前の問題点 | 実施した修正内容 |
| :---: | :---: | :--- | :--- | :--- | :--- |
| 1 | **Critical** | **認証バイパス / セッション固定化 / なりすましログイン** | `AuthController.java`<br>`TaskController.java`<br>`SettingController.java`<br>`HistoryController.java` | ・メールアドレスから決定論的に生成される `user_id` を署名なし Cookie に保存し、全コントローラが Cookie のみで認証・セッション昇格していたため、他人のメールアドレスから容易になりすまし可能だった<br>・ログイン時にセッションID再生成がなかった<br>・`POST /auth/oauth-token` が入力メールアドレスを未検証で信用し、`/auth/login` や `/auth/gas-json-login` でも既存連携アカウントを乗っ取り可能だった | ・署名なし `user_id` Cookie による認証を完全廃止し、サーバ側 `HttpSession`（`HttpOnly` + `SameSite=Lax` の暗号論的ランダム `JSESSIONID`）のみで認証<br>・ログイン時に `request.changeSessionId()` と CSRF トークン再生成を実施（セッション固定化対策）<br>・`/auth/oauth-token` では Google Gmail API (`/users/me/profile`) からトークン本来の所有者メールを直接検証<br>・既に `OAUTH2` / `GAS_BROWSER` / `GAS` で登録済みのアカウントに対し、未認証の簡易ログイン・JSON貼付でのなりすましを拒否 |
| 2 | **Critical** | **SSRF（サーバサイドリクエストフォージェリ） & Open Redirect** | `TaskFetchService.java`<br>`AuthController.java`<br>`SecurityUtils.java` | ・`gasUrl` が `http://` / `https://` で始まるかしか検証しておらず、`POST /auth/gas-login` では任意 URL（`169.254.169.254` や内部 LAN 等）へサーバから自動リダイレクト付き GET を送信可能だった（SSRF）<br>・`POST /auth/gas-browser-start` でも任意外部サイトへ `302` リダイレクト可能だった（Open Redirect） | ・`SecurityUtils.isValidGoogleAppsScriptUrl()` を新設し、`https://script.google.com/macros/s/.../exec` および `https://script.google.com/a/macros/<domain>/s/.../exec` のみ許可<br>・サーバ間通信用 `HttpClient` を `Redirect.NEVER` に変更し、手動リダイレクト検証で Google 公式ドメイン（`script.google.com` / `*.googleusercontent.com`）以外への遷移をすべて遮断 |
| 3 | **Critical** | **未認証 H2 SQL コンソール公開・OAuth 設定上書き・秘密ファイル権限** | `application.properties`<br>`AuthController.java`<br>`TaskFetchService.java` | ・`spring.h2.console.enabled=true`（空パスワード `sa`）により未認証で DB へ直接 SQL 実行可能だった<br>・`POST /auth/oauth-config` が未認証でグローバル OAuth 秘密鍵を上書き可能だった<br>・`config/google-oauth.properties` がデフォルト権限で保存されていた | ・`spring.h2.console.enabled=false` に無効化し、`server.address=${SERVER_ADDRESS:127.0.0.1}` で既定ループバック待受に変更<br>・`POST /auth/oauth-config` をループバック限定かつ設定済み時の未認証上書きを禁止<br>・`config/google-oauth.properties` に POSIX `0600`（所有者のみ読み書き可）を設定 |
| 4 | **High** | **CSRF（クロスサイトリクエストフォージェリ）欠如 & GET ログアウト** | `SecurityFilter.java`<br>`SecurityControllerAdvice.java`<br>全 HTML / `push-client.js` | ・すべての状態変更 `POST` エンドポイントに CSRF 検証がなく、外部サイトから課題提出状態の変更・通知設定無効化・削除・ログアウト（`GET /auth/logout`）等を強制実行可能だった | ・`SecurityFilter` で 256bit `SecureRandom` による Synchronizer Token 方式の CSRF 検証（`_csrf` / `X-CSRF-TOKEN`、定数時間比較）を全状態変更リクエストへ導入<br>・クロスサイト POST である `POST /auth/gas-callback` のみ `Origin` / `Referer` が Google Apps Script ドメイン（`*.googleusercontent.com` / `script.google.com`）または同一オリジンであることを厳格検証<br>・`/auth/logout` を `POST` 専用に変更 |
| 5 | **High** | **OAuth 2.0 `state` 検証バイパス** | `AuthController.java` | ・`if (expectedState != null && state != null && !expectedState.equals(state))` となっており、`state` パラメータを省略するか未開始セッションでアクセスすると `state` 検証が丸ごと素通りしていた | ・`expectedState` と `state` の両方が非空であり、かつ定数時間比較（`MessageDigest.isEqual`）で一致することを必須化 |
| 6 | **High** | **他ユーザー課題情報の参照可能（IDOR）** | `HistoryController.java`<br>`NotificationScheduler.java` | ・`HistoryController.resolveTask()` のフォールバックおよび `NotificationScheduler.retryNotification()` で `taskRepository.findById(taskId)` を所有者確認なしに呼び出していた | ・ログイン中ユーザー所有の `taskMap` 参照のみに限定し、`NotificationScheduler` 側でも `userId.equals(task.getUserId())` を明示検証 |
| 7 | **High** | **セキュリティ HTTP レスポンスヘッダ欠如 & Cookie 設定不備** | `SecurityFilter.java`<br>`application.properties` | ・クリックジャッキング対策・MIME スニッフィング対策・CSP・認証画面のキャッシュ抑止・Cookie `SameSite` 属性が未設定だった | ・`X-Frame-Options: DENY`、`X-Content-Type-Options: nosniff`、`Referrer-Policy`、`Permissions-Policy`、`Content-Security-Policy`、動的ページの `Cache-Control: no-store` を全レスポンスへ付与<br>・`server.servlet.session.cookie.http-only=true` / `same-site=lax` を設定 |
| 8 | **Medium** | **DOM インジェクション / URL 検証不備 / 入力サイズ上限なし（DoS）** | `history.html`<br>`push-client.js`, `sw.js`<br>`TaskController.java`<br>`TaskFetchService.java`<br>`AuthController.java` | ・`history.html` で `toast.innerHTML` を使用し、`item.url` のスキーム検証がなかった<br>・`push-client.js` / `sw.js` の遷移先 URL 検証がなかった<br>・GAS JSON ペイロードや課題入力・Push 購読情報にサイズ上限がなかった | ・`toast` 生成を安全な DOM API (`createElement` + `textContent`) に置換し、遷移先 URL を `/` 開始の内部パスのみ許可<br>・`taskUrl` を `SecurityUtils.sanitizeHttpUrl()` で厳格検証<br>・ペイロード上限（2MB / 200通）、科目名・タイトル・Push 情報の文字数上限を設定 |
| 9 | **Medium** | **エラー詳細・機密ログ漏洩** | `application.properties`<br>`AuthController.java`<br>`TaskFetchService.java`<br>`NotificationScheduler.java` | ・OAuth トークン交換失敗時のレスポンス本文や Push エンドポイント全文がログ出力され、例外メッセージが画面に表示されていた | ・`server.error.include-stacktrace=never` / `include-message=never` / `include-exception=false` を設定し、機密情報ログと生の例外メッセージ出力を削除 |
| 10 | **Medium** | **Gmail API `msgId` 検証不足** | `TaskFetchService.java` | ・Gmail API 応答の `msgId` を未検証で URI に連結していた | ・`SecurityUtils.isValidGmailMessageId()`（`^[A-Za-z0-9_-]{1,128}$`）で検証してから URI を組み立てるよう修正 |

---

## 自動セキュリティ検証テスト

[SecurityHardeningTest.java](../src/test/java/com/example/manabanotifier/SecurityHardeningTest.java) にて、以下の自動検証（`./mvnw test`）を実装・通過しています。

1. 署名なし `user_id` Cookie による認証バイパス拒否（`/tasks`, `/settings`, `/history`, `/api/notifications/recent`）
2. 全セキュリティ HTTP レスポンスヘッダ付与確認
3. CSRF トークン欠落時の `POST` リクエスト `403 Forbidden` 拒否
4. `GET /auth/logout` の `405 Method Not Allowed` 拒否
5. SSRF（`169.254.169.254` 等）および Open Redirect（外部ドメイン）の拒否と正規 Google Apps Script URL（通常・Workspace 形式）の許可
6. `POST /auth/gas-callback` における不正 `Origin` の `403` 拒否、正規 Google Apps Script `Origin` からのログイン成功、および既存連携アカウントへの未認証なりすまし拒否
7. OAuth 2.0 `state` パラメータ欠落時のコールバック拒否
