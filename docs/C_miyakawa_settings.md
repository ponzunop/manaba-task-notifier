# C: 通知設定機能（担当：宮川）設計・実装解説資料

## 1. 担当範囲の概要

本機能（機能3：通知設定）は、ログイン中の学生が **「課題の締切何時間前に通知を受け取るか（複数タイミング）」** と **「どの科目の通知を受け取るか（科目別 ON / OFF）」** を設定・保存するための画面およびバックエンド処理を担当する。

### 担当ファイル一覧
| 種別 | パス | 役割 |
| :--- | :--- | :--- |
| Controller | `src/main/java/com/example/manabanotifier/controller/SettingController.java` | 通知設定画面の表示・入力検証・DB登録/更新・科目手動追加/削除 |
| View (Thymeleaf) | `src/main/resources/templates/settings.html` | 通知時刻チェックボックス・カスタム入力・科目別トグルUI・Web Pushテスト |
| ドキュメント | `docs/C_miyakawa_settings.md` | 本資料（設計・実装解説・第7回課題からの設計変更点） |

---

## 2. 第7回事後課題からの設計変更点（重要ポイント）

`ソフトウェア工学_第7回事後課題.txt` で整理した課題・変更点を、詳細設計書 Ver.1 および他担当（特に D: 川邉さんの `NotificationScheduler`）との連携仕様に落とし込んだ。

### (1) 単一時刻 → 複数タイミング保持への変更
* **変更前**: 通知時刻を「単一の指定時刻」として保持する想定。
* **変更後**: 「締切24時間前・5時間前・1時間前」のように **複数の通知タイミング** を選択・保持できる仕様に変更。
* **変更理由**: 要件である「段階的な事前リマインド（出し忘れ・提出忘れ防止）」を実現するため。

### (2) `NotificationScheduler`（川邉担当）とのデータ連携形式の確定
第7回課題で相談事項としていた「複数通知時刻の受け渡し形式」について、共通Entity `NotificationSetting` の `notifyTimings` カラムを **「降順のカンマ区切り整数文字列（時間単位）」**（例：`"24,5,1"`）に統一した。
* `SettingController` 側で重複排除・範囲検証（1〜168時間）・降順ソートして `"24,5,1"` 形式に正規化して保存する。
* `NotificationScheduler` 側は `notifyTimings.split(",")` で整数（時間）にパースするだけでよく、JSONパーサや追加の結合テーブルを増やさずにシンプルかつ確実に連携できる。

---

## 3. エンドポイントと処理フロー

### 3.1 セッション・認証チェック（共通仕様）
全エンドポイント先頭で `resolveUserId(HttpSession, Cookie)` を実行し、`HttpSession` の `"user_id"`（または Cookie `"user_id"`）を取得する。未ログイン時は `redirect:/login` へ遷移し、他ユーザーの設定へのアクセスを防止する。

### 3.2 `GET /settings`（設定画面表示）
1. `ensureCourseSettings(userId)` を呼び出し、現在の `NotificationSetting` 一覧を取得する。
2. 同時に `TaskRepository.findByUserIdOrderByDeadlineAsc(userId)` から取得した課題の `courseName`（重複なし）を確認し、まだ `NotificationSetting` が存在しない新規科目があれば初期レコード（`enabled = true`, `notifyTimings = 既存の通知時刻または "24,5,1"`）を自動補完する。
3. `notifyTimings` を整数セットにパースし、プリセット（48, 24, 12, 5, 3, 2, 1時間前）のチェック状態と、プリセット外のカスタム時間テキストをモデルに渡して `settings.html` を描画する。

### 3.3 `POST /settings`（通知設定の検証・保存）
1. チェックボックス（`timings`）および自由入力欄（`customTiming`）から送信された通知時間を `parseSubmittedTimings(...)` で検証する。
   * **バリデーション条件**: 1〜168 の整数のみ許可。1件も選択・入力されていない場合や、数値以外・範囲外の値が含まれる場合はエラーとする。
   * **エラー時処理**: 詳細設計書 9.3 に従い **「通知時刻を正しく設定してください」** を表示し、DB は更新せず、ユーザーが入力・操作したチェック状態やトグル状態を保持したまま画面を再描画する。
2. 正常時は重複排除・降順ソートしてカンマ区切り文字列（例：`"24,5,1"`）に正規化する。
3. 対象ユーザーの全 `NotificationSetting` レコードの `notifyTimings` と各科目の `enabled`（`enabledSettingIds` に含まれるか）を更新し、`notificationSettingRepository.saveAll(settings)` で一括保存する（課題未取得の初期状態でも保存できるよう、レコードが空の場合は `"全体共通"` として保存）。
4. PRG（Post/Redirect/Get）パターンで `/settings` へリダイレクトし、完了メッセージ **「通知設定を保存しました」** を表示する。

### 3.4 補助機能（デモ・運用向け）
* `POST /settings/courses`: まだ課題メールが届いていない科目を手動で追加登録できる。
* `POST /settings/courses/{settingId}/delete`: 本人所有の科目設定のみ検証付きで削除できる。
* ブラウザ Web Push テストボタン（`settings.html` 内 JavaScript）: `Notification` API を用いてブラウザ通知のポップアップ表示をその場で実演できる。

---

## 4. 画面設計（`settings.html`）のポイント

1. **共通ナビゲーション**: 他画面（`tasks.html` / `history.html`）と共通のメニュー（課題一覧 `/tasks`・通知設定 `/settings`・通知履歴 `/history`・Gmail同期 `POST /auth/fetch-tasks`・通知許可・ログアウト `/auth/logout`）を配置。
2. **サマリー表示**: 現在の通知タイミング（例：`24時間前 / 5時間前 / 1時間前` と DB 保存値 `24,5,1`）、通知 ON の科目数、Web Push 許可状態を一覧表示。
3. **クイック操作ボタン**:
   * 「標準（24・5・1時間前）」「余裕あり」「すべて解除（バリデーションエラー実演用）」
   * 「全科目 ON」「全科目 OFF」
4. **HTML構造上の安全性**: 科目削除フォームをメインの `<form th:action="@{/settings}">` の外側に配置し、`form="delete-course-{id}"` 属性で紐づけることで `<form>` のネスト（HTML仕様違反）を回避している。

---

## 5. 発表時のデモ手順案

1. 課題一覧画面（`/tasks`）から上部ナビの「通知設定」をクリックして `/settings` へ移動する。
2. まず「すべて解除」→「通知設定を保存する」を押し、バリデーションエラー **「通知時刻を正しく設定してください」** が出ることを確認する。
3. 「24時間前」「5時間前」「1時間前」を選択し、任意でカスタム時間に `6` を入力、さらに特定科目のトグルを `OFF` にして保存し、**「通知設定を保存しました」** とサマリー表示（例：`24,6,5,1`）が反映されることを示す。
4. 「Web Push テスト通知を送信」ボタンで通知ポップアップを表示し、最後に通知履歴画面（`/history`）のテスト機能へ繋げる。
