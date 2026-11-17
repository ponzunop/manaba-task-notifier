package com.example.manabanotifier.controller;

import com.example.manabanotifier.entity.NotificationLog;
import com.example.manabanotifier.entity.Task;
import com.example.manabanotifier.entity.User;
import com.example.manabanotifier.repository.NotificationLogRepository;
import com.example.manabanotifier.repository.TaskRepository;
import com.example.manabanotifier.repository.UserRepository;
import com.example.manabanotifier.service.NotificationScheduler;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 機能5：通知履歴・再試行コントローラ（担当：川邉）
 *
 * 通知送信結果（SUCCESS / FAILED）の一覧表示、失敗通知の再試行、
 * デモ用チェック／失敗シミュレーション、およびブラウザPush通知ポーリングAPIを提供する。
 */
@Controller
public class HistoryController {

    private static final Pattern TIMING_TAG_PATTERN = Pattern.compile("^\\[([^\\]]+)]");
    private static final DateTimeFormatter DISPLAY_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    private final NotificationLogRepository notificationLogRepository;
    private final TaskRepository taskRepository;
    private final UserRepository userRepository;
    private final NotificationScheduler notificationScheduler;

    public HistoryController(
            NotificationLogRepository notificationLogRepository,
            TaskRepository taskRepository,
            UserRepository userRepository,
            NotificationScheduler notificationScheduler) {
        this.notificationLogRepository = notificationLogRepository;
        this.taskRepository = taskRepository;
        this.userRepository = userRepository;
        this.notificationScheduler = notificationScheduler;
    }

    /**
     * 画面表示用の通知履歴ビューモデル。
     */
    public record HistoryItem(
            Long logId,
            Long taskId,
            LocalDateTime sentAt,
            String status,
            String message,
            String timingLabel,
            String courseName,
            String taskTitle,
            LocalDateTime deadline,
            boolean submitted
    ) {
    }

    /**
     * ブラウザ Push 通知ポーリング API 用の JSON DTO。
     */
    public record RecentNotificationDto(
            Long logId,
            Long taskId,
            String title,
            String courseName,
            String deadline,
            String message,
            LocalDateTime sentAt,
            String status,
            String url
    ) {
    }

    /**
     * 通知履歴画面の表示（GET /history）。
     */
    @GetMapping("/history")
    public String showHistory(
            HttpSession session,
            @CookieValue(value = "user_id", required = false) String cookieUserId,
            Model model) {

        String userId = resolveUserId(session, cookieUserId);
        if (userId == null) {
            return "redirect:/login";
        }

        List<NotificationLog> logs = notificationLogRepository.findByUserIdOrderBySentAtDesc(userId);
        Map<Long, Task> taskMap = loadTaskMapForUser(userId);

        List<HistoryItem> historyItems = new ArrayList<>(logs.size());
        long successCount = 0;
        long failedCount = 0;

        for (NotificationLog entry : logs) {
            if ("SUCCESS".equalsIgnoreCase(entry.getStatus())) {
                successCount++;
            } else if ("FAILED".equalsIgnoreCase(entry.getStatus())) {
                failedCount++;
            }

            Task task = resolveTask(entry.getTaskId(), taskMap);
            String courseName = task != null && task.getCourseName() != null
                    ? task.getCourseName() : "—";
            String taskTitle = task != null && task.getTitle() != null
                    ? task.getTitle() : "（課題情報なし）";
            LocalDateTime deadline = task != null ? task.getDeadline() : null;
            boolean submitted = task != null && task.isSubmitted();

            historyItems.add(new HistoryItem(
                    entry.getLogId(),
                    entry.getTaskId(),
                    entry.getSentAt(),
                    entry.getStatus(),
                    entry.getMessage(),
                    extractTimingLabel(entry.getMessage()),
                    courseName,
                    taskTitle,
                    deadline,
                    submitted
            ));
        }

        User currentUser = userRepository.findById(userId).orElse(null);

        model.addAttribute("historyItems", historyItems);
        model.addAttribute("totalCount", historyItems.size());
        model.addAttribute("successCount", successCount);
        model.addAttribute("failedCount", failedCount);
        model.addAttribute("currentUser", currentUser);
        model.addAttribute("userId", userId);

        return "history";
    }

    /**
     * 失敗した通知の再試行処理（POST /history/{logId}/retry）。
     */
    @PostMapping("/history/{logId}/retry")
    public String retryNotification(
            @PathVariable("logId") Long logId,
            @RequestParam(value = "simulateError", defaultValue = "false") boolean simulateError,
            HttpSession session,
            @CookieValue(value = "user_id", required = false) String cookieUserId,
            RedirectAttributes redirectAttributes) {

        String userId = resolveUserId(session, cookieUserId);
        if (userId == null) {
            return "redirect:/login";
        }

        boolean success = notificationScheduler.retryNotification(logId, userId, simulateError);
        if (success) {
            redirectAttributes.addFlashAttribute("successMessage",
                    "通知を再送信しました（送信履歴を SUCCESS に更新しました）。");
        } else {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "再送信に失敗しました。スマートフォンの通知許可設定を確認してください");
        }

        return "redirect:/history";
    }

    /**
     * 今すぐ未提出課題の通知チェックを実行するデモ用エンドポイント（POST /history/trigger-check）。
     */
    @PostMapping("/history/trigger-check")
    public String triggerCheck(
            HttpSession session,
            @CookieValue(value = "user_id", required = false) String cookieUserId,
            RedirectAttributes redirectAttributes) {

        String userId = resolveUserId(session, cookieUserId);
        if (userId == null) {
            return "redirect:/login";
        }

        NotificationScheduler.CheckSummary summary =
                notificationScheduler.triggerManualCheckForUser(userId);

        if (summary.sentCount() > 0 && summary.failedCount() == 0) {
            redirectAttributes.addFlashAttribute("successMessage",
                    String.format("通知チェックを実行し、%d件の Web Push 通知を送信しました。", summary.sentCount()));
        } else if (summary.failedCount() > 0) {
            redirectAttributes.addFlashAttribute("warningMessage",
                    String.format("通知チェックを実行しました（送信成功: %d件 / 送信失敗: %d件）。失敗分は一覧の「再試行」から再送できます。",
                            summary.sentCount(), summary.failedCount()));
        } else {
            redirectAttributes.addFlashAttribute("infoMessage",
                    String.format("通知チェックを実行しました（新規送信: 0件 / 未提出確認: %d件）。既に同タイミング送信済み、または通知設定の対象外です。",
                            summary.checkedCount()));
        }

        return "redirect:/history";
    }

    /**
     * 再試行機能テスト用に送信失敗ログ（FAILED）を1件生成するデモ用エンドポイント（POST /history/simulate-fail）。
     */
    @PostMapping("/history/simulate-fail")
    public String simulateFail(
            HttpSession session,
            @CookieValue(value = "user_id", required = false) String cookieUserId,
            RedirectAttributes redirectAttributes) {

        String userId = resolveUserId(session, cookieUserId);
        if (userId == null) {
            return "redirect:/login";
        }

        NotificationLog failedLog = notificationScheduler.simulateFailedNotification(userId);
        redirectAttributes.addFlashAttribute("warningMessage",
                String.format("送信失敗ログ（ID: %d）を生成しました。一覧の「再試行」ボタンで再送機能をテストできます。",
                        failedLog.getLogId()));

        return "redirect:/history";
    }

    /**
     * ブラウザ側 JavaScript がポーリングして実際のブラウザ Push 通知（new Notification）を
     * ポップアップ表示するための直近通知リスト JSON API（GET /api/notifications/recent）。
     */
    @GetMapping("/api/notifications/recent")
    @ResponseBody
    public ResponseEntity<List<RecentNotificationDto>> getRecentNotifications(
            HttpSession session,
            @CookieValue(value = "user_id", required = false) String cookieUserId) {

        String userId = resolveUserId(session, cookieUserId);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(List.of());
        }

        List<NotificationLog> logs = notificationLogRepository.findByUserIdOrderBySentAtDesc(userId);
        Map<Long, Task> taskMap = loadTaskMapForUser(userId);

        List<RecentNotificationDto> result = new ArrayList<>();
        for (NotificationLog entry : logs) {
            if (!"SUCCESS".equalsIgnoreCase(entry.getStatus())) {
                continue;
            }
            Task task = resolveTask(entry.getTaskId(), taskMap);
            String title = task != null && task.getTitle() != null ? task.getTitle() : "manaba 課題通知";
            String courseName = task != null && task.getCourseName() != null ? task.getCourseName() : "授業科目";
            String deadlineStr = (task != null && task.getDeadline() != null)
                    ? task.getDeadline().format(DISPLAY_FORMATTER)
                    : "締切未設定";

            result.add(new RecentNotificationDto(
                    entry.getLogId(),
                    entry.getTaskId(),
                    title,
                    courseName,
                    deadlineStr,
                    entry.getMessage(),
                    entry.getSentAt(),
                    entry.getStatus(),
                    "/tasks"
            ));
            if (result.size() >= 10) {
                break;
            }
        }

        return ResponseEntity.ok(result);
    }

    private String resolveUserId(HttpSession session, String cookieUserId) {
        Object sessionUserId = session.getAttribute("user_id");
        if (sessionUserId != null && !sessionUserId.toString().isBlank()) {
            return sessionUserId.toString();
        }
        if (cookieUserId != null && !cookieUserId.isBlank()) {
            session.setAttribute("user_id", cookieUserId);
            return cookieUserId;
        }
        return null;
    }

    private Map<Long, Task> loadTaskMapForUser(String userId) {
        List<Task> tasks = taskRepository.findByUserIdOrderByDeadlineAsc(userId);
        Map<Long, Task> map = new HashMap<>();
        if (tasks != null) {
            for (Task t : tasks) {
                if (t.getTaskId() != null) {
                    map.put(t.getTaskId(), t);
                }
            }
        }
        return map;
    }

    private Task resolveTask(Long taskId, Map<Long, Task> taskMap) {
        if (taskId == null) {
            return null;
        }
        Task task = taskMap.get(taskId);
        if (task != null) {
            return task;
        }
        return taskRepository.findById(taskId).orElse(null);
    }

    private String extractTimingLabel(String message) {
        if (message == null || message.isBlank()) {
            return "通知";
        }
        Matcher matcher = TIMING_TAG_PATTERN.matcher(message.trim());
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "通知";
    }
}
