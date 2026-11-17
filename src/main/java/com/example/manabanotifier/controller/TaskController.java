package com.example.manabanotifier.controller;

import com.example.manabanotifier.entity.Task;
import com.example.manabanotifier.entity.User;
import com.example.manabanotifier.repository.TaskRepository;
import com.example.manabanotifier.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
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
 * 機能2：課題一覧・提出済み管理を担当するコントローラ（担当：蘆川）。
 * ログイン中の利用者に紐づく課題を締切順に一覧表示し、提出状態の切替および課題登録を行う。
 */
@Controller
@RequestMapping("/tasks")
public class TaskController {

    private static final DateTimeFormatter DISPLAY_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy/MM/dd (E) HH:mm", Locale.JAPANESE);
    private static final DateTimeFormatter INPUT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final TaskRepository taskRepository;
    private final UserRepository userRepository;

    public TaskController(TaskRepository taskRepository, UserRepository userRepository) {
        this.taskRepository = taskRepository;
        this.userRepository = userRepository;
    }

    /**
     * 課題一覧画面を表示する。
     * 締め切り日時の昇順で課題を取得し、フィルタ条件と統計カウントを画面へ渡す。
     */
    @GetMapping
    public String listTasks(
            @RequestParam(value = "filter", required = false, defaultValue = "all") String filter,
            @RequestParam(value = "course", required = false, defaultValue = "") String course,
            @RequestParam(value = "q", required = false, defaultValue = "") String keyword,
            @RequestParam(value = "synced", required = false) String synced,
            HttpSession session,
            @CookieValue(value = "user_id", required = false) String cookieUserId,
            Model model) {

        String userId = resolveUserId(session, cookieUserId);
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
                if (!title.contains(lowerKeyword) && !cName.contains(lowerKeyword)) {
                    continue;
                }
            }
            filteredTasks.add(TaskView.from(task, now));
        }

        if ("true".equals(synced) && !model.containsAttribute("successMessage")) {
            model.addAttribute("successMessage", "Gmailから最新の課題情報を同期しました");
        }

        model.addAttribute("userId", userId);
        model.addAttribute("userDisplayName", userDisplayName);
        model.addAttribute("userEmail", userEmail);
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
        model.addAttribute("defaultDeadline", now.plusDays(2).withHour(23).withMinute(59).format(INPUT_FORMATTER));

        return "tasks";
    }

    /**
     * 対象課題を提出済み（またはトグル指定時は反転）に更新する。
     * 提出済みに更新された課題は以降の通知対象から除外される。
     */
    @PostMapping("/{taskId}/submit")
    public String submitTask(
            @PathVariable("taskId") Long taskId,
            @RequestParam(value = "toggle", required = false, defaultValue = "false") boolean toggle,
            @RequestParam(value = "filter", required = false, defaultValue = "all") String filter,
            HttpSession session,
            @CookieValue(value = "user_id", required = false) String cookieUserId,
            RedirectAttributes redirectAttributes) {

        String userId = resolveUserId(session, cookieUserId);
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

            if (nextSubmitted) {
                redirectAttributes.addFlashAttribute(
                        "successMessage",
                        "「" + task.getTitle() + "」を提出済みにしました（以降の通知対象から除外されます）");
            } else {
                redirectAttributes.addFlashAttribute(
                        "successMessage",
                        "「" + task.getTitle() + "」を未提出に戻しました");
            }
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "課題の状態更新に失敗しました");
        }

        return buildTasksRedirect(filter);
    }

    /**
     * 提出済み課題を未提出状態に戻す。
     */
    @PostMapping("/{taskId}/unsubmit")
    public String unsubmitTask(
            @PathVariable("taskId") Long taskId,
            @RequestParam(value = "filter", required = false, defaultValue = "all") String filter,
            HttpSession session,
            @CookieValue(value = "user_id", required = false) String cookieUserId,
            RedirectAttributes redirectAttributes) {

        String userId = resolveUserId(session, cookieUserId);
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

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "「" + task.getTitle() + "」を未提出に戻しました（通知対象に復帰しました）");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "課題の状態更新に失敗しました");
        }

        return buildTasksRedirect(filter);
    }

    /**
     * 手動で新しい課題を登録する（テスト・デモ・手動補完用）。
     */
    @PostMapping("/create")
    public String createTask(
            @RequestParam("courseName") String courseName,
            @RequestParam("title") String title,
            @RequestParam("deadline") String deadlineStr,
            HttpSession session,
            @CookieValue(value = "user_id", required = false) String cookieUserId,
            RedirectAttributes redirectAttributes) {

        String userId = resolveUserId(session, cookieUserId);
        if (userId == null) {
            return "redirect:/login";
        }

        String trimmedCourse = courseName != null ? courseName.trim() : "";
        String trimmedTitle = title != null ? title.trim() : "";

        if (trimmedCourse.isEmpty() || trimmedTitle.isEmpty() || deadlineStr == null || deadlineStr.isBlank()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "課題の登録に失敗しました。科目名・課題名・締切日時を入力してください");
            return "redirect:/tasks";
        }

        LocalDateTime deadline;
        try {
            deadline = parseDeadline(deadlineStr.trim());
        } catch (DateTimeParseException e) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "課題の登録に失敗しました。締切日時の形式が正しくありません");
            return "redirect:/tasks";
        }

        try {
            Task task = new Task();
            task.setUserId(userId);
            task.setCourseName(trimmedCourse);
            task.setTitle(trimmedTitle);
            task.setDeadline(deadline);
            task.setSubmitted(false);
            taskRepository.save(task);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "「" + trimmedTitle + "」を課題一覧に追加しました");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "課題の登録に失敗しました");
        }

        return "redirect:/tasks";
    }

    private String resolveUserId(HttpSession session, String cookieUserId) {
        Object attr = session.getAttribute("user_id");
        if (attr != null) {
            String sessionUserId = attr.toString().trim();
            if (!sessionUserId.isEmpty()) {
                return sessionUserId;
            }
        }
        if (cookieUserId != null && !cookieUserId.trim().isEmpty()) {
            String trimmed = cookieUserId.trim();
            session.setAttribute("user_id", trimmed);
            return trimmed;
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

    private LocalDateTime parseDeadline(String value) {
        try {
            return LocalDateTime.parse(value, INPUT_FORMATTER);
        } catch (DateTimeParseException ignored) {
            return LocalDateTime.parse(value);
        }
    }

    /**
     * 課題一覧表示用のビューモデル。残り時間・緊急度ラベルを保持する。
     */
    public static class TaskView {
        private final Long taskId;
        private final String title;
        private final String courseName;
        private final LocalDateTime deadline;
        private final String formattedDeadline;
        private final boolean submitted;
        private final boolean overdue;
        private final boolean urgent;
        private final String remainingText;
        private final String urgencyBadgeClass;
        private final String urgencyLabel;

        private TaskView(
                Long taskId,
                String title,
                String courseName,
                LocalDateTime deadline,
                String formattedDeadline,
                boolean submitted,
                boolean overdue,
                boolean urgent,
                String remainingText,
                String urgencyBadgeClass,
                String urgencyLabel) {
            this.taskId = taskId;
            this.title = title;
            this.courseName = courseName;
            this.deadline = deadline;
            this.formattedDeadline = formattedDeadline;
            this.submitted = submitted;
            this.overdue = overdue;
            this.urgent = urgent;
            this.remainingText = remainingText;
            this.urgencyBadgeClass = urgencyBadgeClass;
            this.urgencyLabel = urgencyLabel;
        }

        public static TaskView from(Task task, LocalDateTime now) {
            LocalDateTime deadline = task.getDeadline();
            String formattedDeadline = deadline != null ? deadline.format(DISPLAY_FORMATTER) : "-";
            boolean submitted = task.isSubmitted();
            boolean overdue = false;
            boolean urgent = false;
            String remainingText;
            String badgeClass;
            String label;

            if (submitted) {
                remainingText = "提出済み（通知停止）";
                badgeClass = "badge-submitted";
                label = "提出済み";
            } else if (deadline == null) {
                remainingText = "締切未設定";
                badgeClass = "badge-normal";
                label = "未提出";
            } else if (deadline.isBefore(now)) {
                overdue = true;
                urgent = true;
                Duration diff = Duration.between(deadline, now);
                long days = diff.toDays();
                long hours = diff.toHours() % 24;
                long mins = diff.toMinutes() % 60;
                if (days > 0) {
                    remainingText = "期限超過 (" + days + "日" + hours + "時間前)";
                } else if (hours > 0) {
                    remainingText = "期限超過 (" + hours + "時間" + mins + "分前)";
                } else {
                    remainingText = "期限超過 (" + Math.max(1, mins) + "分前)";
                }
                badgeClass = "badge-overdue";
                label = "期限超過";
            } else {
                Duration diff = Duration.between(now, deadline);
                long totalHours = diff.toHours();
                long days = diff.toDays();
                long hours = totalHours % 24;
                long mins = diff.toMinutes() % 60;
                if (totalHours < 24) {
                    urgent = true;
                    remainingText = hours > 0
                            ? "あと " + hours + "時間 " + mins + "分"
                            : "あと " + Math.max(1, mins) + "分";
                    badgeClass = "badge-urgent";
                    label = "締切間近";
                } else if (totalHours < 72) {
                    remainingText = "あと " + days + "日 " + hours + "時間";
                    badgeClass = "badge-soon";
                    label = "3日以内";
                } else {
                    remainingText = "あと " + days + "日 " + hours + "時間";
                    badgeClass = "badge-normal";
                    label = "未提出";
                }
            }

            return new TaskView(
                    task.getTaskId(),
                    task.getTitle(),
                    task.getCourseName(),
                    deadline,
                    formattedDeadline,
                    submitted,
                    overdue,
                    urgent,
                    remainingText,
                    badgeClass,
                    label);
        }

        public Long getTaskId() {
            return taskId;
        }

        public String getTitle() {
            return title;
        }

        public String getCourseName() {
            return courseName;
        }

        public LocalDateTime getDeadline() {
            return deadline;
        }

        public String getFormattedDeadline() {
            return formattedDeadline;
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

        public String getRemainingText() {
            return remainingText;
        }

        public String getUrgencyBadgeClass() {
            return urgencyBadgeClass;
        }

        public String getUrgencyLabel() {
            return urgencyLabel;
        }
    }
}
