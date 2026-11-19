package com.example.manabanotifier.service;

import com.example.manabanotifier.entity.NotificationSetting;
import com.example.manabanotifier.entity.Task;
import com.example.manabanotifier.entity.User;
import com.example.manabanotifier.repository.NotificationSettingRepository;
import com.example.manabanotifier.repository.TaskRepository;
import com.example.manabanotifier.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google OAuth 2.0 (Gmail REST API) および Google Apps Script (GAS) ウェブアプリ連携、
 * manaba 通知メール本文・JSON 取り込みを行うサービス（担当：大友）。
 */
@Service
public class TaskFetchService {

    private static final Logger log = LoggerFactory.getLogger(TaskFetchService.class);

    private static final String DEFAULT_NOTIFY_TIMINGS = "24,5,1";
    private static final Path OAUTH_CONFIG_PATH = Paths.get("config", "google-oauth.properties");
    private static final String GMAIL_SEARCH_QUERY =
            "from:do-not-reply@manaba.jp OR manaba OR (小テスト (受付終了 OR 締切 OR コース))";

    // コース名・科目名パターン（[コース名] : ..., ■コース名：..., コース名: ... 等に対応）
    private static final Pattern COURSE_LINE_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:\\[\\s*(?:コース名|科目名|コース|講義名)\\s*\\]|[■◆▼●・]?\\s*(?:コース名|科目名|コース|講義名))\\s*[:：]\\s*(.+?)\\s*$");

    // 小テスト名・課題名パターン
    private static final Pattern TITLE_LINE_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:\\[\\s*(?:小テスト名|課題名|アンケート名|テスト名|レポート名|タイトル)\\s*\\]|[■◆▼●・]?\\s*(?:小テスト名|課題名|アンケート名|テスト名|レポート名|タイトル))\\s*[:：]\\s*(.+?)\\s*$");

    // 公開時間（受付開始日時）パターン
    private static final Pattern OPEN_TIME_LINE_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:\\[\\s*(?:受付開始日時|受付開始|公開日時|公開時間|開始日時)\\s*\\]|[■◆▼●・]?\\s*(?:受付開始日時|受付開始|公開日時|公開時間|開始日時))\\s*[:：]\\s*(.+?)\\s*$");

    // 締切時間（受付終了日時）パターン
    private static final Pattern DEADLINE_LINE_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:\\[\\s*(?:受付終了日時|受付終了|締切日時|締め切り日時|提出期限|締切)\\s*\\]|[■◆▼●・]?\\s*(?:受付終了日時|受付終了|締切日時|締め切り日時|提出期限|締切))\\s*[:：]\\s*(.+?)\\s*$");

    // 受付期間（開始 ～ 終了）パターン
    private static final Pattern PERIOD_LINE_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:\\[\\s*(?:受付期間|公開期間|実施期間)\\s*\\]|[■◆▼●・]?\\s*(?:受付期間|公開期間|実施期間))\\s*[:：]\\s*(.+?)\\s*[～〜~－\\-]\\s*(.+?)\\s*$");

    // 日時文字列抽出パターン（曜日カッコ付き・年月日・時分対応）
    private static final Pattern DATETIME_EXTRACT_PATTERN = Pattern.compile(
            "(\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}日?(?:\\s*[（(][^）)]+[）)])?\\s*[T\\s]?\\d{1,2}[:時]\\d{2}(?:[:分]\\d{2}秒?)?(?:分)?)");

    // URL抽出パターン（PC : https://... 等）
    private static final Pattern PC_URL_PATTERN = Pattern.compile(
            "(?im)^\\s*(?:\\[\\s*(?:PC|URL)\\s*\\]|PC|URL)\\s*[:：]\\s*(https?://\\S+)");
    private static final Pattern ANY_URL_PATTERN = Pattern.compile("(https?://[^\\s<>\"'\\]]+)");

    // 件名フォールバック用パターン
    private static final Pattern SUBJECT_LINE_PATTERN = Pattern.compile("(?im)^\\s*(?:件名|Subject)\\s*[:：]\\s*(.+)$");
    private static final Pattern SUBJECT_COURSE_PAREN_PATTERN = Pattern.compile("[（(]([^）)]+)[）)]");
    private static final Pattern SUBJECT_COURSE_BRACKET_PATTERN = Pattern.compile("\\[([^\\]]+)\\]");

    private static final List<DateTimeFormatter> DT_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-M-d H:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-M-d H:mm")
    );

    private final TaskRepository taskRepository;
    private final NotificationSettingRepository notificationSettingRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${google.oauth.client-id:}")
    private String configuredClientId;

    @Value("${google.oauth.client-secret:}")
    private String configuredClientSecret;

    @Value("${google.oauth.redirect-uri:http://localhost:8080/auth/callback}")
    private String configuredRedirectUri;

    private volatile String dynamicClientId = "";
    private volatile String dynamicClientSecret = "";

    public TaskFetchService(
            TaskRepository taskRepository,
            NotificationSettingRepository notificationSettingRepository,
            UserRepository userRepository,
            ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.notificationSettingRepository = notificationSettingRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @PostConstruct
    public void loadOAuthConfigFromFile() {
        if (!Files.exists(OAUTH_CONFIG_PATH)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(OAUTH_CONFIG_PATH)) {
            props.load(in);
            String fileClientId = props.getProperty("google.oauth.client-id", "").trim();
            String fileClientSecret = props.getProperty("google.oauth.client-secret", "").trim();
            if (!fileClientId.isBlank() && !fileClientSecret.isBlank()) {
                this.dynamicClientId = fileClientId;
                this.dynamicClientSecret = fileClientSecret;
                log.info("ローカル OAuth 設定ファイルを読み込みました: {}", OAUTH_CONFIG_PATH);
            }
        } catch (IOException ex) {
            log.warn("OAuth 設定ファイルの読み込みに失敗しました: {}", ex.getMessage());
        }
    }

    /**
     * ログイン画面から入力された Google OAuth 2.0 クライアントID / Secret を保存し、即座に反映する。
     */
    public synchronized void saveOAuthConfig(String clientId, String clientSecret) throws IOException {
        String trimmedId = clientId != null ? clientId.trim() : "";
        String trimmedSecret = clientSecret != null ? clientSecret.trim() : "";
        if (trimmedId.isBlank() || trimmedSecret.isBlank()) {
            throw new IllegalArgumentException("Google OAuth クライアントID と クライアントシークレットの両方を入力してください。");
        }

        if (OAUTH_CONFIG_PATH.getParent() != null) {
            Files.createDirectories(OAUTH_CONFIG_PATH.getParent());
        }
        Properties props = new Properties();
        props.setProperty("google.oauth.client-id", trimmedId);
        props.setProperty("google.oauth.client-secret", trimmedSecret);
        try (OutputStream out = Files.newOutputStream(OAUTH_CONFIG_PATH)) {
            props.store(out, "Local Google OAuth 2.0 Credentials (ignored by git)");
        }

        this.dynamicClientId = trimmedId;
        this.dynamicClientSecret = trimmedSecret;
    }

    public String getOAuthClientId() {
        if (dynamicClientId != null && !dynamicClientId.isBlank()) {
            return dynamicClientId;
        }
        return configuredClientId != null ? configuredClientId.trim() : "";
    }

    public String getOAuthClientSecret() {
        if (dynamicClientSecret != null && !dynamicClientSecret.isBlank()) {
            return dynamicClientSecret;
        }
        return configuredClientSecret != null ? configuredClientSecret.trim() : "";
    }

    public String getOAuthRedirectUri() {
        return (configuredRedirectUri != null && !configuredRedirectUri.isBlank())
                ? configuredRedirectUri.trim()
                : "http://localhost:8080/auth/callback";
    }

    public boolean isOAuthConfigured() {
        return !getOAuthClientId().isBlank() && !getOAuthClientSecret().isBlank();
    }

    /**
     * Gmail / GAS 取得時の例外（認証エラーか通信・設定エラーかを区別する）。
     */
    public static class GmailFetchException extends RuntimeException {
        private final boolean authFailed;

        public GmailFetchException(String message, boolean authFailed) {
            super(message);
            this.authFailed = authFailed;
        }

        public GmailFetchException(String message, boolean authFailed, Throwable cause) {
            super(message, cause);
            this.authFailed = authFailed;
        }

        public boolean isAuthFailed() {
            return authFailed;
        }
    }

    /**
     * GAS ウェブアプリからの取得結果 DTO。
     */
    public static class GasFetchResult {
        private final String userId;
        private final String email;
        private final int totalMessages;
        private final int syncedCount;

        public GasFetchResult(String userId, String email, int totalMessages, int syncedCount) {
            this.userId = userId;
            this.email = email;
            this.totalMessages = totalMessages;
            this.syncedCount = syncedCount;
        }

        public String getUserId() {
            return userId;
        }

        public String getEmail() {
            return email;
        }

        public int getTotalMessages() {
            return totalMessages;
        }

        public int getSyncedCount() {
            return syncedCount;
        }
    }

    /**
     * manaba メール解析結果 DTO。
     */
    public static class ParsedTask {
        private final String courseName;
        private final String title;
        private final LocalDateTime openTime;
        private final LocalDateTime deadline;
        private final String taskType;
        private final String taskUrl;

        public ParsedTask(
                String courseName,
                String title,
                LocalDateTime openTime,
                LocalDateTime deadline,
                String taskType,
                String taskUrl) {
            this.courseName = courseName;
            this.title = title;
            this.openTime = openTime;
            this.deadline = deadline;
            this.taskType = taskType;
            this.taskUrl = taskUrl;
        }

        public String getCourseName() {
            return courseName;
        }

        public String getTitle() {
            return title;
        }

        public LocalDateTime getOpenTime() {
            return openTime;
        }

        public LocalDateTime getDeadline() {
            return deadline;
        }

        public String getTaskType() {
            return taskType;
        }

        public String getTaskUrl() {
            return taskUrl;
        }
    }

    /**
     * manaba 課題・小テスト通知メールの件名・本文から科目名・名称・公開時間・締切時間・種別・URL を抽出する。
     */
    public Optional<ParsedTask> parseManabaEmail(String subject, String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }

        String normalizedBody = body.replace("\r\n", "\n").replace("\r", "\n");

        String courseName = extractFirstGroup(COURSE_LINE_PATTERN, normalizedBody);
        String title = extractFirstGroup(TITLE_LINE_PATTERN, normalizedBody);

        LocalDateTime openTime = null;
        LocalDateTime deadline = null;

        String rawOpen = extractFirstGroup(OPEN_TIME_LINE_PATTERN, normalizedBody);
        if (rawOpen != null) {
            openTime = parseDateTime(rawOpen);
        }

        String rawDeadline = extractFirstGroup(DEADLINE_LINE_PATTERN, normalizedBody);
        if (rawDeadline != null) {
            deadline = parseDateTime(rawDeadline);
        }

        Matcher periodMatcher = PERIOD_LINE_PATTERN.matcher(normalizedBody);
        if (periodMatcher.find()) {
            if (openTime == null) {
                openTime = parseDateTime(periodMatcher.group(1));
            }
            if (deadline == null) {
                deadline = parseDateTime(periodMatcher.group(2));
            }
        }

        if (subject != null && !subject.isBlank()) {
            if (courseName == null) {
                courseName = extractCourseFromSubject(subject);
            }
            if (title == null) {
                title = extractTitleFromSubject(subject);
            }
        }

        if (courseName == null || courseName.isBlank() || title == null || title.isBlank()) {
            return Optional.empty();
        }

        if (deadline == null && openTime == null) {
            return Optional.empty();
        }
        if (deadline == null) {
            deadline = openTime;
        }

        String taskType = detectTaskType(subject, normalizedBody);
        String taskUrl = extractTaskUrl(normalizedBody);

        return Optional.of(new ParsedTask(
                courseName.trim(),
                title.trim(),
                openTime,
                deadline,
                taskType,
                taskUrl
        ));
    }

    /**
     * ログイン済みユーザーの連携方式（GAS または Google OAuth 2.0）に応じて Gmail から再同期する。
     */
    @Transactional
    public int fetchTasksFromGmail(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new GmailFetchException("ログインユーザー情報が見つかりません。", true);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new GmailFetchException("ユーザー情報が見つかりません。", true));

        if ("GAS".equalsIgnoreCase(user.getAuthType())
                || (user.getGasUrl() != null && !user.getGasUrl().isBlank())) {
            GasFetchResult result = fetchTasksFromGas(userId, user.getGasUrl(), user.getGasToken());
            return result.getSyncedCount();
        }

        if (user.getAccessToken() != null && !user.getAccessToken().isBlank()) {
            return fetchTasksFromGmailApiWithRefresh(user);
        }

        throw new GmailFetchException(
                "Google OAuth 2.0 または GAS ウェブアプリ連携が設定されていません。"
                        + "「manaba通知メール / GAS JSON を貼り付けて追加」または再ログインで連携してください。",
                false);
    }

    private int fetchTasksFromGmailApiWithRefresh(User user) {
        try {
            return fetchTasksFromGmailApi(user.getUserId(), user.getAccessToken());
        } catch (GmailFetchException ex) {
            if (ex.isAuthFailed()
                    && user.getRefreshToken() != null && !user.getRefreshToken().isBlank()
                    && isOAuthConfigured()) {
                log.info("Gmail API トークン期限切れのため refresh_token で再取得を試行します: userId={}", user.getUserId());
                String newAccessToken = refreshAccessToken(user.getRefreshToken());
                user.setAccessToken(newAccessToken);
                userRepository.save(user);
                return fetchTasksFromGmailApi(user.getUserId(), newAccessToken);
            }
            throw ex;
        }
    }

    private String refreshAccessToken(String refreshToken) {
        try {
            String formBody = "client_id=" + urlEncode(getOAuthClientId())
                    + "&client_secret=" + urlEncode(getOAuthClientSecret())
                    + "&refresh_token=" + urlEncode(refreshToken)
                    + "&grant_type=refresh_token";

            HttpRequest req = HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                throw new GmailFetchException(
                        "Google OAuth リフレッシュトークンによる再認証に失敗しました。再度 Google ログインを行ってください。",
                        true);
            }
            JsonNode json = objectMapper.readTree(res.body());
            String newToken = json.path("access_token").asText(null);
            if (newToken == null || newToken.isBlank()) {
                throw new GmailFetchException("アクセストークンの更新に失敗しました。再度 Google ログインを行ってください。", true);
            }
            return newToken;
        } catch (GmailFetchException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GmailFetchException("トークン更新通信が中断されました。", false, ex);
        } catch (IOException ex) {
            throw new GmailFetchException("トークン更新通信に失敗しました: " + ex.getMessage(), false, ex);
        }
    }

    /**
     * 本物の Gmail REST API (gmail.googleapis.com) から manaba 小テスト・課題メールを取得・保存する。
     */
    @Transactional
    public int fetchTasksFromGmailApi(String userId, String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new GmailFetchException("Gmail API アクセストークンが空です。", true);
        }

        String trimmedToken = accessToken.trim();
        try {
            String query = URLEncoder.encode(GMAIL_SEARCH_QUERY, StandardCharsets.UTF_8);
            URI listUri = URI.create(
                    "https://gmail.googleapis.com/gmail/v1/users/me/messages?q=" + query + "&maxResults=50");

            HttpRequest listReq = HttpRequest.newBuilder(listUri)
                    .header("Authorization", "Bearer " + trimmedToken)
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> listRes = httpClient.send(
                    listReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (listRes.statusCode() == 401 || listRes.statusCode() == 403) {
                throw new GmailFetchException(
                        "Gmail API の認証に失敗しました（HTTP " + listRes.statusCode() + "）。"
                                + "アクセストークンの有効期限またはスコープ（gmail.readonly）を確認してください。",
                        true);
            }
            if (listRes.statusCode() < 200 || listRes.statusCode() >= 300) {
                throw new GmailFetchException(
                        "Gmail API からのメール一覧取得に失敗しました（HTTP " + listRes.statusCode() + "）。",
                        false);
            }

            JsonNode root = objectMapper.readTree(listRes.body());
            JsonNode messagesNode = root.get("messages");
            if (messagesNode == null || !messagesNode.isArray() || messagesNode.isEmpty()) {
                return 0;
            }

            int syncedCount = 0;
            for (JsonNode msgRef : messagesNode) {
                String msgId = msgRef.path("id").asText(null);
                if (msgId == null || msgId.isBlank()) {
                    continue;
                }
                try {
                    URI msgUri = URI.create(
                            "https://gmail.googleapis.com/gmail/v1/users/me/messages/" + msgId + "?format=full");
                    HttpRequest msgReq = HttpRequest.newBuilder(msgUri)
                            .header("Authorization", "Bearer " + trimmedToken)
                            .timeout(Duration.ofSeconds(15))
                            .GET()
                            .build();

                    HttpResponse<String> msgRes = httpClient.send(
                            msgReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (msgRes.statusCode() != 200) {
                        continue;
                    }

                    JsonNode msgJson = objectMapper.readTree(msgRes.body());
                    JsonNode payload = msgJson.path("payload");
                    String subject = extractHeaderValue(payload.path("headers"), "Subject");
                    String body = extractBodyFromGmailPayload(payload);

                    Optional<ParsedTask> parsedOpt = parseManabaEmail(subject, body);
                    if (parsedOpt.isPresent()) {
                        upsertParsedTask(userId, parsedOpt.get());
                        syncedCount++;
                    }
                } catch (Exception ex) {
                    log.warn("Gmail API メッセージ {} の解析中にスキップ: {}", msgId, ex.getMessage());
                }
            }

            return syncedCount;
        } catch (GmailFetchException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GmailFetchException("Gmail API 通信が中断されました。", false, ex);
        } catch (IOException ex) {
            throw new GmailFetchException("Gmail API への通信に失敗しました: " + ex.getMessage(), false, ex);
        }
    }

    /**
     * 既知の userId 向けに Google Apps Script (GAS) ウェブアプリから manaba メール JSON を取得・保存する。
     */
    @Transactional
    public GasFetchResult fetchTasksFromGas(String userId, String gasUrl, String gasToken) {
        return executeGasFetch(userId, null, gasUrl, gasToken);
    }

    /**
     * 初回 GAS ログイン時用（メールアドレス未入力時は GAS 応答内の email から userId を決定する）。
     */
    @Transactional
    public GasFetchResult fetchTasksFromGasForLogin(String optionalEmail, String gasUrl, String gasToken) {
        return executeGasFetch(null, optionalEmail, gasUrl, gasToken);
    }

    private GasFetchResult executeGasFetch(
            String knownUserId,
            String optionalEmail,
            String gasUrl,
            String gasToken) {

        if (gasUrl == null || gasUrl.isBlank()) {
            throw new GmailFetchException("GAS ウェブアプリの URL を入力してください。", false);
        }

        String trimmedUrl = gasUrl.trim();
        if (!trimmedUrl.startsWith("https://") && !trimmedUrl.startsWith("http://")) {
            throw new GmailFetchException("GAS ウェブアプリ URL は https:// から始まる有効な URL を入力してください。", false);
        }

        String requestUrl = trimmedUrl;
        if (gasToken != null && !gasToken.isBlank()) {
            String separator = trimmedUrl.contains("?") ? "&" : "?";
            requestUrl = trimmedUrl + separator + "token=" + urlEncode(gasToken.trim());
        }

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(requestUrl))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String bodyText = res.body() != null ? res.body().trim() : "";

            if (isHtmlResponse(bodyText) || res.statusCode() == 401 || res.statusCode() == 403) {
                throw new GmailFetchException(
                        "GAS ウェブアプリの『アクセスできるユーザー』が『全員』になっていないか、"
                                + "組織の制限により外部アクセスがブロックされています。"
                                + "『全員』で再デプロイするか、GAS エディタで exportManabaJson を実行して出力 JSON を画面から貼り付けてください"
                                + "（または Google OAuth 2.0 をご利用ください）。",
                        true);
            }

            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                throw new GmailFetchException(
                        "GAS ウェブアプリからの取得に失敗しました（HTTP " + res.statusCode() + "）。",
                        false);
            }

            if (bodyText.isBlank()) {
                throw new GmailFetchException("GAS ウェブアプリから空のレスポンスが返されました。", false);
            }

            JsonNode root = objectMapper.readTree(bodyText);
            if (root.isObject() && root.has("error") && !root.path("error").asText("").isBlank()) {
                throw new GmailFetchException(
                        "GAS ウェブアプリエラー: " + root.path("error").asText(),
                        true);
            }

            String gasEmail = root.isObject() ? root.path("email").asText("").trim().toLowerCase() : "";
            String resolvedEmail = (optionalEmail != null && !optionalEmail.isBlank())
                    ? optionalEmail.trim().toLowerCase()
                    : (!gasEmail.isBlank() ? gasEmail : "gas-" + shortHash(trimmedUrl) + "@manaba-sync.local");

            String targetUserId = (knownUserId != null && !knownUserId.isBlank())
                    ? knownUserId
                    : generateUserId(resolvedEmail);

            JsonNode emailsNode = extractEmailsArrayNode(root);
            int totalMessages = emailsNode != null ? emailsNode.size() : 0;
            int syncedCount = upsertFromJsonArray(targetUserId, emailsNode);

            return new GasFetchResult(targetUserId, resolvedEmail, totalMessages, syncedCount);
        } catch (GmailFetchException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw new GmailFetchException("GAS ウェブアプリ URL の形式が正しくありません: " + ex.getMessage(), false, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GmailFetchException("GAS ウェブアプリへの通信が中断されました。", false, ex);
        } catch (IOException ex) {
            throw new GmailFetchException("GAS ウェブアプリへの通信または JSON 解析に失敗しました: " + ex.getMessage(), false, ex);
        }
    }

    /**
     * 貼り付けられた manaba 通知メール本文（1通/複数通）または GAS の exportManabaJson() JSON を解析して登録する。
     */
    @Transactional
    public int importFromRawEmailText(String userId, String rawText) {
        if (userId == null || userId.isBlank() || rawText == null || rawText.isBlank()) {
            return 0;
        }

        String trimmed = rawText.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                JsonNode root = objectMapper.readTree(trimmed);
                if (root.isObject() && root.has("subject") && root.has("body")) {
                    Optional<ParsedTask> single = parseManabaEmail(
                            root.path("subject").asText(""),
                            root.path("body").asText(""));
                    if (single.isPresent()) {
                        upsertParsedTask(userId, single.get());
                        return 1;
                    }
                    return 0;
                }
                JsonNode emailsNode = extractEmailsArrayNode(root);
                return upsertFromJsonArray(userId, emailsNode);
            } catch (IOException ex) {
                log.warn("JSON 形式でのメール取り込み解析に失敗しました: {}", ex.getMessage());
                return 0;
            }
        }

        List<String> blocks = splitRawEmailBlocks(rawText);
        int imported = 0;

        for (String block : blocks) {
            String subject = extractFirstGroup(SUBJECT_LINE_PATTERN, block);
            Optional<ParsedTask> parsedOpt = parseManabaEmail(subject, block);
            if (parsedOpt.isPresent()) {
                upsertParsedTask(userId, parsedOpt.get());
                imported++;
            }
        }
        return imported;
    }

    private JsonNode extractEmailsArrayNode(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return null;
        }
        if (root.isArray()) {
            return root;
        }
        if (root.has("emails") && root.get("emails").isArray()) {
            return root.get("emails");
        }
        if (root.has("messages") && root.get("messages").isArray()) {
            return root.get("messages");
        }
        return null;
    }

    private int upsertFromJsonArray(String userId, JsonNode emailsNode) {
        if (emailsNode == null || !emailsNode.isArray()) {
            return 0;
        }
        int syncedCount = 0;
        for (JsonNode item : emailsNode) {
            String subject = item.path("subject").asText("");
            String body = item.path("body").asText("");
            Optional<ParsedTask> parsedOpt = parseManabaEmail(subject, body);
            if (parsedOpt.isPresent()) {
                upsertParsedTask(userId, parsedOpt.get());
                syncedCount++;
            }
        }
        return syncedCount;
    }

    private boolean isHtmlResponse(String bodyText) {
        String lower = bodyText.toLowerCase();
        return lower.startsWith("<!doctype")
                || lower.startsWith("<html")
                || lower.contains("<title>google")
                || lower.contains("accounts.google.com");
    }

    private List<String> splitRawEmailBlocks(String rawText) {
        String normalized = rawText.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n");
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean seenCourse = false;
        boolean seenTitle = false;
        boolean seenTime = false;

        for (String line : lines) {
            String trimmed = line.trim();
            boolean isExplicitSeparator = trimmed.matches("^[-=]{5,}$");
            boolean startsNewSubject = trimmed.matches("^(?:件名|Subject)\\s*[:：].*$");
            boolean startsNewCourse = COURSE_LINE_PATTERN.matcher(line).matches();

            if (isExplicitSeparator) {
                if (!current.isEmpty()) {
                    blocks.add(current.toString());
                    current.setLength(0);
                    seenCourse = false;
                    seenTitle = false;
                    seenTime = false;
                }
                continue;
            }

            if ((startsNewSubject && !current.isEmpty()) || (startsNewCourse && seenCourse && seenTitle && seenTime)) {
                blocks.add(current.toString());
                current.setLength(0);
                seenCourse = false;
                seenTitle = false;
                seenTime = false;
            }

            if (COURSE_LINE_PATTERN.matcher(line).matches()) {
                seenCourse = true;
            }
            if (TITLE_LINE_PATTERN.matcher(line).matches()) {
                seenTitle = true;
            }
            if (DEADLINE_LINE_PATTERN.matcher(line).matches()
                    || OPEN_TIME_LINE_PATTERN.matcher(line).matches()
                    || PERIOD_LINE_PATTERN.matcher(line).matches()) {
                seenTime = true;
            }

            current.append(line).append('\n');
        }

        if (!current.isEmpty()) {
            blocks.add(current.toString());
        }
        return blocks;
    }

    private Task upsertParsedTask(String userId, ParsedTask parsed) {
        Optional<Task> existingOpt = taskRepository.findByUserIdAndTitleAndCourseName(
                userId, parsed.getTitle(), parsed.getCourseName());

        Task task;
        if (existingOpt.isPresent()) {
            task = existingOpt.get();
            if (parsed.getDeadline() != null) {
                task.setDeadline(parsed.getDeadline());
            }
            if (parsed.getOpenTime() != null) {
                task.setOpenTime(parsed.getOpenTime());
            }
            if (parsed.getTaskType() != null && !parsed.getTaskType().isBlank()) {
                task.setTaskType(parsed.getTaskType());
            }
            if (parsed.getTaskUrl() != null && !parsed.getTaskUrl().isBlank()) {
                task.setTaskUrl(parsed.getTaskUrl());
            }
        } else {
            task = new Task();
            task.setUserId(userId);
            task.setCourseName(parsed.getCourseName());
            task.setTitle(parsed.getTitle());
            task.setOpenTime(parsed.getOpenTime());
            task.setDeadline(parsed.getDeadline());
            task.setTaskType(parsed.getTaskType() != null ? parsed.getTaskType() : "小テスト");
            task.setTaskUrl(parsed.getTaskUrl());
            task.setSubmitted(false);
        }

        Task saved = taskRepository.save(task);
        ensureDefaultNotificationSetting(userId, parsed.getCourseName());
        return saved;
    }

    private void ensureDefaultNotificationSetting(String userId, String courseName) {
        Optional<NotificationSetting> existing =
                notificationSettingRepository.findByUserIdAndCourseName(userId, courseName);
        if (existing.isEmpty()) {
            NotificationSetting setting = new NotificationSetting();
            setting.setUserId(userId);
            setting.setCourseName(courseName);
            setting.setNotifyTimings(DEFAULT_NOTIFY_TIMINGS);
            setting.setEnabled(true);
            notificationSettingRepository.save(setting);
        }
    }

    private String extractHeaderValue(JsonNode headers, String name) {
        if (headers == null || !headers.isArray()) {
            return null;
        }
        for (JsonNode header : headers) {
            if (name.equalsIgnoreCase(header.path("name").asText())) {
                return header.path("value").asText(null);
            }
        }
        return null;
    }

    private String extractBodyFromGmailPayload(JsonNode payload) {
        if (payload == null || payload.isMissingNode()) {
            return "";
        }

        String mimeType = payload.path("mimeType").asText("");
        String bodyData = payload.path("body").path("data").asText(null);
        if (bodyData != null && !bodyData.isBlank()) {
            String decoded = new String(Base64.getUrlDecoder().decode(bodyData), StandardCharsets.UTF_8);
            if ("text/html".equalsIgnoreCase(mimeType)) {
                return htmlToPlainText(decoded);
            }
            return decoded;
        }

        JsonNode parts = payload.path("parts");
        if (parts.isArray()) {
            StringBuilder plain = new StringBuilder();
            StringBuilder htmlFallback = new StringBuilder();
            for (JsonNode part : parts) {
                String partMime = part.path("mimeType").asText("");
                String extracted = extractBodyFromGmailPayload(part);
                if (extracted.isBlank()) {
                    continue;
                }
                if ("text/html".equalsIgnoreCase(partMime)) {
                    htmlFallback.append(extracted).append('\n');
                } else {
                    plain.append(extracted).append('\n');
                }
            }
            return !plain.toString().isBlank() ? plain.toString() : htmlFallback.toString();
        }

        return "";
    }

    private String htmlToPlainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return html
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|tr|li|h[1-6])>", "\n")
                .replaceAll("(?i)<td[^>]*>", " ")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private String extractCourseFromSubject(String subject) {
        Matcher parenMatcher = SUBJECT_COURSE_PAREN_PATTERN.matcher(subject);
        if (parenMatcher.find()) {
            String candidate = parenMatcher.group(1).trim();
            if (!candidate.equalsIgnoreCase("manaba")) {
                return candidate;
            }
        }
        Matcher bracketMatcher = SUBJECT_COURSE_BRACKET_PATTERN.matcher(subject);
        while (bracketMatcher.find()) {
            String candidate = bracketMatcher.group(1).trim();
            if (!candidate.equalsIgnoreCase("manaba") && !candidate.contains("リマインダ") && !candidate.contains("お知らせ")) {
                return candidate;
            }
        }
        return null;
    }

    private String extractTitleFromSubject(String subject) {
        int slashIdx = subject.indexOf('/');
        if (slashIdx >= 0 && slashIdx + 1 < subject.length()) {
            return subject.substring(slashIdx + 1).trim();
        }
        int colonIdx = Math.max(subject.indexOf(':'), subject.indexOf('：'));
        if (colonIdx >= 0 && colonIdx + 1 < subject.length()) {
            return subject.substring(colonIdx + 1).trim();
        }
        return null;
    }

    private String detectTaskType(String subject, String body) {
        String combined = ((subject != null ? subject : "") + "\n" + (body != null ? body : ""));
        if (combined.contains("小テスト")) {
            return "小テスト";
        }
        if (combined.contains("アンケート")) {
            return "アンケート";
        }
        if (combined.contains("レポート") || combined.contains("課題名")) {
            return "レポート";
        }
        return "小テスト";
    }

    private String extractTaskUrl(String body) {
        Matcher pcMatcher = PC_URL_PATTERN.matcher(body);
        if (pcMatcher.find()) {
            return pcMatcher.group(1).trim();
        }
        Matcher anyMatcher = ANY_URL_PATTERN.matcher(body);
        while (anyMatcher.find()) {
            String url = anyMatcher.group(1).trim();
            if (url.contains("manaba") || url.contains("course") || url.contains("query")) {
                return url;
            }
        }
        return null;
    }

    private String extractFirstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private LocalDateTime parseDateTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher dtMatcher = DATETIME_EXTRACT_PATTERN.matcher(raw);
        String target = dtMatcher.find() ? dtMatcher.group(1) : raw;

        String normalized = target
                .replaceAll("[（(][^）)]+[）)]", " ")
                .replace("年", "-")
                .replace("月", "-")
                .replace("日", " ")
                .replace("/", "-")
                .replace("T", " ")
                .replace("時", ":")
                .replace("分", "")
                .replace("秒", "")
                .replaceAll("\\s+", " ")
                .trim();

        if (normalized.endsWith(":")) {
            normalized = normalized + "00";
        }

        for (DateTimeFormatter formatter : DT_FORMATTERS) {
            try {
                return LocalDateTime.parse(normalized, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private String generateUserId(String email) {
        return "user-" + UUID.nameUUIDFromBytes(email.getBytes(StandardCharsets.UTF_8))
                .toString().substring(0, 12);
    }

    private String shortHash(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8))
                .toString().substring(0, 8);
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
