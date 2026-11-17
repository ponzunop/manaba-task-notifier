# 機能B：小テスト・課題一覧・提出/受験済み管理 設計・実装解説（担当：蘆川）

本ドキュメントは、チーム12「manaba 課題通知システム」における **機能2：課題一覧・提出済み（担当：蘆川）** の設計・コード解説・デモ発表用の説明資料です。

---

## 1. 担当範囲とファイル構成

`詳細設計書.docx`（9.2 機能2：課題一覧・提出済み）および `画面・機能・データ関係図 (1).pdf` に基づき、以下のファイルを担当・実装しました。

| 種別 | ファイルパス | 役割 |
| :--- | :--- | :--- |
| Controller | `src/main/java/com/example/manabanotifier/controller/TaskController.java` | ログイン確認、小テスト・課題一覧の締切昇順取得、種別/公開時間/締切/URL対応、カウント集計、フィルタ検索、受験/提出状態更新、手動登録 |
| View (Thymeleaf) | `src/main/resources/templates/tasks.html` | 共通ナビゲーションバー、Gmail同期・メール本文貼り付け・手動追加バー、サマリーカード、種別バッジ/公開時間/締切時間/リンク付き一覧テーブル、各モーダル |
| 解説資料 | `docs/B_ashikawa_tasks.md` | 本ドキュメント（設計意図・連携仕様・発表説明ポイント） |

---

## 2. システム全体における位置づけと他機能との連携

```
[機能A: 大友] AuthController / TaskFetchService
   │ Gmail (IMAPS / Gmail API) またはメール本文貼り付けから manaba 小テスト通知を解析
   │ 科目名・小テスト名・公開時間 (openTime)・締切時間 (deadline)・URL (taskUrl)・種別 (taskType) を保存
   │ セッション "user_id" および Cookie "user_id" を発行して /tasks へ遷移
   ▼
[機能B: 蘆川] TaskController / tasks.html
   │ 1. tasks テーブルを user_id で検索し deadline 昇順で表示
   │ 2. 種別バッジ（小テスト等）・科目名・小テスト名（manabaリンク）・公開時間・締切時間＋残り時間を一覧表示
   │ 3. 「受験済みにする / 提出済みにする」押下で tasks.is_submitted を true に更新
   ▼
[機能D: 川邉] NotificationScheduler
   └─ is_submitted == false の項目のみを通知対象として抽出するため、
      受験/提出済みに更新された項目は以降の Web Push 通知対象から自動的に除外される
```

### 共通Entity / Repository との接続
- `Task` フィールド参照: `taskId`, `userId`, `taskType`（`小テスト` 等）, `courseName`, `title`, `openTime`（公開時間 / 受付開始日時）, `deadline`（締切時間 / 受付終了日時）, `taskUrl`（manaba URL）, `submitted`
- `TaskRepository.findByUserIdOrderByDeadlineAsc(userId)`: ログインユーザーの項目を締切が近い順に取得
- `TaskRepository.findById(taskId)` / `TaskRepository.save(task)`: 受験/提出状態の更新・手動登録項目の保存
- `UserRepository.findById(userId)`: ナビゲーションバーに表示するユーザー名および連携メールアドレスの取得

---

## 3. エンドポイント仕様（`TaskController.java`）

| HTTPメソッド | URL | 処理概要 | リダイレクト / 遷移先 |
| :--- | :--- | :--- | :--- |
| `GET` | `/tasks` | 一覧表示。`filter`（`all` / `unsubmitted` / `urgent` / `submitted`）、`course`（科目）、`q`（キーワード）による絞り込みと件数集計を実行 | `tasks.html`（未ログイン時は `redirect:/login`） |
| `POST` | `/tasks/{taskId}/submit` | 対象項目の `is_submitted` を `true`（`toggle=true` 指定時は反転）に更新し、以降の通知対象から除外 | `redirect:/tasks`（現在の `filter` を維持） |
| `POST` | `/tasks/{taskId}/unsubmit` | 誤操作時などに、受験/提出済み項目を `is_submitted = false` へ戻す | `redirect:/tasks`（現在の `filter` を維持） |
| `POST` | `/tasks/create` | 種別（`taskType`）・科目名・小テスト/課題名・公開時間（`openTime`）・締切時間（`deadline`）・manaba URL（`taskUrl`）を手動入力して登録 | `redirect:/tasks` |

### セッション・セキュリティ・エラーハンドリング
1. **未ログインアクセス制御**:
   `resolveUserId()` で `HttpSession` の `"user_id"` 属性または `@CookieValue("user_id")` を確認し、未設定・空文字の場合は `redirect:/login` へ遷移します。
2. **他ユーザー課題の操作防止**:
   `POST /tasks/{taskId}/submit` および `/unsubmit` では `taskRepository.findById(taskId)` で取得した `Task` の `userId` がログイン中の `userId` と一致するかを必ず検証します。
3. **URL・日時バリデーション**:
   `taskUrl` は `http://` または `https://` で始まる値のみをリンクとして扱い、公開時間 `openTime` が締切時間 `deadline` より後の場合はエラーメッセージを表示します。
4. **詳細設計書準拠のエラーメッセージ**:
   対象項目が存在しない場合、別ユーザーの項目である場合、または DB 例外発生時は `RedirectAttributes` で `"課題の状態更新に失敗しました"` を渡し、`/tasks` へリダイレクトして赤色のアラートバナーを表示します。

---

## 4. 画面・UI設計のポイント（`tasks.html` + `TaskView`）

### (1) 一覧テーブルの表示項目
- **種別バッジ**: `小テスト` をメイン表示（`アンケート` / `レポート` 等にも対応）
- **科目名**: コース名をピル表示
- **小テスト名 / 課題名**: `taskUrl` がある場合は `↗ manaba` リンクバッジを表示し、直接 manaba へ遷移可能
- **公開時間（受付開始）**: `openTime` があれば `yyyy/MM/dd HH:mm`、未設定時は `-`
- **締切時間（受付終了）**: `deadline`（`yyyy/MM/dd HH:mm`）と現在時刻からの残り時間を表示
- **状態・操作**: 緊急度バッジ（`期限超過` / `締切間近` / `公開前` / `3日以内` / `受付中` / `受験済`）と「受験済みにする / 提出済みにする」ボタン（完了後は「未受験に戻す / 未提出に戻す」）

### (2) 上部アクションバーと2種類のモーダル
- **「Gmailから最新の小テスト通知メールを取得」**: `POST /auth/fetch-tasks` を実行し、実際の Gmail から manaba 通知を再取得
- **「manaba通知メール本文を貼り付けて追加」モーダル**: `POST /auth/import-email`（`rawText`）へメール本文を送信し、自動解析して登録
- **「＋ 小テストを手動追加」モーダル**: `POST /tasks/create` へ種別・科目名・小テスト名・公開時間・締切時間・URL を送信して登録

### (3) 共通ナビゲーションバー
- **画面遷移**: `小テスト・課題一覧 (/tasks)`・`通知設定 (/settings)`・`通知履歴 (/history)`・`ログアウト (/auth/logout)`
- **ブラウザ通知許可ボタン**: Browser Notification API の許可状態を表示し、クリックで許可取得＋ `/auth/push-subscribe` への購読登録を実行

---

## 5. 発表時の説明ポイント（30秒〜1分サマリー）

1. **実データ準拠の小テスト一覧**: 実際の Gmail から取得した manaba 小テストの「科目名」「公開時間」「締切時間」「manaba URL」を締切が近い順に一覧表示します。
2. **ワンクリックで通知停止**: 「受験済みにする / 提出済みにする」ボタンを押すと DB の `is_submitted` が `true` に更新され、川邉さん担当の通知スケジューラが自動で通知対象から除外します。
3. **柔軟な取り込みと安全性**: Gmail 自動取得に加えて「通知メール本文貼り付け」と「公開時間・締切時間指定の手動追加」を備え、セッション検証と所有者チェックで他人のデータ操作を防いでいます。
