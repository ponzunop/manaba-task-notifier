# 機能B：課題一覧・提出済み管理 設計・実装解説（担当：蘆川）

本ドキュメントは、チーム12「manaba 課題通知システム」における **機能2：課題一覧・提出済み（担当：蘆川）** の設計・コード解説・デモ発表用の説明資料です。

---

## 1. 担当範囲とファイル構成

`詳細設計書.docx`（9.2 機能2：課題一覧・提出済み）および `画面・機能・データ関係図 (1).pdf` に基づき、以下のファイルを担当・実装しました。

| 種別 | ファイルパス | 役割 |
| :--- | :--- | :--- |
| Controller | `src/main/java/com/example/manabanotifier/controller/TaskController.java` | ログイン確認、課題一覧の締切昇順取得、カウント集計、フィルタ検索、提出状態更新（提出済み/未提出）、手動課題登録 |
| View (Thymeleaf) | `src/main/resources/templates/tasks.html` | 共通ナビゲーションバー、サマリーカード、フィルタ/検索バー、緊急度バッジ・残り時間付き課題一覧テーブル、手動追加モーダル |
| 解説資料 | `docs/B_ashikawa_tasks.md` | 本ドキュメント（設計意図・連携仕様・発表説明ポイント） |

---

## 2. システム全体における位置づけと他機能との連携

```
[機能A: 大友] AuthController / TaskFetchService
   │ Gmail通知メールを解析し tasks テーブルへ未提出課題を登録・更新
   │ セッション "user_id" および Cookie "user_id" を発行して /tasks へ遷移
   ▼
[機能B: 蘆川] TaskController / tasks.html
   │ 1. tasks テーブルを user_id で検索し deadline 昇順で表示
   │ 2. 「提出済みにする」押下で tasks.is_submitted を true に更新
   │    （必要に応じて「未提出に戻す」・手動課題追加も可能）
   ▼
[機能D: 川邉] NotificationScheduler
   └─ is_submitted == false の課題のみを通知対象として抽出するため、
      提出済みに更新された課題は以降の Web Push 通知対象から自動的に除外される
```

### 共通Entity / Repository との接続
- `TaskRepository.findByUserIdOrderByDeadlineAsc(userId)`: ログインユーザーの課題を締切が近い順に取得
- `TaskRepository.findById(taskId)` / `TaskRepository.save(task)`: 提出状態の更新・新規課題の保存
- `UserRepository.findById(userId)`: ナビゲーションバーに表示するユーザー名（`displayName` / `email`）の取得

---

## 3. エンドポイント仕様（`TaskController.java`）

| HTTPメソッド | URL | 処理概要 | リダイレクト / 遷移先 |
| :--- | :--- | :--- | :--- |
| `GET` | `/tasks` | 課題一覧表示。`filter`（`all` / `unsubmitted` / `urgent` / `submitted`）、`course`（科目）、`q`（キーワード）による絞り込みと件数集計を実行 | `tasks.html`（未ログイン時は `redirect:/login`） |
| `POST` | `/tasks/{taskId}/submit` | 対象課題の `is_submitted` を `true`（`toggle=true` 指定時は反転）に更新し、以降の通知対象から除外 | `redirect:/tasks`（現在の `filter` を維持） |
| `POST` | `/tasks/{taskId}/unsubmit` | 誤操作時やデモ再試行用に、提出済み課題を `is_submitted = false` へ戻す | `redirect:/tasks`（現在の `filter` を維持） |
| `POST` | `/tasks/create` | 科目名・課題名・締切日時を手動入力して新規課題を登録（テスト・デモ用） | `redirect:/tasks` |

### セッション・セキュリティ・エラーハンドリング
1. **未ログインアクセス制御**:
   `resolveUserId()` で `HttpSession` の `"user_id"` 属性または `@CookieValue("user_id")` を確認し、未設定・空文字の場合は `redirect:/login` へ遷移します。
2. **他ユーザー課題の操作防止**:
   `POST /tasks/{taskId}/submit` および `/unsubmit` では `taskRepository.findById(taskId)` で取得した `Task` の `userId` がログイン中の `userId` と一致するかを必ず検証します。
3. **詳細設計書準拠のエラーメッセージ**:
   対象課題が存在しない場合、別ユーザーの課題の場合、または DB 例外発生時は `RedirectAttributes` で `"課題の状態更新に失敗しました"` を渡し、`/tasks` へリダイレクトして赤色のアラートバナーを表示します。

---

## 4. 画面・UI設計のポイント（`tasks.html` + `TaskView`）

### (1) 締め切り重視の表示設計（`TaskView` ビューモデル）
`TaskController` 内の静的クラス `TaskView` にて、現在時刻 `LocalDateTime.now()` との差分から以下を算出して画面へ渡しています。
- **期限超過**: 未提出かつ締切過去 → 赤色バッジ `期限超過`、`期限超過 (○日○時間前)`
- **締切間近（24時間以内）**: 未提出かつ残り24時間未満 → オレンジバッジ `締切間近`、`あと ○時間 ○分`（行背景もハイライト）
- **3日以内**: 未提出かつ残り72時間未満 → 黄色バッジ `3日以内`
- **通常未提出**: 上記以外 → 青色バッジ `未提出`
- **提出済み**: `submitted == true` → 緑色バッジ `提出済み`、課題名に取り消し線と `提出済み（通知停止）` 表示

### (2) サマリーカードと絞り込みUI
- 画面上部に **全課題 / 未提出 / 締切間近（24時間以内） / 提出済み** の4つのサマリーカードを配置し、各カードをクリックするとそのまま対応するフィルタ表示へ切り替わります。
- 科目別ドロップダウンとキーワード検索により、履修科目数が増えても目的の課題をすぐ見つけられます。

### (3) 共通ナビゲーションバーとデモ・実演導線
- **画面遷移**: `課題一覧 (/tasks)`・`通知設定 (/settings)`・`通知履歴 (/history)`・`ログアウト (/auth/logout)`
- **Gmail同期ボタン**: `POST /auth/fetch-tasks` を直接呼び出して最新メールから課題を再取得可能
- **ブラウザ通知許可ボタン**: Browser Notification API の許可状態（未設定 / 許可済み / 拒否中）をリアルタイム表示し、クリックで許可取得＋ `/auth/push-subscribe` への購読登録を実行
- **「＋ 課題を手動追加」モーダル**: 発表デモ中に「締切間近の新規課題」をその場で追加し、通知設定・スケジューラ・提出済み切替の一連の流れを実演可能

---

## 5. 発表時の説明ポイント（30秒〜1分サマリー）

1. **ログイン直後のハブ画面**: Gmail連携で自動取得された課題を「締め切りが近い順」に並べ、残り時間と緊急度バッジで出し忘れを直感的に防ぎます。
2. **ワンクリックで通知停止**: 「提出済みにする」ボタンを押すと DB の `is_submitted` が `true` に更新され、川邉さん担当の通知スケジューラが自動で通知対象から除外します。
3. **安全性とデモしやすさ**: セッション検証と所有者チェックで他人の課題変更を防ぎつつ、誤操作用の「未提出に戻す」ボタンや「課題を手動追加」モーダルにより実演テストもスムーズに行えます。
