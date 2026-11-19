package com.example.manabanotifier.service;

import com.example.manabanotifier.entity.NotificationLog;
import com.example.manabanotifier.entity.NotificationSetting;
import com.example.manabanotifier.entity.Task;
import com.example.manabanotifier.entity.User;
import com.example.manabanotifier.repository.NotificationLogRepository;
import com.example.manabanotifier.repository.NotificationSettingRepository;
import com.example.manabanotifier.repository.TaskRepository;
import com.example.manabanotifier.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 機能4：通知送信スケジューラ（担当：川邉）
 *
 * 定期的に未提出課題を確認し、ユーザーの通知設定（科目別ON/OFF・通知タイミング）に
 * 該当する課題に対して Web Push 通知を送信し、結果を通知履歴へ記録する。
 */
@Service
public class NotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);
    private static final String DEFAULT_TIMINGS = "24,5,1";
    private static final int MAX_MESSAGE_LENGTH = 250;
    private static final DateTimeFormatter DEADLINE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    private final TaskRepository taskRepository;
    private final NotificationSettingRepository notificationSettingRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final UserRepository userRepository;

    public NotificationScheduler(
            TaskRepository taskRepository,
            NotificationSettingRepository notificationSettingRepository,
            NotificationLogRepository notificationLogRepository,
            UserRepository userRepository) {
        this.taskRepository = taskRepository;
        this.notificationSettingRepository = notificationSettingRepository;
        this.notificationLogRepository = notificationLogRepository;
        this.userRepository = userRepository;
    }

    /**
     * 定期実行による通知判定・配信結果サマリ。
     */
    public record CheckSummary(int checkedCount, int sentCount, int failedCount, int skippedCount) {
    }

    /**
     * パース済みの通知タイミング（締切からの残り時間閾値と表示タグ）。
     */
    private record TimingThreshold(long thresholdSeconds, String tag) {
    }

    /**
     * 1分ごとにバックグラウンド起動し、全ユーザーの未提出課題に対する通知判定・送信を行う。
     */
    @Scheduled(fixedRate = 60000)
    public void checkAndSendNotifications() {
        LocalDateTime now = LocalDateTime.now();
        List<Task> unsubmittedTasks;
        try {
            unsubmittedTasks = taskRepository.findBySubmittedFalse();
        } catch (Exception e) {
            log.error("未提出課題の取得に失敗しました。次回実行時に再試行します。", e);
            return;
        }

        if (unsubmittedTasks == null || unsubmittedTasks.isEmpty()) {
            return;
        }

        CheckSummary summary = evaluateAndSendForTasks(unsubmittedTasks, now);
        if (summary.sentCount() > 0 || summary.failedCount() > 0) {
            log.info("定期通知チェック完了: checked={}, sent={}, failed={}, skipped={}",
                    summary.checkedCount(), summary.sentCount(),
                    summary.failedCount(), summary.skippedCount());
        }
    }

    /**
     * ログインユーザー向けの手動通知チェック実行（デモ・動作確認用）。
     *
     * @param userId 対象ユーザーID
     * @return チェック・送信結果のサマリ
     */
    public CheckSummary triggerManualCheckForUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return new CheckSummary(0, 0, 0, 0);
        }
        LocalDateTime now = LocalDateTime.now();
        List<Task> userTasks = taskRepository.findByUserIdOrderByDeadlineAsc(userId);
        List<Task> unsubmittedTasks = userTasks.stream()
                .filter(task -> !task.isSubmitted())
                .toList();
        return evaluateAndSendForTasks(unsubmittedTasks, now);
    }

    /**
     * 送信失敗（FAILED）ログの単一再試行処理（機能5：再試行）。
     *
     * @param logId         再試行対象の通知履歴ID
     * @param userId        実行ユーザーID（所有者チェック用）
     * @return 再送信成功なら true、失敗なら false
     */
    public boolean retryNotification(Long logId, String userId) {
        return retryNotification(logId, userId, false);
    }

    /**
     * 送信失敗（FAILED）ログの単一再試行処理（エラーテスト用フラグ付き）。
     *
     * @param logId         再試行対象の通知履歴ID
     * @param userId        実行ユーザーID
     * @param simulateError エラー再現用フラグ（true の場合は送信失敗をシミュレート）
     * @return 再送信成功なら true、失敗なら false
     */
    public boolean retryNotification(Long logId, String userId, boolean simulateError) {
        if (logId == null || userId == null || userId.isBlank()) {
            return false;
        }

        Optional<NotificationLog> logOpt = notificationLogRepository.findById(logId);
        if (logOpt.isEmpty()) {
            return false;
        }

        NotificationLog targetLog = logOpt.get();
        if (!userId.equals(targetLog.getUserId())) {
            log.warn("他ユーザーの通知ログ再試行を拒否しました: logId={}, userId={}", logId, userId);
            return false;
        }

        User user = userRepository.findById(userId).orElse(null);
        Task task = targetLog.getTaskId() != null
                ? taskRepository.findById(targetLog.getTaskId())
                        .filter(t -> userId.equals(t.getUserId()))
                        .orElse(null)
                : null;

        try {
            dispatchWebPush(user, task, targetLog.getMessage(), simulateError);
            targetLog.setStatus("SUCCESS");
            targetLog.setSentAt(LocalDateTime.now());
            targetLog.setMessage(buildRetrySuccessMessage(targetLog.getMessage()));
            notificationLogRepository.save(targetLog);
            log.info("通知再送信に成功しました: logId={}, userId={}", logId, userId);
            return true;
        } catch (Exception e) {
            targetLog.setStatus("FAILED");
            targetLog.setSentAt(LocalDateTime.now());
            notificationLogRepository.save(targetLog);
            log.warn("通知再送信に失敗しました: logId={}, userId={}, reason={}",
                    logId, userId, e.getMessage());
            return false;
        }
    }

    /**
     * デモ・再試行機能テスト用に、FAILED 状態の通知ログを1件生成する。
     *
     * @param userId 対象ユーザーID
     * @return 生成・保存された FAILED ログ
     */
    public NotificationLog simulateFailedNotification(String userId) {
        List<Task> tasks = taskRepository.findByUserIdOrderByDeadlineAsc(userId);
        Task sampleTask = tasks.stream()
                .filter(t -> !t.isSubmitted())
                .findFirst()
                .orElse(tasks.isEmpty() ? null : tasks.get(0));

        NotificationLog failedLog = new NotificationLog();
        failedLog.setUserId(userId);
        failedLog.setSentAt(LocalDateTime.now());
        failedLog.setStatus("FAILED");

        if (sampleTask != null) {
            failedLog.setTaskId(sampleTask.getTaskId());
            String deadlineText = sampleTask.getDeadline() != null
                    ? sampleTask.getDeadline().format(DEADLINE_FORMATTER)
                    : "未設定";
            failedLog.setMessage(truncateMessage(String.format(
                    "[テスト失敗] %s「%s」（締切: %s）- Web Push配信エラー（通信タイムアウト模擬）",
                    safeStr(sampleTask.getCourseName(), "科目未設定"),
                    safeStr(sampleTask.getTitle(), "課題"),
                    deadlineText)));
        } else {
            failedLog.setTaskId(null);
            failedLog.setMessage(truncateMessage(
                    "[テスト失敗] サンプル課題通知 - Web Push配信エラー（購読情報エラー模擬）"));
        }

        return notificationLogRepository.save(failedLog);
    }

    private CheckSummary evaluateAndSendForTasks(List<Task> tasks, LocalDateTime now) {
        int checked = 0;
        int sent = 0;
        int failed = 0;
        int skipped = 0;

        for (Task task : tasks) {
            checked++;
            try {
                if (task.isSubmitted() || task.getDeadline() == null || task.getUserId() == null) {
                    skipped++;
                    continue;
                }

                // 締切を既に過ぎている課題は事前通知対象外
                Duration remaining = Duration.between(now, task.getDeadline());
                if (remaining.isNegative()) {
                    skipped++;
                    continue;
                }

                // 科目別の通知設定を確認（設定レコードがない場合はユーザー共通設定またはデフォルトを使用）
                CourseNotificationRule rule = resolveCourseRule(task.getUserId(), task.getCourseName());
                if (!rule.enabled()) {
                    skipped++;
                    continue;
                }

                // 通知タイミング文字列をパースし、現在時刻に該当する最も近いタイミングを取得
                Optional<TimingThreshold> matchedTimingOpt =
                        findMatchedTiming(remaining.toSeconds(), rule.notifyTimings());
                if (matchedTimingOpt.isEmpty()) {
                    skipped++;
                    continue;
                }

                TimingThreshold timing = matchedTimingOpt.get();
                // 同一課題・同一タイミングタグでの SUCCESS 重複送信を防止
                boolean alreadySent = notificationLogRepository
                        .existsByTaskIdAndUserIdAndMessageContainingAndStatus(
                                task.getTaskId(), task.getUserId(), timing.tag(), "SUCCESS");
                if (alreadySent) {
                    skipped++;
                    continue;
                }

                String message = buildNotificationMessage(task, timing.tag());
                User user = userRepository.findById(task.getUserId()).orElse(null);

                NotificationLog entry = new NotificationLog();
                entry.setTaskId(task.getTaskId());
                entry.setUserId(task.getUserId());
                entry.setSentAt(now);

                try {
                    dispatchWebPush(user, task, message, false);
                    entry.setStatus("SUCCESS");
                    entry.setMessage(truncateMessage(message));
                    notificationLogRepository.save(entry);
                    sent++;
                } catch (Exception pushEx) {
                    entry.setStatus("FAILED");
                    entry.setMessage(truncateMessage(message + "（送信失敗: " + pushEx.getMessage() + "）"));
                    notificationLogRepository.save(entry);
                    failed++;
                    log.warn("Web Push 通知送信に失敗しました（スケジューラは継続します）: taskId={}, userId={}, error={}",
                            task.getTaskId(), task.getUserId(), pushEx.getMessage());
                }
            } catch (Exception taskEx) {
                failed++;
                log.error("課題の通知判定中に予期しないエラーが発生しました: taskId={}",
                        task.getTaskId(), taskEx);
            }
        }

        return new CheckSummary(checked, sent, failed, skipped);
    }

    private record CourseNotificationRule(boolean enabled, String notifyTimings) {
    }

    private CourseNotificationRule resolveCourseRule(String userId, String courseName) {
        if (courseName != null && !courseName.isBlank()) {
            Optional<NotificationSetting> courseSettingOpt =
                    notificationSettingRepository.findByUserIdAndCourseName(userId, courseName);
            if (courseSettingOpt.isPresent()) {
                NotificationSetting setting = courseSettingOpt.get();
                String timings = (setting.getNotifyTimings() != null && !setting.getNotifyTimings().isBlank())
                        ? setting.getNotifyTimings()
                        : resolveUserFallbackTimings(userId);
                return new CourseNotificationRule(setting.isEnabled(), timings);
            }
        }
        return new CourseNotificationRule(true, resolveUserFallbackTimings(userId));
    }

    private String resolveUserFallbackTimings(String userId) {
        List<NotificationSetting> userSettings = notificationSettingRepository.findByUserId(userId);
        if (userSettings != null) {
            for (NotificationSetting s : userSettings) {
                if (s.getNotifyTimings() != null && !s.getNotifyTimings().isBlank()) {
                    return s.getNotifyTimings();
                }
            }
        }
        return DEFAULT_TIMINGS;
    }

    /**
     * カンマ区切りの通知タイミング（例: "24,5,1"）を解析し、
     * 残り時間（秒）が該当する最も近い（最小の）閾値を返す。
     */
    private Optional<TimingThreshold> findMatchedTiming(long remainingSeconds, String notifyTimings) {
        if (notifyTimings == null || notifyTimings.isBlank()) {
            return Optional.empty();
        }
        List<TimingThreshold> thresholds = parseTimingThresholds(notifyTimings);
        for (TimingThreshold threshold : thresholds) {
            if (remainingSeconds <= threshold.thresholdSeconds()) {
                return Optional.of(threshold);
            }
        }
        return Optional.empty();
    }

    private List<TimingThreshold> parseTimingThresholds(String notifyTimings) {
        List<TimingThreshold> list = new ArrayList<>();
        String[] parts = notifyTimings.split(",");
        for (String raw : parts) {
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                if (token.toLowerCase(Locale.ROOT).endsWith("m")) {
                    int minutes = Integer.parseInt(token.substring(0, token.length() - 1).trim());
                    if (minutes > 0) {
                        list.add(new TimingThreshold(minutes * 60L, "[" + minutes + "分前]"));
                    }
                } else {
                    int hours = Integer.parseInt(token);
                    if (hours > 0) {
                        list.add(new TimingThreshold(hours * 3600L, "[" + hours + "時間前]"));
                    }
                }
            } catch (NumberFormatException ex) {
                try {
                    double fractionalHours = Double.parseDouble(token);
                    if (fractionalHours > 0) {
                        long seconds = Math.round(fractionalHours * 3600.0);
                        String tag = fractionalHours < 1.0
                                ? "[" + Math.round(fractionalHours * 60.0) + "分前]"
                                : "[" + token + "時間前]";
                        list.add(new TimingThreshold(seconds, tag));
                    }
                } catch (NumberFormatException ignored) {
                    log.debug("無効な通知タイミング指定をスキップしました: '{}'", token);
                }
            }
        }
        list.sort(Comparator.comparingLong(TimingThreshold::thresholdSeconds));
        return list;
    }

    /**
     * Web Push API を用いた通知配信処理（デモ環境では購読情報検証＋ブラウザ通知配信シミュレーション）。
     */
    private void dispatchWebPush(User user, Task task, String payloadMessage, boolean simulateError) {
        if (simulateError) {
            throw new IllegalStateException("スマートフォンの通知許可または購読情報が無効です");
        }

        if (user != null && user.getPushEndpoint() != null) {
            String endpoint = user.getPushEndpoint().trim().toUpperCase(Locale.ROOT);
            if (endpoint.startsWith("ERROR")
                    || endpoint.startsWith("INVALID")
                    || endpoint.startsWith("DENIED")
                    || endpoint.startsWith("EXPIRED")) {
                throw new IllegalStateException("Web Push 購読情報が無効または期限切れです");
            }
        }

        // 実稼働時は user.getPushEndpoint() / user.getPushKeys() を用いて Web Push (VAPID) 送信を行う。
        // 本アプリではブラウザ側 /api/notifications/recent ポーリングとも連携し、
        // スマートフォン / PC ブラウザの Notification API で即座にプッシュ通知を表示する。
        boolean hasEndpoint = user != null && user.getPushEndpoint() != null && !user.getPushEndpoint().isBlank();
        Long taskId = task != null ? task.getTaskId() : null;
        log.info("Web Push 通知配信実行: hasEndpoint={}, taskId={}", hasEndpoint, taskId);
    }

    private String buildNotificationMessage(Task task, String timingTag) {
        String deadlineText = task.getDeadline().format(DEADLINE_FORMATTER);
        return String.format("%s %s「%s」（締切: %s）",
                timingTag,
                safeStr(task.getCourseName(), "科目未設定"),
                safeStr(task.getTitle(), "無題の課題"),
                deadlineText);
    }

    private String buildRetrySuccessMessage(String originalMessage) {
        if (originalMessage == null || originalMessage.isBlank()) {
            return "[再送信成功] 課題締切のリマインダー通知を再配信しました";
        }
        String cleaned = originalMessage
                .replace("- Web Push配信エラー（通信タイムアウト模擬）", "")
                .replace("- Web Push配信エラー（購読情報エラー模擬）", "")
                .trim();
        if (!cleaned.contains("[再送成功]")) {
            cleaned = cleaned + " [再送成功]";
        }
        return truncateMessage(cleaned);
    }

    private String safeStr(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private String truncateMessage(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_MESSAGE_LENGTH ? text : text.substring(0, MAX_MESSAGE_LENGTH - 3) + "...";
    }
}
