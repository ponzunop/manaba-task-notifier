# 他の人も使えるように公開する手順（マルチユーザー共有ガイド）

このアプリはすでに **ユーザーごと（`user_id`）の完全データ分離** に対応しており、以下の手順で **千葉工業大学の他の学生（チームメンバー・友達全員）が設定不要・1クリックで自分のスマホやPCから使える状態** に公開できます。

---

## 全体の仕組み（なぜ他の人は設定不要で使えるのか？）

1. **アプリ本体を HTTPS で公開**（Render / Railway などの無料クラウド、または Cloudflare Tunnel）
2. **代表者（宮川さん等）が 1 つだけ GAS ウェブアプリを「学内共有設定」でデプロイ**：
   - 次のユーザーとして実行：**`ウェブアプリにアクセスしているユーザー`**（← これが最重要！アクセスした人それぞれの自分の Gmail を読み取ります）
   - アクセスできるユーザー：**`千葉工業大学内の全員`**（← 大学 Google Workspace で選択可能！）
3. 発行された 1 つの GAS URL をアプリの環境変数 **`SHARED_GAS_URL`** に設定する
4. 他の学生は公開 URL（`https://...`）を開いて **「大学 Google アカウントでログイン & 小テストを自動取得」ボタンを 1 回押すだけ**！
   - 他の学生は GAS のコードをコピペする必要も、アプリパスワードも、Google Cloud 設定も一切不要です。
   - A さんがログインすると A さんの Gmail から A さんの小テストだけが表示され、B さんがログインすると B さんの小テストだけが表示されます。

---

## 方法A：Render / Railway で常時 HTTPS 公開（おすすめ・無料枠あり）

GitHub リポジトリ（`ponzunop/manaba-task-notifier`）には `Dockerfile` と `render.yaml`、PostgreSQL ドライバを同梱済みです。

### ステップ 1：アプリ本体を HTTPS 公開する
#### Render の場合（最も簡単）
1. [Render](https://render.com/) に GitHub アカウントでログイン
2. **「New +」→「Web Service」** を選び、GitHub リポジトリ `ponzunop/manaba-task-notifier` を選択
3. Runtime は **`Docker`** が自動認識されます（Plan は **Free** を選択）
4. Environment Variables に以下を設定して **Deploy**：
   - `SERVER_ADDRESS` = `0.0.0.0`
   - `APP_PUBLIC_MODE` = `true`
   - （DB データを再起動後も永続化したい場合は Render PostgreSQL を作り、`SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` を設定。未設定でも組み込み H2 DB で即起動します）
5. デプロイ完了後、公開 HTTPS URL（例：`https://manaba-task-notifier.onrender.com`）が発行されます。

*(※ Railway を使う場合も、GitHub リポジトリを選ぶだけで `Dockerfile` が自動検出され、HTTPS URL が発行されます。)*

### ステップ 2：大学内共有 GAS を 1 つだけデプロイする（代表者が 1 回だけ）
1. 公開された **`https://<あなたの公開URL>/login`** をブラウザで開く
2. 画面内の「コピー用 GAS コードを表示」を開き、**「GASコードをコピー」** を押す
   - ※公開 URL で開いてコピーすると、コード内の `ALLOWED_ORIGINS` に自動でその公開 URL が埋め込まれます！
3. 大学 Google アカウント（`@chibatech.ac.jp`）で [script.google.com](https://script.google.com) を開き、コードを貼り付けて保存
4. **「デプロイ」→「新しいデプロイ」→ 歯車「ウェブアプリ」** で以下を設定してデプロイ：
   - **次のユーザーとして実行**: **`ウェブアプリにアクセスしているユーザー`**
   - **アクセスできるユーザー**: **`千葉工業大学内の全員`**
5. 発行された URL（`https://script.google.com/a/macros/chibatech.ac.jp/s/.../exec`）をコピー

### ステップ 3：環境変数 `SHARED_GAS_URL` を設定して完了！
1. Render（または Railway）の Environment Variables に **`SHARED_GAS_URL`** =（ステップ2でコピーした URL）を追加して保存
2. 自動再起動後、公開 URL（`https://...`）を開くと、トップに **「大学 Google アカウントでログイン & 小テストを自動取得」** の 1 クリックボタンが表示されます！
3. あとは公開 URL を LINE や Discord でチームメンバーや友達に送るだけで、全員が自分のスマホ・PC からそのまま使えます。

---

## 方法B：クラウド登録なしで今すぐ友達のスマホと共有（授業デモ向け・Cloudflare Quick Tunnel）

授業中の発表デモやテストで「今すぐ友達のスマホから自分の PC のアプリにアクセスさせたい」場合は、クラウド契約不要で一時 HTTPS URL を発行できます。

1. アプリを起動（ローカル待受のまま OK）：
   ```bash
   cd /home/miyakawaharuki/ソフトウェア工学/manaba-notifier
   ./mvnw spring-boot:run
   ```
2. 別ターミナルで Cloudflare Tunnel を起動：
   ```bash
   cloudflared tunnel --url http://localhost:8080
   ```
3. 表示された `https://xxxx.trycloudflare.com` を開いて、ステップ2と同様に `ALLOWED_ORIGINS` へ URL を入れて共有 GAS をデプロイし、ローカルの `/login` 画面の **「学内共有 GAS URL を登録」** に保存すれば、即座に友達のスマホから 1 クリックで利用できます。

---

## マルチユーザー公開時のセキュリティ設計
- **データ完全分離**: 全テーブル（`tasks` / `notification_settings` / `notification_logs`）を `user_id` で分離し、他人の課題・設定・履歴は閲覧も更新も不可（IDOR テスト済み）
- **コールバック先固定 (`ALLOWED_ORIGINS`)**: 共有 GAS が任意の外部サイトへメールデータを送れないよう、送信先を公開アプリ本体のオリジンのみにホワイトリスト制限
- **簡易ログイン無効化 (`APP_PUBLIC_MODE=true`)**: 公開時はメール入力だけのなりすましログインを自動無効化し、必ず Google 認証済みアカウントのみでログイン
- **ワンタイム `gasState` + CSRF + HTTPS Cookie**: 複数人が同時にログインしても衝突せず安全にセッション確立
