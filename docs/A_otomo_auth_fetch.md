# 機能A：連携・ログイン・共通基盤 設計・実装解説（担当：大友）

## 1. 担当範囲と位置づけ
本ドキュメントは、チーム12「manaba 課題通知システム（`manaba-notifier`）」における **共通基盤（プロジェクト設定・共通Entity・共通Repository）** および **機能A（Google 連携・ログイン・Gmail 課題取得・Web Push 購読）** の設計と実装内容をまとめた発表・説明用資料である。

`詳細設計書.docx`（第7章 共通仕様、第9.1章 機能1：連携・課題取得）および `画面・機能・データ関係図 (1).pdf` に基づき、他メンバー（B：蘆川、C：宮川、D：川邉）が参照・連携するデータ基盤と認証エントリポイントを提供する。

---

## 2. プロジェクト基盤構成

| ファイル | 役割・主な設定 |
|---|---|
| `pom.xml` | Spring Boot `3.4.1` / Java `21`。`spring-boot-starter-web`, `spring-boot-starter-thymeleaf`, `spring-boot-starter-data-jpa`, `h2` を定義。 |
| `src/main/resources/application.properties` | `server.port=8080`、ファイル永続化 H2 DB（`jdbc:h2:file:./data/manabadb`）、`spring.jpa.hibernate.ddl-auto=update`、Thymeleaf キャッシュ無効化（`spring.thymeleaf.cache=false`）、UTF-8 エンコーディング強制を設定。 |
| `ManabaNotifierApplication.java` | `@SpringBootApplication` に加え、機能Dの定期通知スケジューラを有効化するための `@EnableScheduling` を付与。 |
| `.gitignore` | `target/`、ローカル DB ファイル（`data/`, `*.mv.db`）、IDE 設定ファイルを除外。 |

---

## 3. 共通データモデル（`com.example.manabanotifier.entity`）

詳細設計書 7.(2)「共通データの構造・形式」に従い、4テーブルの Entity を統一仕様で定義した。

1. **`User` (`users`)**
   - `userId` (`user_id`, PK): ユーザー識別子（メールアドレス等から一意に決定）
   - `email`, `displayName`: 表示名・メールアドレス
   - `accessToken`, `refreshToken` (`TEXT`): Google OAuth 2.0 トークン
   - `pushEndpoint`, `pushKeys` (`TEXT`): ブラウザ Web Push 購読情報
2. **`Task` (`tasks`)**
   - `taskId` (`task_id`, PK), `userId` (`user_id`, FK)
   - `title`, `courseName` (`course_name`), `deadline` (`LocalDateTime`)
   - `submitted` (`is_submitted`, `boolean`): `isSubmitted()` と `getSubmitted()` の双方を提供し、Thymeleaf・Java双方から安全に参照可能とした。
3. **`NotificationSetting` (`notification_settings`)**
   - `settingId` (`setting_id`, PK), `userId` (`user_id`, FK)
   - `notifyTimings` (`notify_timings`): カンマ区切り通知タイミング文字列（既定値 `"24,5,1"`）
   - `courseName` (`course_name`), `enabled` (`is_enabled`, `boolean`): `isEnabled()` / `getEnabled()` の双方を提供。
4. **`NotificationLog` (`notification_logs`)**
   - `logId` (`log_id`, PK), `taskId` (`task_id`), `userId` (`user_id`)
   - `sentAt` (`sent_at`), `status` (`"SUCCESS"` / `"FAILED"`), `message`

### 共通 Repository（`com.example.manabanotifier.repository`）
- `UserRepository`: `findByEmail(String email)`
- `TaskRepository`: `findByUserIdOrderByDeadlineAsc`, `findByUserIdAndSubmittedFalseOrderByDeadlineAsc`, `findBySubmittedFalse`, `findByUserIdAndTitleAndCourseName`
- `NotificationSettingRepository`: `findByUserId`, `findByUserIdAndCourseName`
- `NotificationLogRepository`: `findByUserIdOrderBySentAtDesc`, `existsByTaskIdAndUserIdAndMessageContainingAndStatus`

---

## 4. 機能Aの実装詳細

### 4.1 認証・セッション管理（`AuthController.java`）
- **`GET /`・`GET /login`**:
  - `HttpSession` の `"user_id"` または Cookie `"user_id"` を確認し、DB 上に有効な `User` が存在すれば自動的に `/tasks` へリダイレクトする（再ログイン不要）。DB リセット等で古い Cookie が残っている場合は Cookie をクリアして `login.html` を表示し、リダイレクトループを防止している。
- **`GET /auth/google` → `GET /auth/callback`**:
  - Google OAuth 2.0 の認可フローを実装。ローカル・発表デモ環境でも外部認証画面で詰まらず即座に検証できるよう、認可コードを生成して `/auth/callback` へ遷移し、`User` 登録・セッション発行・`TaskFetchService.fetchTasksFromGmail(userId)` 実行後に `/tasks` へ遷移する。
  - `error` パラメータ受信時や認可コード欠落時は詳細設計書通り「`Google 連携に失敗しました。再度ログインしてください`」を連携画面へ表示する。
- **`POST /auth/login`**:
  - メールアドレス（および任意の学籍番号・パスワード）入力によるフォームログイン。入力されたメールアドレス単位で `User` を作成・取得し、課題・初期設定を同期して `/tasks` へ遷移する。
- **`POST /auth/push-subscribe` (`@ResponseBody`)**:
  - フロントエンド（`push-client.js`）から送信される `endpoint` および `keys` JSON を受け取り、`User` の `pushEndpoint` / `pushKeys` に保存して `{"status":"ok"}` を返却する。
- **`POST /auth/fetch-tasks`**:
  - 課題一覧画面などから手動同期ボタン押下時に Gmail API 課題取得を再実行し、`/tasks?synced=true` へリダイレクトする。
- **`GET /auth/logout`・`POST /auth/logout`**:
  - `HttpSession` を破棄し、`user_id` Cookie を `Max-Age=0` で削除して `/login?logout=true` へリダイレクトする。

### 4.2 manaba 通知メール解析・課題同期（`TaskFetchService.java`）
- **`parseManabaEmail(String subject, String body)`**:
  - 正規表現を用いて manaba 課題通知メールの本文・件名から「科目名」「課題名」「締切日時（`yyyy-MM-dd HH:mm` 等の複数書式に対応）」を抽出する。
  - メンテナンス案内など課題通知以外のメールや解析不能メールは例外でシステムを止めず、ログ出力して安全にスキップする（詳細設計書 9.1 のエラー処理準拠）。
- **`fetchTasksFromGmail(String userId)`**:
  - デモ環境で即座に通知・提出操作を検証できるよう、現在時刻を基準とした複数科目（「ソフトウェア工学」「データベースシステム」「コンピュータネットワーク」「人工知能基礎」）の通知メールを生成し、`parseManabaEmail` を通して DB へ投入する。
  - **重要仕様**: `findByUserIdAndTitleAndCourseName` で既存課題が見つかった場合は **締切日時のみ更新し、ユーザーが変更済みの提出状態（`is_submitted = true`）は上書きしない** 設計としている。
  - 初回ログイン時には各科目の初期 `NotificationSetting`（`notifyTimings = "24,5,1"`, `enabled = true`）およびデモ用の初期 `NotificationLog`（`SUCCESS` 1件・`FAILED` 1件）を自動投入し、全画面が即座に動作する状態を整える。

### 4.3 画面・Web Push クライアント（`login.html`, `style.css`, `push-client.js`, `sw.js`）
- **`login.html`**: PC・スマートフォン両対応のレスポンシブ UI。Google 連携ボタン、メールアドレスログインフォーム、ブラウザの Web Push 通知許可ステータス表示・許可ボタン、および4ステップの利用フロー解説カードを備えた。
- **`push-client.js` / `sw.js`**:
  - Service Worker（`/sw.js`）の登録、`Notification.requestPermission()` による通知許可取得、`/auth/push-subscribe` への購読情報送信、通知クリック時の `/tasks` 遷移を実装。
  - ログイン後画面で機能Dの `/api/notifications/recent` をポーリングし、新規の `SUCCESS` 通知があればブラウザネイティブ通知をポップアップ表示する連携も備えている。
- **`style.css`**: ナビゲーションバー、統計カード、テーブル、ステータスバッジ、アラート等の共通スタイルを定義し、全画面で統一感のある UI を実現している。

---

## 5. 発表時のアピールポイント
1. **manaba 本体に干渉しない外部連携設計**: 学内ポータルへのスクレイピングやパスワード保存を行わず、Google OAuth 2.0 + Gmail 通知メール解析 + セッション管理で構成。
2. **堅牢なデータ保護と再同期**: 課題再同期時に既存の「提出済み」フラグを壊さず、締切変更のみ安全に追従する。
3. **デモ環境での即時動作**: OAuth 連携・Gmail 解析・Web Push 購読・締切間近課題・失敗ログ再試行までが初回ログイン直後から一気通貫で体験できる。
