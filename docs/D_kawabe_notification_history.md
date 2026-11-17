# 機能D：通知送信・通知履歴／再試行 設計・実装解説資料（担当：川邉）

## 1. 担当範囲と詳細設計書との対応

本ドキュメントは、チーム12「manaba 課題通知システム」における **機能D（川邉担当）** の設計・実装・データ連携仕様をまとめた発表・説明用資料である。

| 詳細設計書の章 | 機能名 | 担当クラス / 画面 | 主な役割 |
| :--- | :--- | :--- | :--- |
| **9.4 機能4** | 通知送信（バックグラウンド自動実行） | `NotificationScheduler.java` | 未提出課題と通知設定を定期チェックし、Web Push 通知を送信・履歴記録 |
| **9.5 機能5** | 通知履歴・再試行 | `HistoryController.java` / `history.html` | 通知結果の一覧表示、失敗分（`FAILED`）の再試行、リアルタイム通知受信表示 |

---

## 2. 作成ファイル一覧

1. `src/main/java/com/example/manabanotifier/service/NotificationScheduler.java`
2. `src/main/java/com/example/manabanotifier/controller/HistoryController.java`
3. `src/main/resources/templates/history.html`
4. `docs/D_kawabe_notification_history.md`（本資料）

---

## 3. 機能4：通知送信（`NotificationScheduler.java`）の実装解説

### 3.1 定期実行スケジューラ（`@Scheduled`）
- `ManabaNotifierApplication` の `@EnableScheduling` により、`checkAndSendNotifications()` が **60秒ごと（`@Scheduled(fixedRate = 60000)`）** に自動起動する。
- `TaskRepository.findBySubmittedFalse()` で**未提出課題のみ**を抽出するため、蘆川さん担当の機能Bで「提出済み（`is_submitted = true`）」に切り替えられた課題は自動的に通知対象から除外される。

### 3.2 宮川さん（機能C：通知設定）との通知時刻データ連携仕様
第7回事後課題で相談事項となっていた「通知時刻データの受け渡し形式」については、共通DB `notification_settings` テーブルの仕様として以下の通り連携を確定・実装した。

- **データ受け渡し形式**: `NotificationSetting.notifyTimings` に **カンマ区切り文字列**（例: `"24,5,1"` ＝ 締切24時間前・5時間前・1時間前）を格納する。科目ごとの ON/OFF は `NotificationSetting.isEnabled()`（`true` / `false`）で判定する。
- **解析・ウィンドウ判定ロジック（`NotificationScheduler` 側）**:
  1. `task.getDeadline()` が現在時刻より未来（`remaining >= 0`）であることを確認する。
  2. `NotificationSettingRepository.findByUserIdAndCourseName(userId, courseName)` で対象科目の設定を取得し、`enabled == false` なら通知をスキップする（科目別レコードが未作成の場合は同ユーザーの他設定またはデフォルト `"24,5,1"` を参照）。
  3. `notifyTimings` を `,` で分割し、昇順の閾値リスト（例: `1時間前`, `5時間前`, `24時間前`、`30m` などの分指定にも対応）へパースする。
  4. 締切までの残り時間 `remainingSeconds` が `thresholdSeconds` 以下となる**最も近い閾値**を特定し、対応するタイミングタグ（例: `"[5時間前]"`）を生成する。
- **重複通知の防止**:
  - `NotificationLogRepository.existsByTaskIdAndUserIdAndMessageContainingAndStatus(taskId, userId, timingTag, "SUCCESS")` を呼び出し、同一課題・同一タイミングタグ（例: `"[5時間前]"`）で既に `SUCCESS` 記録がある場合は再送をスキップする。
  - これにより、「24時間前」「5時間前」「1時間前」の各段階で1回ずつ確実に通知され、毎分のスケジューラ実行で同じ通知が重複送信されない。

### 3.3 Web Push 配信とエラー耐性（例外キャッチ）
- `UserRepository.findById(userId)` から `pushEndpoint` / `pushKeys` を参照して Web Push 配信処理（`dispatchWebPush`）を実行し、結果を `notification_logs` に保存する。
- 購読情報が無効（`ERROR` / `INVALID` / `DENIED` / `EXPIRED` 等）や通信エラーが発生した場合でも、**課題単位で `try-catch` して例外を捕捉**するため、スケジューラ全体が停止することはない。
- 失敗時は `status = "FAILED"` として `notification_logs` に記録し、後から機能5の画面上で再試行できるようにする。

---

## 4. 機能5：通知履歴・再試行（`HistoryController.java` / `history.html`）の実装解説

### 4.1 画面・API エンドポイント一覧

| メソッド & パス | 機能 | 詳細 |
| :--- | :--- | :--- |
| `GET /history` | 通知履歴画面表示 | セッションまたは Cookie の `user_id` を検証し、`findByUserIdOrderBySentAtDesc` で取得した履歴に `Task`（科目名・課題名・締切日時）を結合して表示 |
| `POST /history/{logId}/retry` | 失敗通知の再試行 | `NotificationScheduler.retryNotification(logId, userId, simulateError)` を実行。成功時は `status = "SUCCESS"`・`sentAt = Now` に更新。失敗時は設計書通り「再送信に失敗しました。スマートフォンの通知許可設定を確認してください」を表示 |
| `POST /history/trigger-check` | 今すぐ通知チェック（デモ用） | `triggerManualCheckForUser(userId)` を即時実行し、未提出課題の通知判定・Web Push 配信結果を画面にフィードバック |
| `POST /history/simulate-fail` | 失敗ケース生成（デモ用） | `simulateFailedNotification(userId)` で `status = "FAILED"` の履歴レコードを1件生成し、再試行ボタンの動作確認を可能にする |
| `GET /api/notifications/recent` | 新着通知JSON API | 直近の `SUCCESS` 通知一覧（`logId`, `title`, `courseName`, `deadline`, `message`, `sentAt`, `url`）を JSON 配列で返却 |

### 4.2 ブラウザ通知のリアルタイム受信表示（`history.html`）
- `history.html` 内の JavaScript が5秒間隔で `GET /api/notifications/recent` をポーリングし、未表示の `logId` を `localStorage` で重複管理しながら検出する。
- ブラウザの通知許可（`Notification.permission === 'granted'`）がある場合は OS / ブラウザ標準の `new Notification(...)` をポップアップ表示し、通知クリックで課題一覧画面（`/tasks`）へ遷移する（詳細設計書 9.4 の出力仕様に準拠）。
- 同時に画面右下のトースト通知領域にもリアルタイム表示するため、デモ環境でブラウザ通知がブロックされている場合でも送信結果を視覚的に確認できる。

---

## 5. 発表・結合テスト時の実演シナリオ

1. **ログイン・課題取得（機能A：大友）**: `/login` からログインし、manaba の課題サンプルを自動取得。
2. **通知設定（機能C：宮川）**: `/settings` で通知タイミング（例: `24,5,1`）と科目別 ON/OFF を保存。
3. **通知送信・リアルタイム受信（機能D：川邉）**:
   - `/history` 画面で「🔔 ブラウザ通知を許可する」をクリック。
   - 「⚡ 今すぐ通知チェック実行」を押すと、`NotificationScheduler` が未提出課題を判定し、`SUCCESS` ログが記録されると同時にブラウザ Push 通知がポップアップ表示される。
4. **失敗時の再試行デモ（機能D：川邉）**:
   - 「🧪 失敗ケースをシミュレート」を押して `FAILED` ログを生成。
   - 該当行の「🔄 再試行」ボタンを押すと再配信が実行され、ステータスが `SUCCESS` に更新される。
5. **提出済みによる通知停止（機能B：蘆川）**:
   - 通知をクリックして `/tasks` へ遷移し、「提出済み」ボタンを押すと、`is_submitted = true` となり以降の通知チェック対象から除外される。
