package com.example.manabanotifier.service;

import com.example.manabanotifier.entity.NotificationSetting;
import com.example.manabanotifier.entity.Task;
import com.example.manabanotifier.entity.User;
import com.example.manabanotifier.repository.NotificationSettingRepository;
import com.example.manabanotifier.repository.TaskRepository;
import com.example.manabanotifier.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.search.BodyTerm;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.OrTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SubjectTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 実際の Gmail (IMAPS / Gmail REST API) および manaba 通知メール本文から
 * 小テスト・課題情報を取得・解析するサービス（担当：大友）。
 */
@Service
public class TaskFetchService {

    private static final Logger log = LoggerFactory.getLogger(TaskFetchService.class);

    private static final String DEFAULT_NOTIFY_TIMINGS = "24,5,1";
    private static final int MAX_IMAP_MESSAGES_TO_PARSE = 60;

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
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Gmail 取得時の例外（認証エラーか通信・設定エラーかを区別する）。
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

        // 件名からのフォールバック補完
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
            // 公開通知のみで締切日時が省略されていた場合の安全フォールバック
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
     * ログイン済みユーザーの認証情報（Google アプリパスワード IMAP または OAuth 2.0 アクセストークン）を用いて
     * 実際の Gmail から manaba の小テスト・課題通知メールを取得し、DB に登録/更新する。
     */
    @Transactional
    public int fetchTasksFromGmail(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new GmailFetchException("ログインユーザー情報が見つかりません。", true);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new GmailFetchException("ユーザー情報が見つかりません。", true));

        if (user.getImapPassword() != null && !user.getImapPassword().isBlank()) {
            return fetchTasksFromGmailImap(userId, user.getEmail(), user.getImapPassword());
        }

        if (user.getAccessToken() != null && !user.getAccessToken().isBlank()) {
            return fetchTasksFromGmailApi(userId, user.getAccessToken());
        }

        throw new GmailFetchException(
                "Gmail 連携情報（Googleアプリパスワード または OAuth 2.0 アクセストークン）が未設定です。"
                        + "メール本文貼り付け、または再ログインで連携してください。",
                false);
    }

    /**
     * Gmail IMAPS (imap.gmail.com:993) に接続して manaba 小テスト・課題通知メールを取得・保存する。
     */
    @Transactional
    public int fetchTasksFromGmailImap(String userId, String email, String rawAppPassword) {
        if (email == null || email.isBlank() || rawAppPassword == null || rawAppPassword.isBlank()) {
            throw new GmailFetchException("メールアドレスと Google アプリパスワードを入力してください。", true);
        }

        String cleanPassword = rawAppPassword.replaceAll("\\s+", "");

        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", "imap.gmail.com");
        props.put("mail.imaps.port", "993");
        props.put("mail.imaps.ssl.enable", "true");
        props.put("mail.imaps.connectiontimeout", "10000");
        props.put("mail.imaps.timeout", "15000");
        props.put("mail.imaps.writetimeout", "10000");

        Store store = null;
        Folder folder = null;
        int syncedCount = 0;

        try {
            Session mailSession = Session.getInstance(props);
            store = mailSession.getStore("imaps");
            store.connect("imap.gmail.com", 993, email.trim(), cleanPassword);

            folder = openTargetFolder(store);
            folder.open(Folder.READ_ONLY);

            SearchTerm searchTerm = new OrTerm(new SearchTerm[]{
                    new FromStringTerm("manaba"),
                    new SubjectTerm("manaba"),
                    new SubjectTerm("小テスト"),
                    new BodyTerm("manaba")
            });

            Message[] matched = folder.search(searchTerm);
            if (matched == null || matched.length == 0) {
                log.info("Gmail IMAP 接続成功（対象 manaba メール 0 件）: email={}", email);
                return 0;
            }

            int start = Math.max(0, matched.length - MAX_IMAP_MESSAGES_TO_PARSE);
            for (int i = matched.length - 1; i >= start; i--) {
                Message msg = matched[i];
                try {
                    String subject = msg.getSubject();
                    String body = extractTextFromPart(msg);
                    Optional<ParsedTask> parsedOpt = parseManabaEmail(subject, body);
                    if (parsedOpt.isPresent()) {
                        upsertParsedTask(userId, parsedOpt.get());
                        syncedCount++;
                    }
                } catch (Exception ex) {
                    log.warn("Gmail メッセージ1件の解析中にエラーが発生したためスキップします: {}", ex.getMessage());
                }
            }

            log.info("Gmail IMAP 同期完了: email={}, matched={}, syncedTasks={}", email, matched.length, syncedCount);
            return syncedCount;

        } catch (AuthenticationFailedException ex) {
            log.warn("Gmail IMAP 認証失敗: email={}, reason={}", email, ex.getMessage());
            throw new GmailFetchException(
                    "Gmail（imap.gmail.com）への接続認証に失敗しました。"
                            + "Googleの仕様上、通常のログインパスワードでは外部アプリからメールを取得できません。"
                            + "Googleアカウントの『16桁のアプリパスワード』（https://myaccount.google.com/apppasswords）を入力するか、OAuth 2.0 トークンで連携してください。",
                    true,
                    ex);
        } catch (MessagingException ex) {
            log.error("Gmail IMAP 通信エラー: email={}, error={}", email, ex.getMessage(), ex);
            throw new GmailFetchException(
                    "Gmail（imap.gmail.com）への接続中にエラーが発生しました: " + ex.getMessage(),
                    false,
                    ex);
        } finally {
            closeQuietly(folder, store);
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
            String query = URLEncoder.encode("manaba OR 小テスト", StandardCharsets.UTF_8);
            URI listUri = URI.create(
                    "https://gmail.googleapis.com/gmail/v1/users/me/messages?q=" + query + "&maxResults=50");

            HttpRequest listReq = HttpRequest.newBuilder(listUri)
                    .header("Authorization", "Bearer " + trimmedToken)
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> listRes = httpClient.send(listReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
     * 貼り付けられた manaba 通知メール本文（1通または複数通分）を解析して DB に登録・更新する。
     */
    @Transactional
    public int importFromRawEmailText(String userId, String rawText) {
        if (userId == null || userId.isBlank() || rawText == null || rawText.isBlank()) {
            return 0;
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
            // 既に提出済みの状態は上書きせず、締切・公開日時・URLのみ最新化する
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

    private Folder openTargetFolder(Store store) throws MessagingException {
        for (String candidate : List.of("[Gmail]/すべてのメール", "[Gmail]/All Mail", "INBOX")) {
            Folder f = store.getFolder(candidate);
            if (f != null && f.exists()) {
                return f;
            }
        }
        return store.getFolder("INBOX");
    }

    private void closeQuietly(Folder folder, Store store) {
        if (folder != null) {
            try {
                if (folder.isOpen()) {
                    folder.close(false);
                }
            } catch (Exception ignored) {
            }
        }
        if (store != null) {
            try {
                if (store.isConnected()) {
                    store.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private String extractTextFromPart(Part part) throws MessagingException, IOException {
        if (part == null) {
            return "";
        }
        if (part.isMimeType("text/plain")) {
            Object content = part.getContent();
            return content != null ? content.toString() : "";
        }
        if (part.isMimeType("text/html")) {
            Object content = part.getContent();
            return htmlToPlainText(content != null ? content.toString() : "");
        }
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            StringBuilder plain = new StringBuilder();
            StringBuilder htmlFallback = new StringBuilder();
            for (int i = 0; i < mp.getCount(); i++) {
                Part bp = mp.getBodyPart(i);
                if (bp.isMimeType("text/plain")) {
                    plain.append(extractTextFromPart(bp)).append('\n');
                } else if (bp.isMimeType("text/html")) {
                    htmlFallback.append(extractTextFromPart(bp)).append('\n');
                } else if (bp.isMimeType("multipart/*")) {
                    String nested = extractTextFromPart(bp);
                    if (!nested.isBlank()) {
                        plain.append(nested).append('\n');
                    }
                }
            }
            return !plain.toString().isBlank() ? plain.toString() : htmlFallback.toString();
        }
        return "";
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
        String text = html
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
        return text;
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

        // "2026-11-18 23:" のように分が省略された形式への補完
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
}
