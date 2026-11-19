package com.example.manabanotifier.service;

import com.example.manabanotifier.entity.NotificationSetting;
import com.example.manabanotifier.entity.Task;
import com.example.manabanotifier.entity.User;
import com.example.manabanotifier.repository.NotificationSettingRepository;
import com.example.manabanotifier.repository.TaskRepository;
import com.example.manabanotifier.repository.UserRepository;
import com.example.manabanotifier.security.SecurityUtils;
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
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ブラウザ経由 GAS 連携・Google OAuth 2.0 (Gmail REST API)・manaba 小テスト通知メール解析サービス（担当：大友）。
 */
@Service
public class TaskFetchService {

    private static final Logger log = LoggerFactory.getLogger(TaskFetchService.class);

    private static final String DEFAULT_NOTIFY_TIMINGS = "24,5,1";
    private static final int MAX_SKIPPED_SAMPLES = 5;
    private static final int MAX_IMPORT_PAYLOAD_CHARS = 2 * 1024 * 1024;
    private static final int MAX_IMPORT_EMAILS = 200;
    private static final int MAX_GAS_REDIRECTS = 5;
    private static final Path OAUTH_CONFIG_PATH = Paths.get("config", "google-oauth.properties");
    private static final Path SHARED_GAS_CONFIG_PATH = Paths.get("config", "shared-gas.properties");
    private static final String GMAIL_SEARCH_QUERY =
            "from:do-not-reply@manaba.jp OR manaba OR (小テスト (受付終了 OR 締切 OR コース))";

    // コース名・科目名パターン（[コース名] : ..., ■コース名：..., コース：..., 科目名：... 等に対応）
    private static final Pattern COURSE_LINE_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:\\[\\s*(?:コース名|科目名|コース|講義名|授業名)\\s*\\]|[■◆▼●・]?\\s*(?:コース名|科目名|コース|講義名|授業名))\\s*[:：]\\s*(.+?)\\s*$");

    // 小テスト名・課題名パターン
    private static final Pattern TITLE_LINE_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:\\[\\s*(?:小テスト名|小テスト|課題名|課題|アンケート名|テスト名|レポート名|タイトル)\\s*\\]|[■◆▼●・]?\\s*(?:小テスト名|小テスト|課題名|課題|アンケート名|テスト名|レポート名|タイトル))\\s*[:：]\\s*(.+?)\\s*$");

    // 公開時間（受付開始日時・公開日時・公開開始）パターン
    private static final Pattern OPEN_TIME_LINE_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:\\[\\s*(?:受付開始日時|受付開始|公開開始日時|公開開始|公開日時|公開時間|開始日時)\\s*\\]|[■◆▼●・]?\\s*(?:受付開始日時|受付開始|公開開始日時|公開開始|公開日時|公開時間|開始日時))\\s*[:：]\\s*(.+?)\\s*$");

    // 締切時間（受付終了日時・公開終了・締切・期限）パターン
    private static final Pattern DEADLINE_LINE_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:\\[\\s*(?:受付終了日時|受付終了|公開終了日時|公開終了|締切日時|締め切り日時|提出期限|締切|期限|終了日時)\\s*\\]|[■◆▼●・]?\\s*(?:受付終了日時|受付終了|公開終了日時|公開終了|締切日時|締め切り日時|提出期限|締切|期限|終了日時))\\s*[:：]\\s*(.+?)\\s*$");

    // 受付期間・公開期間（開始 ～ 終了 / から ～ まで）パターン
    private static final Pattern PERIOD_LINE_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:\\[\\s*(?:受付期間|公開期間|実施期間|提出期間)\\s*\\]|[■◆▼●・]?\\s*(?:受付期間|公開期間|実施期間|提出期間))\\s*[:：]\\s*(.+?)\\s*(?:[～〜~－\\-]|から)\\s*(.+?)(?:まで)?\\s*$");

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
    private final HttpClient noRedirectHttpClient;

    @Value("${google.oauth.client-id:}")
    private String configuredClientId;

    @Value("${google.oauth.client-secret:}")
    private String configuredClientSecret;

    @Value("${google.oauth.redirect-uri:http://localhost:8080/auth/callback}")
    private String configuredRedirectUri;

    @Value("${app.gas.shared-url:}")
    private String configuredSharedGasUrl;

    private volatile String dynamicClientId = "";
    private volatile String dynamicClientSecret = "";
    private volatile String dynamicSharedGasUrl = "";

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
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.noRedirectHttpClient = this.httpClient;
    }

    @PostConstruct
    public void loadOAuthConfigFromFile() {
        loadSharedGasConfigFromFile();
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

    private void loadSharedGasConfigFromFile() {
        if (!Files.exists(SHARED_GAS_CONFIG_PATH)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(SHARED_GAS_CONFIG_PATH)) {
            props.load(in);
            String url = props.getProperty("app.gas.shared-url", "").trim();
            if (SecurityUtils.isValidGoogleAppsScriptUrl(url)) {
                this.dynamicSharedGasUrl = url;
                log.info("共有 GAS 設定ファイルを読み込みました: {}", SHARED_GAS_CONFIG_PATH);
            }
        } catch (IOException ex) {
            log.warn("共有 GAS 設定ファイルの読み込みに失敗しました: {}", ex.getMessage());
        }
    }

    /**
     * 複数ユーザーで共有する GAS ウェブアプリ URL（「アクセスしているユーザーとして実行」設定）を保存する。
     */
    public synchronized void saveSharedGasUrl(String gasUrl) throws IOException {
        String trimmed = gasUrl != null ? gasUrl.trim() : "";
        if (!SecurityUtils.isValidGoogleAppsScriptUrl(trimmed)) {
            throw new IllegalArgumentException(
                    "Google Apps Script 公式のウェブアプリ URL（https://script.google.com/.../exec）を入力してください。");
        }
        if (SHARED_GAS_CONFIG_PATH.getParent() != null) {
            Files.createDirectories(SHARED_GAS_CONFIG_PATH.getParent());
        }
        Properties props = new Properties();
        props.setProperty("app.gas.shared-url", trimmed);
        try (OutputStream out = Files.newOutputStream(SHARED_GAS_CONFIG_PATH)) {
            props.store(out, "Shared Multi-User GAS Web App URL (ignored by git)");
        }
        try {
            Set<PosixFilePermission> ownerOnly = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(SHARED_GAS_CONFIG_PATH, ownerOnly);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystems
        }
        this.dynamicSharedGasUrl = trimmed;
    }

    public String getSharedGasUrl() {
        if (dynamicSharedGasUrl != null && !dynamicSharedGasUrl.isBlank()) {
            return dynamicSharedGasUrl;
        }
        String envUrl = configuredSharedGasUrl != null ? configuredSharedGasUrl.trim() : "";
        return SecurityUtils.isValidGoogleAppsScriptUrl(envUrl) ? envUrl : "";
    }

    public boolean isSharedGasConfigured() {
        return !getSharedGasUrl().isBlank();
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
        try {
            Set<PosixFilePermission> ownerOnly = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(OAUTH_CONFIG_PATH, ownerOnly);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystems
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
     * GAS ペイロード取り込み結果 DTO（スキャン件数・登録件数・解析できなかったサンプルを含む）。
     */
    public static class ImportResult {
        private final String detectedEmail;
        private final String detectedGasUrl;
        private final int scannedEmailCount;
        private final int syncedCount;
        private final List<String> skippedSamples;

        public ImportResult(
                String detectedEmail,
                String detectedGasUrl,
                int scannedEmailCount,
                int syncedCount,
                List<String> skippedSamples) {
            this.detectedEmail = detectedEmail;
            this.detectedGasUrl = detectedGasUrl;
            this.scannedEmailCount = scannedEmailCount;
            this.syncedCount = syncedCount;
            this.skippedSamples = skippedSamples != null ? skippedSamples : List.of();
        }

        public String getDetectedEmail() {
            return detectedEmail;
        }

        public String getDetectedGasUrl() {
            return detectedGasUrl;
        }

        public int getScannedEmailCount() {
            return scannedEmailCount;
        }

        public int getSyncedCount() {
            return syncedCount;
        }

        public List<String> getSkippedSamples() {
            return skippedSamples;
        }
    }

    /**
     * GAS ウェブアプリからの取得結果 DTO（互換性維持）。
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
     * 千葉工業大学を含む多様な manaba 通知メール形式に対応し、ラベル欠落時も日時ペアと件名からフォールバック抽出する。
     */
    public Optional<ParsedTask> parseManabaEmail(String subject, String body) {
        if ((body == null || body.isBlank()) && (subject == null || subject.isBlank())) {
            return Optional.empty();
        }

        String normalizedBody = (body != null ? body : "").replace("\r\n", "\n").replace("\r", "\n");
        String cleanedSubject = subject != null ? subject.trim() : "";
        String taskUrl = extractTaskUrl(normalizedBody);

        // コースニュース（お知らせ掲示）や掲示板など、小テスト・課題ではないメールを明確に除外する
        if (isNonAssignmentManabaNotice(cleanedSubject, normalizedBody, taskUrl)) {
            return Optional.empty();
        }

        String courseName = extractFirstGroup(COURSE_LINE_PATTERN, normalizedBody);
        String title = extractFirstGroup(TITLE_LINE_PATTERN, normalizedBody);

        LocalDateTime openTime = null;
        LocalDateTime deadline = null;
        boolean hasExplicitDeadline = false;

        String rawOpen = extractFirstGroup(OPEN_TIME_LINE_PATTERN, normalizedBody);
        if (rawOpen != null) {
            openTime = parseDateTime(rawOpen);
        }

        String rawDeadline = extractFirstGroup(DEADLINE_LINE_PATTERN, normalizedBody);
        if (rawDeadline != null) {
            deadline = parseDateTime(rawDeadline);
            if (deadline != null) {
                hasExplicitDeadline = true;
            }
        }

        Matcher periodMatcher = PERIOD_LINE_PATTERN.matcher(normalizedBody);
        if (periodMatcher.find()) {
            if (openTime == null) {
                openTime = parseDateTime(periodMatcher.group(1));
            }
            if (deadline == null) {
                deadline = parseDateTime(periodMatcher.group(2));
                if (deadline != null) {
                    hasExplicitDeadline = true;
                }
            }
        }

        // プロジェクトチーム通知（_teamtop_ 等）は明示的な締切ラベルがない限り課題として取り込まない
        if (!hasExplicitDeadline && taskUrl != null
                && (taskUrl.contains("_teamtop_") || taskUrl.contains("_thread_") || taskUrl.contains("_bbs_"))) {
            return Optional.empty();
        }

        // 1行にラベル・次行に値の形式へのフォールバック（例: "[コース名]\nソフトウェア工学"）
        if (courseName == null) {
            courseName = extractNextLineAfterLabel(normalizedBody, "コース名", "科目名", "コース");
        }
        if (title == null) {
            title = extractNextLineAfterLabel(normalizedBody, "小テスト名", "課題名", "アンケート名", "タイトル");
        }

        // 本文から日時ラベルが見つからない場合：小テスト・課題系メールに限定して本文中の日時出現順からフォールバック抽出
        boolean assignmentHint = isAssignmentMailHint(cleanedSubject, normalizedBody, taskUrl);

        if (openTime == null && deadline == null && assignmentHint) {
            List<LocalDateTime> foundDates = extractAllDateTimes(normalizedBody);
            if (foundDates.size() >= 2) {
                openTime = foundDates.get(0);
                deadline = foundDates.get(1);
            } else if (foundDates.size() == 1) {
                deadline = foundDates.get(0);
            }
        } else if (deadline == null && openTime != null && assignmentHint) {
            List<LocalDateTime> foundDates = extractAllDateTimes(normalizedBody);
            for (LocalDateTime candidate : foundDates) {
                if (!candidate.equals(openTime)) {
                    deadline = candidate;
                    break;
                }
            }
        }

        // 件名・本文先頭行からのコース名・タイトル補完
        if (!cleanedSubject.isBlank()) {
            if (courseName == null) {
                courseName = extractCourseFromSubject(cleanedSubject);
            }
            if (title == null) {
                title = extractTitleFromSubject(cleanedSubject);
            }
        }

        if (assignmentHint && (deadline != null || openTime != null)) {
            if (courseName == null && title != null) {
                String fallbackCourse = stripManabaTag(cleanedSubject);
                courseName = !fallbackCourse.isBlank() ? fallbackCourse : "manaba科目";
            } else if (title == null && courseName != null) {
                String fallbackTitle = stripManabaTag(cleanedSubject);
                title = !fallbackTitle.isBlank() ? fallbackTitle : detectTaskType(cleanedSubject, normalizedBody, taskUrl);
            } else if (courseName == null && title == null) {
                String cleaned = stripManabaTag(cleanedSubject);
                if (!cleaned.isBlank()) {
                    courseName = extractFirstMeaningfulLine(normalizedBody, "manaba科目");
                    title = cleaned;
                }
            }
        }

        if (courseName == null || courseName.isBlank() || title == null || title.isBlank()) {
            return Optional.empty();
        }

        if (deadline == null && openTime == null) {
            return Optional.empty();
        }
        if (deadline == null) {
            // 明示的な締切も2つ目の日時もない場合は課題として誤登録しない（_query_ / _report_ / _survey_ のみ許可）
            boolean isExplicitTaskUrl = taskUrl != null
                    && (taskUrl.contains("_query_") || taskUrl.contains("_report_") || taskUrl.contains("_survey_"));
            if (!isExplicitTaskUrl) {
                return Optional.empty();
            }
            deadline = openTime;
        }

        String taskType = detectTaskType(cleanedSubject, normalizedBody, taskUrl);

        return Optional.of(new ParsedTask(
                courseName.trim(),
                title.trim(),
                openTime,
                deadline,
                taskType,
                taskUrl
        ));
    }

    private boolean isNonAssignmentManabaNotice(String subject, String body, String taskUrl) {
        if (taskUrl != null && taskUrl.contains("_news_")) {
            return true;
        }
        if (subject.contains("コースニュース")
                || body.contains("--コースニュース情報--")
                || body.contains("コースニュースが掲示されました")) {
            return true;
        }
        if (subject.contains("成績公開") || subject.contains("スレッド") || subject.contains("掲示板")) {
            return !body.contains("受付終了") && !body.contains("締切");
        }
        return false;
    }

    private boolean isAssignmentMailHint(String subject, String body, String taskUrl) {
        if (taskUrl != null && (taskUrl.contains("_query_") || taskUrl.contains("_report_") || taskUrl.contains("_survey_"))) {
            return true;
        }
        String combined = subject + "\n" + body;
        return combined.contains("小テスト")
                || combined.contains("アンケート")
                || combined.contains("レポート")
                || combined.contains("課題公開")
                || combined.contains("受付終了")
                || combined.contains("締切")
                || combined.contains("提出期限");
    }

    /**
     * ブラウザ経由 GAS（または貼り付け JSON）から送信された JSON ペイロードを解析し、DB に登録する。
     */
    @Transactional
    public ImportResult importFromGasPayload(String userId, String jsonPayload) {
        if (jsonPayload == null || jsonPayload.isBlank()) {
            return new ImportResult(null, null, 0, 0, List.of());
        }
        if (jsonPayload.length() > MAX_IMPORT_PAYLOAD_CHARS) {
            throw new GmailFetchException("送信されたデータサイズが大きすぎます（上限 2MB）。", false);
        }

        try {
            JsonNode root = objectMapper.readTree(jsonPayload.trim());
            String detectedEmail = root.isObject() ? blankToNull(root.path("email").asText(null)) : null;
            String rawDetectedGasUrl = root.isObject() ? blankToNull(root.path("gasUrl").asText(null)) : null;
            String detectedGasUrl = SecurityUtils.isValidGoogleAppsScriptUrl(rawDetectedGasUrl) ? rawDetectedGasUrl : null;

            if (root.isObject() && root.has("subject") && root.has("body") && !root.has("emails")) {
                String subject = root.path("subject").asText("");
                String from = root.path("from").asText("");
                String body = root.path("body").asText("");
                List<String> skipped = new ArrayList<>();
                Optional<ParsedTask> parsed = parseManabaEmail(subject, body);
                int synced = 0;
                if (parsed.isPresent()) {
                    if (userId != null && !userId.isBlank()) {
                        upsertParsedTask(userId, parsed.get());
                    }
                    synced = 1;
                } else if (isManabaRelated(subject, from, body)) {
                    skipped.add(formatSkippedSample(subject, from, body));
                }
                return new ImportResult(detectedEmail, detectedGasUrl, 1, synced, skipped);
            }

            JsonNode emailsNode = extractEmailsArrayNode(root);
            if (emailsNode == null || !emailsNode.isArray()) {
                return new ImportResult(detectedEmail, detectedGasUrl, 0, 0, List.of());
            }

            int scanned = Math.min(emailsNode.size(), MAX_IMPORT_EMAILS);
            int synced = 0;
            List<String> skippedSamples = new ArrayList<>();

            if (userId != null && !userId.isBlank()) {
                purgeNonAssignmentFalsePositives(userId);
            }

            int processed = 0;
            for (JsonNode item : emailsNode) {
                if (processed >= MAX_IMPORT_EMAILS) {
                    break;
                }
                processed++;
                String subject = item.path("subject").asText("");
                String from = item.path("from").asText("");
                String body = item.path("body").asText("");
                String extractedUrl = extractTaskUrl(body);

                Optional<ParsedTask> parsedOpt = parseManabaEmail(subject, body);
                if (parsedOpt.isPresent()) {
                    if (userId != null && !userId.isBlank()) {
                        upsertParsedTask(userId, parsedOpt.get());
                    }
                    synced++;
                } else if (skippedSamples.size() < MAX_SKIPPED_SAMPLES
                        && isManabaRelated(subject, from, body)
                        && !isNonAssignmentManabaNotice(subject, body, extractedUrl)) {
                    skippedSamples.add(formatSkippedSample(subject, from, body));
                }
            }

            return new ImportResult(detectedEmail, detectedGasUrl, scanned, synced, skippedSamples);
        } catch (IOException ex) {
            log.warn("GAS JSON ペイロードの解析に失敗しました: {}", ex.getMessage());
            throw new GmailFetchException("GAS から受信した JSON の形式が正しくありません。", false, ex);
        }
    }

    private void purgeNonAssignmentFalsePositives(String userId) {
        List<Task> existing = taskRepository.findByUserIdOrderByDeadlineAsc(userId);
        List<String> removedCourses = new ArrayList<>();
        for (Task task : existing) {
            String url = task.getTaskUrl();
            if (url != null && (url.contains("_news_") || url.contains("_teamtop_") || url.contains("_thread_") || url.contains("_bbs_"))) {
                removedCourses.add(task.getCourseName());
                taskRepository.delete(task);
            }
        }
        if (!removedCourses.isEmpty()) {
            List<Task> remaining = taskRepository.findByUserIdOrderByDeadlineAsc(userId);
            for (String course : removedCourses) {
                boolean stillUsed = remaining.stream().anyMatch(t -> course != null && course.equals(t.getCourseName()));
                if (!stillUsed && course != null) {
                    notificationSettingRepository.findByUserIdAndCourseName(userId, course)
                            .ifPresent(notificationSettingRepository::delete);
                }
            }
        }
    }

    /**
     * JSON ペイロードから email / gasUrl メタデータのみを安全に抽出する（ログインユーザー特定用）。
     */
    public ImportResult peekGasPayloadMetadata(String jsonPayload) {
        if (jsonPayload == null || jsonPayload.isBlank() || jsonPayload.length() > MAX_IMPORT_PAYLOAD_CHARS) {
            return new ImportResult(null, null, 0, 0, List.of());
        }
        try {
            JsonNode root = objectMapper.readTree(jsonPayload.trim());
            String detectedEmail = root.isObject() ? blankToNull(root.path("email").asText(null)) : null;
            String rawDetectedGasUrl = root.isObject() ? blankToNull(root.path("gasUrl").asText(null)) : null;
            String detectedGasUrl = SecurityUtils.isValidGoogleAppsScriptUrl(rawDetectedGasUrl) ? rawDetectedGasUrl : null;
            return new ImportResult(detectedEmail, detectedGasUrl, 0, 0, List.of());
        } catch (IOException ex) {
            return new ImportResult(null, null, 0, 0, List.of());
        }
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
                || ("GAS_BROWSER".equalsIgnoreCase(user.getAuthType()) && user.getGasUrl() != null && !user.getGasUrl().isBlank())
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
     * Gmail API アクセストークンの実際の所有者メールアドレスを Google API で検証・取得する（なりすましログイン防止）。
     */
    public String verifyGmailApiProfileEmail(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new GmailFetchException("Gmail API アクセストークンが空です。", true);
        }
        String trimmedToken = accessToken.trim();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://gmail.googleapis.com/gmail/v1/users/me/profile"))
                    .header("Authorization", "Bearer " + trimmedToken)
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() == 401 || res.statusCode() == 403) {
                throw new GmailFetchException(
                        "Gmail API アクセストークンの検証に失敗しました（HTTP " + res.statusCode() + "）。有効なトークンを入力してください。",
                        true);
            }
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                throw new GmailFetchException("Gmail プロフィールの確認に失敗しました。", false);
            }
            JsonNode json = objectMapper.readTree(res.body());
            String email = json.path("emailAddress").asText("").trim().toLowerCase(Locale.ROOT);
            if (email.isBlank() || !email.contains("@")) {
                throw new GmailFetchException("トークンからメールアドレスを取得できませんでした。", true);
            }
            return email;
        } catch (GmailFetchException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GmailFetchException("Gmail API 検証通信が中断されました。", false, ex);
        } catch (IOException ex) {
            throw new GmailFetchException("Gmail API への検証通信に失敗しました。", false, ex);
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
                if (!SecurityUtils.isValidGmailMessageId(msgId)) {
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
     * サーバ直接呼び出しによる GAS ウェブアプリ取得（互換性維持）。
     */
    @Transactional
    public GasFetchResult fetchTasksFromGas(String userId, String gasUrl, String gasToken) {
        return executeGasFetch(userId, null, gasUrl, gasToken);
    }

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
        if (!SecurityUtils.isValidGoogleAppsScriptUrl(trimmedUrl)) {
            throw new GmailFetchException(
                    "セキュリティ保護のため、Google Apps Script の公式ウェブアプリ URL（https://script.google.com/.../exec）のみ接続可能です。",
                    false);
        }

        String requestUrl = trimmedUrl;
        if (gasToken != null && !gasToken.isBlank()) {
            String separator = trimmedUrl.contains("?") ? "&" : "?";
            requestUrl = trimmedUrl + separator + "token=" + urlEncode(gasToken.trim());
        }

        try {
            HttpResponse<String> res = sendGasGetWithStrictRedirects(URI.create(requestUrl));
            String bodyText = res.body() != null ? res.body().trim() : "";

            if (isHtmlResponse(bodyText) || res.statusCode() == 401 || res.statusCode() == 403) {
                throw new GmailFetchException(
                        "GAS ウェブアプリが「自分のみ」で公開されているためサーバ直接アクセスではログイン画面が返りました。"
                                + "「GAS を開いて自分の Gmail から小テストを取得＆ログイン」（ブラウザ経由連携）をご利用ください。",
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
                throw new GmailFetchException("GAS ウェブアプリエラー: " + root.path("error").asText(), true);
            }

            String gasEmail = root.isObject() ? root.path("email").asText("").trim().toLowerCase() : "";
            String resolvedEmail = (optionalEmail != null && !optionalEmail.isBlank())
                    ? optionalEmail.trim().toLowerCase()
                    : (!gasEmail.isBlank() ? gasEmail : "gas-" + shortHash(trimmedUrl) + "@manaba-sync.local");

            String targetUserId = (knownUserId != null && !knownUserId.isBlank())
                    ? knownUserId
                    : generateUserId(resolvedEmail);

            ImportResult result = importFromGasPayload(targetUserId, bodyText);
            return new GasFetchResult(targetUserId, resolvedEmail, result.getScannedEmailCount(), result.getSyncedCount());
        } catch (GmailFetchException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw new GmailFetchException("GAS ウェブアプリ URL の形式が正しくありません。", false, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GmailFetchException("GAS ウェブアプリへの通信が中断されました。", false, ex);
        } catch (IOException ex) {
            log.warn("GAS ウェブアプリ通信エラー: {}", ex.getMessage());
            throw new GmailFetchException("GAS ウェブアプリへの通信または JSON 解析に失敗しました。", false, ex);
        }
    }

    /**
     * SSRF 防止のため、リダイレクト先が Google Apps Script 公式ドメインの場合のみ手動追従する。
     */
    private HttpResponse<String> sendGasGetWithStrictRedirects(URI initialUri) throws IOException, InterruptedException {
        URI current = initialUri;
        for (int hop = 0; hop <= MAX_GAS_REDIRECTS; hop++) {
            HttpRequest req = HttpRequest.newBuilder(current)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> res = noRedirectHttpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = res.statusCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                Optional<String> locationOpt = res.headers().firstValue("Location");
                if (locationOpt.isEmpty()) {
                    return res;
                }
                URI next = current.resolve(locationOpt.get());
                if (!SecurityUtils.isAllowedGasRedirectTarget(next)) {
                    throw new GmailFetchException(
                            "GAS ウェブアプリが Google ログイン画面または許可外ドメインへリダイレクトされました。"
                                    + "大学アカウントでは「ブラウザ経由 GAS 連携（自分のみ公開）」をご利用ください。",
                            true);
                }
                current = next;
                continue;
            }
            return res;
        }
        throw new GmailFetchException("GAS ウェブアプリのリダイレクト回数が上限を超えました。", false);
    }

    /**
     * 貼り付けられた manaba 通知メール本文（1通/複数通）または GAS JSON を解析して登録する。
     */
    @Transactional
    public int importFromRawEmailText(String userId, String rawText) {
        if (userId == null || userId.isBlank() || rawText == null || rawText.isBlank()
                || rawText.length() > MAX_IMPORT_PAYLOAD_CHARS) {
            return 0;
        }

        String trimmed = rawText.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return importFromGasPayload(userId, trimmed).getSyncedCount();
            } catch (GmailFetchException ex) {
                return 0;
            }
        }

        List<String> blocks = splitRawEmailBlocks(rawText);
        int imported = 0;
        int processed = 0;

        for (String block : blocks) {
            if (processed >= MAX_IMPORT_EMAILS) {
                break;
            }
            processed++;
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

    private boolean isManabaRelated(String subject, String from, String body) {
        return containsIgnoreCase(subject, "manaba")
                || containsIgnoreCase(from, "manaba")
                || (subject != null && subject.contains("小テスト"))
                || containsIgnoreCase(body, "manaba")
                || (body != null && body.contains("小テスト"));
    }

    private String formatSkippedSample(String subject, String from, String body) {
        String safeSubject = (subject != null && !subject.isBlank()) ? subject.trim() : "(件名なし)";
        String cleanBody = (body != null ? body : "").replaceAll("\\s+", " ").trim();
        String snippet = cleanBody.length() > 200 ? cleanBody.substring(0, 200) + "..." : cleanBody;
        StringBuilder sb = new StringBuilder();
        sb.append("件名: ").append(safeSubject);
        if (from != null && !from.isBlank()) {
            sb.append(" | 差出人: ").append(from.trim());
        }
        sb.append(" | 本文: ").append(snippet);
        return sb.toString();
    }

    private boolean isHtmlResponse(String bodyText) {
        String lower = bodyText.toLowerCase(Locale.ROOT);
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

    private String extractNextLineAfterLabel(String body, String... labels) {
        String[] lines = body.split("\n");
        for (int i = 0; i < lines.length - 1; i++) {
            String trimmed = lines[i].trim()
                    .replaceAll("^[\\[■◆▼●・\\s]+", "")
                    .replaceAll("[\\]\\s:：]+$", "");
            for (String label : labels) {
                if (trimmed.equals(label)) {
                    String next = lines[i + 1].trim();
                    if (!next.isBlank() && !next.startsWith("[") && !next.startsWith("■")) {
                        return next;
                    }
                }
            }
        }
        return null;
    }

    private List<LocalDateTime> extractAllDateTimes(String text) {
        List<LocalDateTime> list = new ArrayList<>();
        Matcher m = DATETIME_EXTRACT_PATTERN.matcher(text);
        while (m.find()) {
            LocalDateTime dt = parseDateTime(m.group(1));
            if (dt != null) {
                list.add(dt);
            }
        }
        return list;
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
            if (!candidate.equalsIgnoreCase("manaba")
                    && !candidate.contains("リマインダ")
                    && !candidate.contains("お知らせ")) {
                return candidate;
            }
        }
        String stripped = stripManabaTag(subject);
        int slashIdx = stripped.indexOf('/');
        if (slashIdx > 0) {
            return stripped.substring(0, slashIdx).trim();
        }
        return null;
    }

    private String extractTitleFromSubject(String subject) {
        String stripped = stripManabaTag(subject);
        int slashIdx = stripped.indexOf('/');
        if (slashIdx >= 0 && slashIdx + 1 < stripped.length()) {
            return stripped.substring(slashIdx + 1).trim();
        }
        int colonIdx = Math.max(stripped.indexOf(':'), stripped.indexOf('：'));
        if (colonIdx >= 0 && colonIdx + 1 < stripped.length()) {
            return stripped.substring(colonIdx + 1).trim();
        }
        return null;
    }

    private String stripManabaTag(String subject) {
        return subject
                .replaceAll("(?i)\\[\\s*manaba\\s*\\]", "")
                .replaceAll("(?i)【\\s*manaba\\s*】", "")
                .replaceAll("^(?:リマインダ|お知らせ|通知)\\s*[:：]?\\s*", "")
                .trim();
    }

    private String extractFirstMeaningfulLine(String body, String fallback) {
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (t.isBlank()
                    || t.startsWith("http")
                    || t.startsWith("-")
                    || t.startsWith("=")
                    || t.contains("manaba")
                    || DATETIME_EXTRACT_PATTERN.matcher(t).find()) {
                continue;
            }
            return t.length() > 60 ? t.substring(0, 60) : t;
        }
        return fallback;
    }

    private String detectTaskType(String subject, String body, String taskUrl) {
        if (taskUrl != null) {
            if (taskUrl.contains("_query_")) {
                return "小テスト";
            }
            if (taskUrl.contains("_survey_")) {
                return "アンケート";
            }
            if (taskUrl.contains("_report_")) {
                return "レポート";
            }
        }
        String combined = ((subject != null ? subject : "") + "\n" + (body != null ? body : ""));
        if (combined.contains("小テスト")) {
            return "小テスト";
        }
        if (combined.contains("アンケート")) {
            return "アンケート";
        }
        if (combined.contains("レポート")) {
            return "レポート";
        }
        return "小テスト";
    }

    private String extractTaskUrl(String body) {
        Matcher pcMatcher = PC_URL_PATTERN.matcher(body);
        if (pcMatcher.find()) {
            String sanitized = SecurityUtils.sanitizeHttpUrl(pcMatcher.group(1), 500);
            if (sanitized != null) {
                return sanitized;
            }
        }
        Matcher anyMatcher = ANY_URL_PATTERN.matcher(body);
        while (anyMatcher.find()) {
            String url = anyMatcher.group(1).trim();
            if (url.contains("manaba") || url.contains("course") || url.contains("query")) {
                String sanitized = SecurityUtils.sanitizeHttpUrl(url, 500);
                if (sanitized != null) {
                    return sanitized;
                }
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

    private boolean containsIgnoreCase(String text, String needle) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
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
