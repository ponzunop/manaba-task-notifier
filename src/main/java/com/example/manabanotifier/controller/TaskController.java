package com.example.manabanotifier.controller;

import com.example.manabanotifier.entity.Task;
import com.example.manabanotifier.entity.User;
import com.example.manabanotifier.repository.TaskRepository;
import com.example.manabanotifier.repository.UserRepository;
import com.example.manabanotifier.security.SecurityUtils;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 機能2：小テスト・課題一覧・提出/受験済み管理を担当するコントローラ（担当：蘆川）。
 * ログイン中の利用者に紐づく小テスト・課題を締切順に一覧表示し、状態切替および手動登録を行う。
 */
@Controller
@RequestMapping("/tasks")
public class TaskController {

    private static final DateTimeFormatter DISPLAY_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");
    private static final DateTimeFormatter INPUT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final TaskRepository taskRepository;
    private final UserRepository userRepository;

    public TaskController(TaskRepository taskRepository, UserRepository userRepository) {
        this.taskRepository = taskRepository;
        this.userRepository = userRepository;
    }

    /**
     * 小テスト・課題一覧画面を表示する。
     * 締め切り日時の昇順で取得し、公開時間・種別・URL・残り時間と統計カウントを画面へ渡す。
     */
    @GetMapping
    public String listTasks(
            @RequestParam(value = "filter", required = false, defaultValue = "all") String filter,
            @RequestParam(value = "course", required = false, defaultValue = "") String course,
            @RequestParam(value = "q", required = false, defaultValue = "") String keyword,
            @RequestParam(value = "synced", required = false) String synced,
            HttpSession session,
            Model model) {

        String userId = resolveUserId(session);
        if (userId == null) {
            return "redirect:/login";
        }

        User user = userRepository.findById(userId).orElse(null);
        String userDisplayName = resolveDisplayName(userId, user);
        String userEmail = user != null && user.getEmail() != null ? user.getEmail() : "";

        List<Task> allTasks = taskRepository.findByUserIdOrderByDeadlineAsc(userId);
        LocalDateTime now = LocalDateTime.now();

        long unsubmittedCount = 0;
        long submittedCount = 0;
        long urgentCount = 0;
        long overdueCount = 0;
        Set<String> courses = new LinkedHashSet<>();

        for (Task task : allTasks) {
            if (task.getCourseName() != null && !task.getCourseName().isBlank()) {
                courses.add(task.getCourseName());
            }
            if (task.isSubmitted()) {
                submittedCount++;
            } else {
                unsubmittedCount++;
                if (task.getDeadline() != null) {
                    if (task.getDeadline().isBefore(now)) {
                        overdueCount++;
                        urgentCount++;
                    } else if (!task.getDeadline().isAfter(now.plusHours(24))) {
                        urgentCount++;
                    }
                }
            }
        }

        String normalizedFilter = normalizeFilter(filter);
        String trimmedCourse = course != null ? course.trim() : "";
        String trimmedKeyword = keyword != null ? keyword.trim() : "";
        String lowerKeyword = trimmedKeyword.toLowerCase(Locale.ROOT);

        List<TaskView> filteredTasks = new ArrayList<>();
        for (Task task : allTasks) {
            if (!matchesFilter(task, normalizedFilter, now)) {
                continue;
            }
            if (!trimmedCourse.isEmpty() && !trimmedCourse.equals(task.getCourseName())) {
                continue;
            }
            if (!lowerKeyword.isEmpty()) {
                String title = task.getTitle() != null ? task.getTitle().toLowerCase(Locale.ROOT) : "";
                String cName = task.getCourseName() != null ? task.getCourseName().toLowerCase(Locale.ROOT) : "";
                String tType = task.getTaskType() != null ? task.getTaskType().toLowerCase(Locale.ROOT) : "";
                if (!title.contains(lowerKeyword) && !cName.contains(lowerKeyword) && !tType.contains(lowerKeyword)) {
                    continue;
                }
            }
            filteredTasks.add(TaskView.from(task, now));
        }

        if ("true".equals(synced) && !model.containsAttribute("successMessage")) {
            model.addAttribute("successMessage", "Gmailから最新の小テスト・課題情報を同期しました");
        }

        Integer lastSyncScannedCount = resolveIntegerAttr(session, "lastSyncScannedCount");
        Integer lastSyncSyncedCount = resolveIntegerAttr(session, "lastSyncSyncedCount");
        List<String> lastSyncSkippedSamples = resolveStringListAttr(session, "lastSyncSkippedSamples");
        boolean isGasBrowserAuth = user != null
                && "GAS_BROWSER".equals(user.getAuthType())
                && user.getGasUrl() != null
                && !user.getGasUrl().isBlank();

        model.addAttribute("userId", userId);
        model.addAttribute("userDisplayName", userDisplayName);
        model.addAttribute("userEmail", userEmail);
        model.addAttribute("isGasBrowserAuth", isGasBrowserAuth);
        model.addAttribute("lastSyncScannedCount", lastSyncScannedCount);
        model.addAttribute("lastSyncSyncedCount", lastSyncSyncedCount);
        model.addAttribute("lastSyncSkippedSamples", lastSyncSkippedSamples);
        model.addAttribute("tasks", filteredTasks);
        model.addAttribute("courses", courses);
        model.addAttribute("totalCount", allTasks.size());
        model.addAttribute("unsubmittedCount", unsubmittedCount);
        model.addAttribute("submittedCount", submittedCount);
        model.addAttribute("urgentCount", urgentCount);
        model.addAttribute("overdueCount", overdueCount);
        model.addAttribute("currentFilter", normalizedFilter);
        model.addAttribute("currentCourse", trimmedCourse);
        model.addAttribute("currentKeyword", trimmedKeyword);
        model.addAttribute("defaultOpenTime", now.withMinute(0).format(INPUT_FORMATTER));
        model.addAttribute("defaultDeadline", now.plusDays(3).withHour(23).withMinute(59).format(INPUT_FORMATTER));

        return "tasks";
    }

    /**
     * 対象の小テスト・課題を提出/受験済み（またはトグル指定時は反転）に更新する。
     * 更新後は以降の通知対象から除外される。
     */
    @PostMapping("/{taskId}/submit")
    public String submitTask(
            @PathVariable("taskId") Long taskId,
            @RequestParam(value = "toggle", required = false, defaultValue = "false") boolean toggle,
            @RequestParam(value = "filter", required = false, defaultValue = "all") String filter,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        String userId = resolveUserId(session);
        if (userId == null) {
            return "redirect:/login";
        }

        try {
            Optional<Task> optionalTask = taskRepository.findById(taskId);
            if (optionalTask.isEmpty() || !userId.equals(optionalTask.get().getUserId())) {
                redirectAttributes.addFlashAttribute("errorMessage", "課題の状態更新に失敗しました");
                return buildTasksRedirect(filter);
            }

            Task task = optionalTask.get();
            boolean nextSubmitted = toggle ? !task.isSubmitted() : true;
            task.setSubmitted(nextSubmitted);
            taskRepository.save(task);

            boolean isQuiz = "小テスト".equals(normalizeTaskType(task.getTaskType()));
            if (nextSubmitted) {
                redirectAttributes.addFlashAttribute(
                        "successMessage",
                        "「" + task.getTitle() + "」を" + (isQuiz ? "受験済み" : "提出済み") + "にしました（以降の通知対象から除外されます）");
            } else {
                redirectAttributes.addFlashAttribute(
                        "successMessage",
                        "「" + task.getTitle() + "」を" + (isQuiz ? "未受験" : "未提出") + "に戻しました");
            }
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "課題の状態更新に失敗しました");
        }

        return buildTasksRedirect(filter);
    }

    /**
     * 提出/受験済み項目を未提出/未受験状態に戻す。
     */
    @PostMapping("/{taskId}/unsubmit")
    public String unsubmitTask(
            @PathVariable("taskId") Long taskId,
            @RequestParam(value = "filter", required = false, defaultValue = "all") String filter,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        String userId = resolveUserId(session);
        if (userId == null) {
            return "redirect:/login";
        }

        try {
            Optional<Task> optionalTask = taskRepository.findById(taskId);
            if (optionalTask.isEmpty() || !userId.equals(optionalTask.get().getUserId())) {
                redirectAttributes.addFlashAttribute("errorMessage", "課題の状態更新に失敗しました");
                return buildTasksRedirect(filter);
            }

            Task task = optionalTask.get();
            task.setSubmitted(false);
            taskRepository.save(task);

            boolean isQuiz = "小テスト".equals(normalizeTaskType(task.getTaskType()));
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "「" + task.getTitle() + "」を" + (isQuiz ? "未受験" : "未提出") + "に戻しました（通知対象に復帰しました）");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "課題の状態更新に失敗しました");
        }

        return buildTasksRedirect(filter);
    }

    /**
     * 手動で小テスト・課題を登録する（公開日時・締切日時・種別・URL対応）。
     */
    @PostMapping("/create")
    public String createTask(
            @RequestParam(value = "taskType", required = false, defaultValue = "小テスト") String taskType,
            @RequestParam("courseName") String courseName,
            @RequestParam("title") String title,
            @RequestParam(value = "openTime", required = false) String openTimeStr,
            @RequestParam("deadline") String deadlineStr,
            @RequestParam(value = "taskUrl", required = false) String taskUrl,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        String userId = resolveUserId(session);
        if (userId == null) {
            return "redirect:/login";
        }

        String trimmedCourse = courseName != null ? courseName.trim() : "";
        String trimmedTitle = title != null ? title.trim() : "";
        String resolvedType = normalizeTaskType(taskType);

        if (trimmedCourse.isEmpty() || trimmedCourse.length() > 150
                || trimmedTitle.isEmpty() || trimmedTitle.length() > 200
                || deadlineStr == null || deadlineStr.isBlank()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "登録に失敗しました。科目名（150文字以内）・小テスト/課題名（200文字以内）・締切日時を正しく入力してください");
            return "redirect:/tasks";
        }

        LocalDateTime openTime = null;
        if (openTimeStr != null && !openTimeStr.isBlank()) {
            try {
                openTime = parseDateTime(openTimeStr.trim());
            } catch (DateTimeParseException e) {
                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        "登録に失敗しました。公開日時の形式が正しくありません");
                return "redirect:/tasks";
            }
        }

        LocalDateTime deadline;
        try {
            deadline = parseDateTime(deadlineStr.trim());
        } catch (DateTimeParseException e) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "登録に失敗しました。締切日時の形式が正しくありません");
            return "redirect:/tasks";
        }

        if (openTime != null && openTime.isAfter(deadline)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "公開時間は締切時間より前に設定してください");
            return "redirect:/tasks";
        }

        String sanitizedUrl = sanitizeUrl(taskUrl);

        try {
            Task task = new Task();
            task.setUserId(userId);
            task.setTaskType(resolvedType);
            task.setCourseName(trimmedCourse);
            task.setTitle(trimmedTitle);
            task.setOpenTime(openTime);
            task.setDeadline(deadline);
            task.setTaskUrl(sanitizedUrl);
            task.setSubmitted(false);
            taskRepository.save(task);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "「" + trimmedTitle + "」（" + resolvedType + "）を一覧に追加しました");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "小テスト・課題の登録に失敗しました");
        }

        return "redirect:/tasks";
    }

    private String resolveUserId(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object attr = session.getAttribute("user_id");
        if (attr != null) {
            String sessionUserId = attr.toString().trim();
            if (!sessionUserId.isEmpty()) {
                return sessionUserId;
            }
        }
        return null;
    }

    private String resolveDisplayName(String userId, User user) {
        if (user != null) {
            if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
                return user.getDisplayName();
            }
            if (user.getEmail() != null && !user.getEmail().isBlank()) {
                return user.getEmail();
            }
        }
        return userId;
    }

    private Integer resolveIntegerAttr(HttpSession session, String name) {
        Object attr = session.getAttribute(name);
        if (attr instanceof Number num) {
            return num.intValue();
        }
        if (attr instanceof String str && !str.isBlank()) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private List<String> resolveStringListAttr(HttpSession session, String name) {
        Object attr = session.getAttribute(name);
        if (!(attr instanceof List<?> rawList) || rawList.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : rawList) {
            if (item != null) {
                String text = item.toString();
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    private static String normalizeTaskType(String taskType) {
        if (taskType == null || taskType.isBlank()) {
            return "小テスト";
        }
        String trimmed = taskType.trim();
        return switch (trimmed) {
            case "小テスト", "アンケート", "レポート", "課題" -> trimmed;
            default -> "小テスト";
        };
    }

    private static String sanitizeUrl(String url) {
        return SecurityUtils.sanitizeHttpUrl(url, 500);
    }

    private String normalizeFilter(String filter) {
        if ("unsubmitted".equals(filter) || "submitted".equals(filter) || "urgent".equals(filter)) {
            return filter;
        }
        return "all";
    }

    private boolean matchesFilter(Task task, String filter, LocalDateTime now) {
        return switch (filter) {
            case "unsubmitted" -> !task.isSubmitted();
            case "submitted" -> task.isSubmitted();
            case "urgent" -> !task.isSubmitted()
                    && task.getDeadline() != null
                    && !task.getDeadline().isAfter(now.plusHours(24));
            default -> true;
        };
    }

    private String buildTasksRedirect(String filter) {
        String normalized = normalizeFilter(filter);
        if ("all".equals(normalized)) {
            return "redirect:/tasks";
        }
        return "redirect:/tasks?filter=" + normalized;
    }

    private LocalDateTime parseDateTime(String value) {
        try {
            return LocalDateTime.parse(value, INPUT_FORMATTER);
        } catch (DateTimeParseException ignored) {
            return LocalDateTime.parse(value);
        }
    }

    /**
     * 一覧表示用のビューモデル。種別・公開時間・締切時間・URL・残り時間・緊急度ラベルを保持する。
     */
    public static class TaskView {
        private final Long taskId;
        private final String taskType;
        private final String taskTypeBadgeClass;
        private final String title;
        private final String courseName;
        private final LocalDateTime openTime;
        private final String formattedOpenTime;
        private final LocalDateTime deadline;
        private final String formattedDeadline;
        private final String taskUrl;
        private final boolean hasUrl;
        private final boolean submitted;
        private final boolean overdue;
        private final boolean urgent;
        private final boolean upcoming;
        private final String remainingText;
        private final String urgencyBadgeClass;
        private final String urgencyLabel;
        private final String actionButtonLabel;
        private final String revertButtonLabel;

        private TaskView(
                Long taskId,
                String taskType,
                String taskTypeBadgeClass,
                String title,
                String courseName,
                LocalDateTime openTime,
                String formattedOpenTime,
                LocalDateTime deadline,
                String formattedDeadline,
                String taskUrl,
                boolean hasUrl,
                boolean submitted,
                boolean overdue,
                boolean urgent,
                boolean upcoming,
                String remainingText,
                String urgencyBadgeClass,
                String urgencyLabel,
                String actionButtonLabel,
                String revertButtonLabel) {
            this.taskId = taskId;
            this.taskType = taskType;
            this.taskTypeBadgeClass = taskTypeBadgeClass;
            this.title = title;
            this.courseName = courseName;
            this.openTime = openTime;
            this.formattedOpenTime = formattedOpenTime;
            this.deadline = deadline;
            this.formattedDeadline = formattedDeadline;
            this.taskUrl = taskUrl;
            this.hasUrl = hasUrl;
            this.submitted = submitted;
            this.overdue = overdue;
            this.urgent = urgent;
            this.upcoming = upcoming;
            this.remainingText = remainingText;
            this.urgencyBadgeClass = urgencyBadgeClass;
            this.urgencyLabel = urgencyLabel;
            this.actionButtonLabel = actionButtonLabel;
            this.revertButtonLabel = revertButtonLabel;
        }

        public static TaskView from(Task task, LocalDateTime now) {
            String taskType = normalizeTaskType(task.getTaskType());
            boolean isQuiz = "小テスト".equals(taskType);
            String typeBadgeClass = switch (taskType) {
                case "小テスト" -> "type-quiz";
                case "アンケート" -> "type-survey";
                case "レポート" -> "type-report";
                default -> "type-other";
            };

            LocalDateTime openTime = task.getOpenTime();
            String formattedOpenTime = openTime != null ? openTime.format(DISPLAY_FORMATTER) : "-";

            LocalDateTime deadline = task.getDeadline();
            String formattedDeadline = deadline != null ? deadline.format(DISPLAY_FORMATTER) : "-";

            String sanitizedUrl = sanitizeUrl(task.getTaskUrl());
            boolean hasUrl = sanitizedUrl != null;

            boolean submitted = task.isSubmitted();
            boolean overdue = false;
            boolean urgent = false;
            boolean upcoming = false;
            String remainingText;
            String badgeClass;
            String label;

            if (submitted) {
                remainingText = isQuiz ? "受験済（通知停止）" : "提出済（通知停止）";
                badgeClass = "badge-submitted";
                label = isQuiz ? "受験済" : "提出済";
            } else if (deadline == null) {
                remainingText = "締切未設定";
                badgeClass = "badge-normal";
                label = isQuiz ? "未受験" : "未提出";
            } else if (deadline.isBefore(now)) {
                overdue = true;
                urgent = true;
                remainingText = "期限超過 (" + formatDurationShort(Duration.between(deadline, now)) + "前)";
                badgeClass = "badge-overdue";
                label = "期限超過";
            } else if (openTime != null && openTime.isAfter(now)) {
                upcoming = true;
                Duration untilOpen = Duration.between(now, openTime);
                Duration untilDeadline = Duration.between(now, deadline);
                if (untilDeadline.toHours() < 24) {
                    urgent = true;
                }
                remainingText = "公開まであと " + formatDurationShort(untilOpen);
                badgeClass = "badge-upcoming";
                label = "公開前";
            } else {
                Duration diff = Duration.between(now, deadline);
                long totalHours = diff.toHours();
                if (totalHours < 24) {
                    urgent = true;
                    remainingText = "あと " + formatDurationShort(diff);
                    badgeClass = "badge-urgent";
                    label = "締切間近";
                } else if (totalHours < 72) {
                    remainingText = "あと " + formatDurationShort(diff);
                    badgeClass = "badge-soon";
                    label = "3日以内";
                } else {
                    remainingText = "あと " + formatDurationShort(diff);
                    badgeClass = "badge-normal";
                    label = isQuiz ? "受付中" : "未提出";
                }
            }

            String actionLabel = isQuiz ? "受験済みにする" : "提出済みにする";
            String revertLabel = isQuiz ? "未受験に戻す" : "未提出に戻す";

            return new TaskView(
                    task.getTaskId(),
                    taskType,
                    typeBadgeClass,
                    task.getTitle(),
                    task.getCourseName(),
                    openTime,
                    formattedOpenTime,
                    deadline,
                    formattedDeadline,
                    sanitizedUrl,
                    hasUrl,
                    submitted,
                    overdue,
                    urgent,
                    upcoming,
                    remainingText,
                    badgeClass,
                    label,
                    actionLabel,
                    revertLabel);
        }

        private static String formatDurationShort(Duration diff) {
            long days = diff.toDays();
            long hours = diff.toHours() % 24;
            long mins = diff.toMinutes() % 60;
            if (days > 0) {
                return days + "日 " + hours + "時間";
            }
            if (hours > 0) {
                return hours + "時間 " + mins + "分";
            }
            return Math.max(1, mins) + "分";
        }

        public Long getTaskId() {
            return taskId;
        }

        public String getTaskType() {
            return taskType;
        }

        public String getTaskTypeBadgeClass() {
            return taskTypeBadgeClass;
        }

        public String getTitle() {
            return title;
        }

        public String getCourseName() {
            return courseName;
        }

        public LocalDateTime getOpenTime() {
            return openTime;
        }

        public String getFormattedOpenTime() {
            return formattedOpenTime;
        }

        public LocalDateTime getDeadline() {
            return deadline;
        }

        public String getFormattedDeadline() {
            return formattedDeadline;
        }

        public String getTaskUrl() {
            return taskUrl;
        }

        public boolean isHasUrl() {
            return hasUrl;
        }

        public boolean isSubmitted() {
            return submitted;
        }

        public boolean isOverdue() {
            return overdue;
        }

        public boolean isUrgent() {
            return urgent;
        }

        public boolean isUpcoming() {
            return upcoming;
        }

        public String getRemainingText() {
            return remainingText;
        }

        public String getUrgencyBadgeClass() {
            return urgencyBadgeClass;
        }

        public String getUrgencyLabel() {
            return urgencyLabel;
        }

        public String getActionButtonLabel() {
            return actionButtonLabel;
        }

        public String getRevertButtonLabel() {
            return revertButtonLabel;
        }
    }
}
