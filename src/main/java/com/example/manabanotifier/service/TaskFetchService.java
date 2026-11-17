package com.example.manabanotifier.service;

import com.example.manabanotifier.entity.NotificationLog;
import com.example.manabanotifier.entity.NotificationSetting;
import com.example.manabanotifier.entity.Task;
import com.example.manabanotifier.repository.NotificationLogRepository;
import com.example.manabanotifier.repository.NotificationSettingRepository;
import com.example.manabanotifier.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gmail API からの manaba 課題通知メール取得・解析サービス（担当：大友）。
 */
@Service
public class TaskFetchService {

    private static final Logger log = LoggerFactory.getLogger(TaskFetchService.class);

    private static final String DEFAULT_NOTIFY_TIMINGS = "24,5,1";

    private static final Pattern BODY_COURSE_PATTERN =
            Pattern.compile("(?:コース名|科目名|科目)\\s*[:：]\\s*(.+)");
    private static final Pattern BODY_TITLE_PATTERN =
            Pattern.compile("(?:課題名|レポート名|小テスト名|タイトル)\\s*[:：]\\s*(.+)");
    private static final Pattern BODY_DEADLINE_PATTERN =
            Pattern.compile("(?:受付終了日時|締切日時|締め切り日時|提出期限|締切)\\s*[:：]\\s*(\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}日?\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)");
    private static final Pattern SUBJECT_COURSE_PATTERN =
            Pattern.compile("[（(]([^）)]+)[）)]");

    private static final DateTimeFormatter EMAIL_DT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter FLEXIBLE_DT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-M-d H:mm");
    private static final DateTimeFormatter FLEXIBLE_DT_SEC_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-M-d H:mm:ss");

    private final TaskRepository taskRepository;
    private final NotificationSettingRepository notificationSettingRepository;
    private final NotificationLogRepository notificationLogRepository;

    public TaskFetchService(
            TaskRepository taskRepository,
            NotificationSettingRepository notificationSettingRepository,
            NotificationLogRepository notificationLogRepository) {
        this.taskRepository = taskRepository;
        this.notificationSettingRepository = notificationSettingRepository;
        this.notificationLogRepository = notificationLogRepository;
    }

    /**
     * メール解析結果 DTO。
     */
    public static class ParsedTask {
        private final String courseName;
        private final String title;
        private final LocalDateTime deadline;

        public ParsedTask(String courseName, String title, LocalDateTime deadline) {
            this.courseName = courseName;
            this.title = title;
            this.deadline = deadline;
        }

        public String getCourseName() {
            return courseName;
        }

        public String getTitle() {
            return title;
        }

        public LocalDateTime getDeadline() {
            return deadline;
        }
    }

    private record RawEmail(String subject, String body) {
    }

    /**
     * manaba 課題通知メールの件名・本文から科目名・課題名・締切日時を抽出する。
     */
    public Optional<ParsedTask> parseManabaEmail(String subject, String body) {
        if (body == null || body.isBlank()) {
            log.warn("manabaメール本文が空のためスキップしました。subject={}", subject);
            return Optional.empty();
        }

        String courseName = extractFirstGroup(BODY_COURSE_PATTERN, body);
        if (courseName == null && subject != null) {
            courseName = extractFirstGroup(SUBJECT_COURSE_PATTERN, subject);
        }

        String title = extractFirstGroup(BODY_TITLE_PATTERN, body);
        String deadlineStr = extractFirstGroup(BODY_DEADLINE_PATTERN, body);

        if (courseName == null || title == null || deadlineStr == null) {
            log.info("課題通知以外のメールまたは解析対象外メールをスキップしました。subject={}", subject);
            return Optional.empty();
        }

        LocalDateTime deadline = parseDeadline(deadlineStr);
        if (deadline == null) {
            log.warn("締切日時のパースに失敗しました。subject={}, rawDeadline={}", subject, deadlineStr);
            return Optional.empty();
        }

        return Optional.of(new ParsedTask(courseName.trim(), title.trim(), deadline));
    }

    /**
     * Gmail API（デモ環境では manaba 通知メール群の取得・解析シミュレーション）を実行し、
     * 課題・初期通知設定・初期サンプル通知履歴を DB に登録/更新する。
     *
     * @param userId 対象ユーザーID
     * @return 同期（登録・更新）した課題件数
     */
    @Transactional
    public int fetchTasksFromGmail(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0;
        }

        List<RawEmail> emails = fetchSampleManabaEmails();
        List<Task> syncedTasks = new ArrayList<>();

        for (RawEmail email : emails) {
            try {
                Optional<ParsedTask> parsedOpt = parseManabaEmail(email.subject(), email.body());
                if (parsedOpt.isEmpty()) {
                    continue;
                }

                ParsedTask parsed = parsedOpt.get();
                Task savedTask = upsertTask(userId, parsed);
                syncedTasks.add(savedTask);

                ensureDefaultNotificationSetting(userId, parsed.getCourseName());
            } catch (Exception ex) {
                // 詳細設計書 9.1: Gmail API通信エラーやメール解析失敗時はログ記録してスキップ
                log.error("メール解析・課題登録中にエラーが発生したためスキップします: subject={}", email.subject(), ex);
            }
        }

        seedInitialNotificationLogsIfEmpty(userId, syncedTasks);
        return syncedTasks.size();
    }

    private Task upsertTask(String userId, ParsedTask parsed) {
        Optional<Task> existingOpt = taskRepository.findByUserIdAndTitleAndCourseName(
                userId, parsed.getTitle(), parsed.getCourseName());

        if (existingOpt.isPresent()) {
            Task existing = existingOpt.get();
            if (!parsed.getDeadline().equals(existing.getDeadline())) {
                existing.setDeadline(parsed.getDeadline());
                log.info("既存課題の締切日時を更新しました: course={}, title={}, deadline={}",
                        existing.getCourseName(), existing.getTitle(), existing.getDeadline());
            }
            return taskRepository.save(existing);
        }

        Task task = new Task();
        task.setUserId(userId);
        task.setCourseName(parsed.getCourseName());
        task.setTitle(parsed.getTitle());
        task.setDeadline(parsed.getDeadline());
        task.setSubmitted(false);
        return taskRepository.save(task);
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

    private void seedInitialNotificationLogsIfEmpty(String userId, List<Task> tasks) {
        if (tasks.isEmpty() || !notificationLogRepository.findByUserIdOrderBySentAtDesc(userId).isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        Task firstTask = tasks.get(0);
        NotificationLog successLog = new NotificationLog();
        successLog.setTaskId(firstTask.getTaskId());
        successLog.setUserId(userId);
        successLog.setSentAt(now.minusHours(2));
        successLog.setStatus("SUCCESS");
        successLog.setMessage("[24時間前] " + firstTask.getCourseName() + "「" + firstTask.getTitle() + "」の締切が近づいています。");
        notificationLogRepository.save(successLog);

        if (tasks.size() > 1) {
            Task secondTask = tasks.get(1);
            NotificationLog failedLog = new NotificationLog();
            failedLog.setTaskId(secondTask.getTaskId());
            failedLog.setUserId(userId);
            failedLog.setSentAt(now.minusMinutes(35));
            failedLog.setStatus("FAILED");
            failedLog.setMessage("[24時間前] " + secondTask.getCourseName() + "「" + secondTask.getTitle() + "」の通知送信に失敗（ネットワーク一時障害）");
            notificationLogRepository.save(failedLog);
        }
    }

    private List<RawEmail> fetchSampleManabaEmails() {
        LocalDateTime base = LocalDateTime.now().withSecond(0).withNano(0);
        List<RawEmail> list = new ArrayList<>();

        list.add(buildManabaEmail(
                "ソフトウェア工学",
                "第8回 詳細設計書レビュー課題",
                base.plusHours(4)
        ));
        list.add(buildManabaEmail(
                "データベースシステム",
                "ER図およびSQL正規化レポート",
                base.plusHours(21)
        ));
        list.add(buildManabaEmail(
                "ソフトウェア工学",
                "結合テスト計画書ドラフト",
                base.plusDays(1).plusHours(6)
        ));
        list.add(buildManabaEmail(
                "コンピュータネットワーク",
                "TCP/IPパケット解析演習",
                base.plusDays(2).plusHours(12)
        ));
        list.add(buildManabaEmail(
                "人工知能基礎",
                "探索アルゴリズム実装課題",
                base.plusDays(5)
        ));
        // 解析対象外のお知らせメール（エラー耐性・スキップ動作の検証用）
        list.add(new RawEmail(
                "[manaba] システムメンテナンスのお知らせ",
                "深夜 02:00〜04:00 の間、定期メンテナンスを実施します。"
        ));

        return list;
    }

    private RawEmail buildManabaEmail(String courseName, String title, LocalDateTime deadline) {
        String subject = "[manaba] レポート課題公開のお知らせ（" + courseName + "）";
        String body = """
                manaba から新しい課題のお知らせです。

                コース名: %s
                課題名: %s
                受付終了日時: %s

                manaba にログインして内容を確認し、期限内に提出してください。
                """.formatted(courseName, title, deadline.format(EMAIL_DT_FORMATTER));
        return new RawEmail(subject, body);
    }

    private String extractFirstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private LocalDateTime parseDeadline(String raw) {
        String normalized = raw
                .replace("年", "-")
                .replace("月", "-")
                .replace("日", "")
                .replace("/", "-")
                .replaceAll("\\s+", " ")
                .trim();

        for (DateTimeFormatter formatter : List.of(FLEXIBLE_DT_FORMATTER, FLEXIBLE_DT_SEC_FORMATTER)) {
            try {
                return LocalDateTime.parse(normalized, formatter);
            } catch (DateTimeParseException ignored) {
                // try next pattern
            }
        }
        return null;
    }
}
