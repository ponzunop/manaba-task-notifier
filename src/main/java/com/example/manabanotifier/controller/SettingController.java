package com.example.manabanotifier.controller;

import com.example.manabanotifier.entity.NotificationSetting;
import com.example.manabanotifier.entity.Task;
import com.example.manabanotifier.entity.User;
import com.example.manabanotifier.repository.NotificationSettingRepository;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 機能3：通知設定担当（宮川）のコントローラ。
 * 通知タイミング（複数指定：締切N時間前）および科目ごとの通知 ON/OFF 設定を管理する。
 */
@Controller
@RequestMapping("/settings")
public class SettingController {

    private static final String DEFAULT_TIMINGS = "24,5,1";
    private static final int MIN_TIMING_HOURS = 1;
    private static final int MAX_TIMING_HOURS = 168;
    private static final String VALIDATION_ERROR_MSG = "通知時刻を正しく設定してください";

    /**
     * 画面上のチェックボックス用プリセット（時間 -> 表示ラベル）。
     */
    private static final Map<Integer, String> PRESET_TIMING_OPTIONS;

    static {
        Map<Integer, String> presets = new LinkedHashMap<>();
        presets.put(48, "48時間前（2日前）");
        presets.put(24, "24時間前（1日前）");
        presets.put(12, "12時間前");
        presets.put(5, "5時間前");
        presets.put(3, "3時間前");
        presets.put(2, "2時間前");
        presets.put(1, "1時間前");
        PRESET_TIMING_OPTIONS = Collections.unmodifiableMap(presets);
    }

    private final NotificationSettingRepository notificationSettingRepository;
    private final TaskRepository taskRepository;
    private final UserRepository userRepository;

    public SettingController(
            NotificationSettingRepository notificationSettingRepository,
            TaskRepository taskRepository,
            UserRepository userRepository) {
        this.notificationSettingRepository = notificationSettingRepository;
        this.taskRepository = taskRepository;
        this.userRepository = userRepository;
    }

    /**
     * 通知設定画面を表示する（GET /settings）。
     */
    @GetMapping
    public String showSettings(
            HttpSession session,
            @CookieValue(value = "user_id", required = false) String cookieUserId,
            Model model) {
        String userId = resolveUserId(session, cookieUserId);
        if (userId == null) {
            return "redirect:/login";
        }

        List<NotificationSetting> settings = ensureCourseSettings(userId);
        String currentTimingsRaw = extractCurrentTimings(settings);
        Set<Integer> selectedTimings = parseTimingsToSet(currentTimingsRaw);
        String customTimingsText = extractNonPresetTimingsText(selectedTimings);

        populateCommonModel(model, userId, settings, selectedTimings, customTimingsText);
        return "settings";
    }

    /**
     * 通知タイミングおよび科目別 ON/OFF 設定を検証・保存する（POST /settings）。
     */
    @PostMapping
    public String saveSettings(
            HttpSession session,
            @CookieValue(value = "user_id", required = false) String cookieUserId,
            @RequestParam(value = "timings", required = false) List<String> timings,
            @RequestParam(value = "customTiming", required = false) String customTiming,
            @RequestParam(value = "enabledSettingIds", required = false) List<Long> enabledSettingIds,
            @RequestParam(value = "enabledCourses", required = false) List<String> enabledCourses,
            RedirectAttributes redirectAttributes,
            Model model) {
        String userId = resolveUserId(session, cookieUserId);
        if (userId == null) {
            return "redirect:/login";
        }

        List<NotificationSetting> settings = ensureCourseSettings(userId);
        Set<Long> submittedIds = enabledSettingIds != null ? new HashSet<>(enabledSettingIds) : Set.of();
        Set<String> submittedCourses = enabledCourses != null ? new HashSet<>(enabledCourses) : Set.of();

        TimingParseResult parseResult = parseSubmittedTimings(timings, customTiming);
        if (!parseResult.valid()) {
            applySubmittedEnabledPreview(settings, submittedIds, submittedCourses);
            populateCommonModel(
                    model,
                    userId,
                    settings,
                    parseResult.validIntegers(),
                    customTiming != null ? customTiming.trim() : "");
            model.addAttribute("errorMessage", VALIDATION_ERROR_MSG);
            return "settings";
        }

        String normalizedTimings = parseResult.normalizedString();

        try {
            if (settings.isEmpty()) {
                NotificationSetting fallbackSetting = new NotificationSetting();
                fallbackSetting.setUserId(userId);
                fallbackSetting.setCourseName("全体共通");
                fallbackSetting.setEnabled(true);
                fallbackSetting.setNotifyTimings(normalizedTimings);
                notificationSettingRepository.save(fallbackSetting);
            } else {
                for (NotificationSetting setting : settings) {
                    boolean enabled = submittedIds.contains(setting.getSettingId())
                            || submittedCourses.contains(setting.getCourseName());
                    setting.setEnabled(enabled);
                    setting.setNotifyTimings(normalizedTimings);
                }
                notificationSettingRepository.saveAll(settings);
            }
        } catch (Exception e) {
            applySubmittedEnabledPreview(settings, submittedIds, submittedCourses);
            populateCommonModel(
                    model,
                    userId,
                    settings,
                    parseResult.validIntegers(),
                    customTiming != null ? customTiming.trim() : "");
            model.addAttribute("errorMessage", "設定の保存に失敗しました。入力内容を確認して再度お試しください。");
            return "settings";
        }

        redirectAttributes.addFlashAttribute("successMessage", "通知設定を保存しました");
        return "redirect:/settings";
    }

    /**
     * 新しい科目の通知設定を手動で追加する（POST /settings/courses）。
     */
    @PostMapping("/courses")
    public String addCourseSetting(
            HttpSession session,
            @CookieValue(value = "user_id", required = false) String cookieUserId,
            @RequestParam("courseName") String courseName,
            RedirectAttributes redirectAttributes) {
        String userId = resolveUserId(session, cookieUserId);
        if (userId == null) {
            return "redirect:/login";
        }

        String trimmed = courseName != null ? courseName.trim() : "";
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            redirectAttributes.addFlashAttribute("errorMessage", "科目名を1〜100文字で入力してください");
            return "redirect:/settings";
        }

        Optional<NotificationSetting> existing =
                notificationSettingRepository.findByUserIdAndCourseName(userId, trimmed);
        if (existing.isPresent()) {
            redirectAttributes.addFlashAttribute("errorMessage", "科目「" + trimmed + "」は既に登録されています");
            return "redirect:/settings";
        }

        List<NotificationSetting> currentSettings = notificationSettingRepository.findByUserId(userId);
        String timingsToInherit = extractCurrentTimings(currentSettings);

        NotificationSetting newSetting = new NotificationSetting();
        newSetting.setUserId(userId);
        newSetting.setCourseName(trimmed);
        newSetting.setEnabled(true);
        newSetting.setNotifyTimings(timingsToInherit);
        notificationSettingRepository.save(newSetting);

        redirectAttributes.addFlashAttribute("successMessage", "科目「" + trimmed + "」を通知設定に追加しました");
        return "redirect:/settings";
    }

    /**
     * 科目別通知設定を削除する（POST /settings/courses/{settingId}/delete）。
     */
    @PostMapping("/courses/{settingId}/delete")
    public String deleteCourseSetting(
            HttpSession session,
            @CookieValue(value = "user_id", required = false) String cookieUserId,
            @PathVariable("settingId") Long settingId,
            RedirectAttributes redirectAttributes) {
        String userId = resolveUserId(session, cookieUserId);
        if (userId == null) {
            return "redirect:/login";
        }

        Optional<NotificationSetting> targetOpt = notificationSettingRepository.findById(settingId);
        if (targetOpt.isPresent() && userId.equals(targetOpt.get().getUserId())) {
            String removedName = targetOpt.get().getCourseName();
            notificationSettingRepository.delete(targetOpt.get());
            redirectAttributes.addFlashAttribute("successMessage", "科目「" + removedName + "」の設定を削除しました");
        } else {
            redirectAttributes.addFlashAttribute("errorMessage", "指定された科目設定が見つかりませんでした");
        }
        return "redirect:/settings";
    }

    /**
     * セッションまたはCookieからログイン中の user_id を解決する。
     */
    private String resolveUserId(HttpSession session, String cookieUserId) {
        Object sessionUserId = session.getAttribute("user_id");
        if (sessionUserId instanceof String s && !s.isBlank()) {
            return s;
        }
        if (cookieUserId != null && !cookieUserId.isBlank()) {
            session.setAttribute("user_id", cookieUserId);
            return cookieUserId;
        }
        return null;
    }

    /**
     * ユーザーのタスクに含まれる履修科目を確認し、未登録科目の NotificationSetting を初期値で補完する。
     */
    private List<NotificationSetting> ensureCourseSettings(String userId) {
        List<NotificationSetting> existing = new ArrayList<>(notificationSettingRepository.findByUserId(userId));
        String inheritTimings = extractCurrentTimings(existing);

        Set<String> registeredCourses = new HashSet<>();
        for (NotificationSetting setting : existing) {
            if (setting.getCourseName() != null) {
                registeredCourses.add(setting.getCourseName().trim());
            }
        }

        List<Task> tasks = taskRepository.findByUserIdOrderByDeadlineAsc(userId);
        Set<String> taskCourses = new LinkedHashSet<>();
        for (Task task : tasks) {
            if (task.getCourseName() != null && !task.getCourseName().trim().isEmpty()) {
                taskCourses.add(task.getCourseName().trim());
            }
        }

        List<NotificationSetting> newSettings = new ArrayList<>();
        for (String course : taskCourses) {
            if (!registeredCourses.contains(course)) {
                NotificationSetting created = new NotificationSetting();
                created.setUserId(userId);
                created.setCourseName(course);
                created.setEnabled(true);
                created.setNotifyTimings(inheritTimings);
                newSettings.add(created);
                registeredCourses.add(course);
            }
        }

        if (!newSettings.isEmpty()) {
            List<NotificationSetting> saved = notificationSettingRepository.saveAll(newSettings);
            existing.addAll(saved);
        }

        existing.sort(Comparator.comparing(
                NotificationSetting::getSettingId,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return existing;
    }

    /**
     * 既存設定から現在の通知時刻文字列を取得する（未設定時はデフォルト "24,5,1"）。
     */
    private String extractCurrentTimings(List<NotificationSetting> settings) {
        for (NotificationSetting setting : settings) {
            if (setting.getNotifyTimings() != null && !setting.getNotifyTimings().isBlank()) {
                return setting.getNotifyTimings().trim();
            }
        }
        return DEFAULT_TIMINGS;
    }

    /**
     * カンマ区切り通知時刻文字列を降順の整数セットへ変換する。
     */
    private Set<Integer> parseTimingsToSet(String rawTimings) {
        Set<Integer> result = new TreeSet<>(Comparator.reverseOrder());
        if (rawTimings == null || rawTimings.isBlank()) {
            return result;
        }
        for (String token : rawTimings.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                try {
                    int hours = Integer.parseInt(trimmed);
                    if (hours >= MIN_TIMING_HOURS && hours <= MAX_TIMING_HOURS) {
                        result.add(hours);
                    }
                } catch (NumberFormatException ignored) {
                    // DB上の不正トークンはスキップ
                }
            }
        }
        return result;
    }

    /**
     * プリセットに含まれないカスタム通知時刻をカンマ区切りテキストとして抽出する。
     */
    private String extractNonPresetTimingsText(Set<Integer> selectedTimings) {
        return selectedTimings.stream()
                .filter(h -> !PRESET_TIMING_OPTIONS.containsKey(h))
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
    }

    /**
     * フォーム送信された通知時刻（チェックボックス + カスタム入力）を検証・正規化する。
     */
    private TimingParseResult parseSubmittedTimings(List<String> timings, String customTiming) {
        Set<Integer> collected = new TreeSet<>(Comparator.reverseOrder());
        boolean hasInvalid = false;

        if (timings != null) {
            for (String raw : timings) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                Integer parsed = parseValidHour(raw.trim());
                if (parsed == null) {
                    hasInvalid = true;
                } else {
                    collected.add(parsed);
                }
            }
        }

        if (customTiming != null && !customTiming.isBlank()) {
            String[] customTokens = customTiming.trim().split("[,、\\s]+");
            for (String token : customTokens) {
                if (token.isEmpty()) {
                    continue;
                }
                Integer parsed = parseValidHour(token);
                if (parsed == null) {
                    hasInvalid = true;
                } else {
                    collected.add(parsed);
                }
            }
        }

        if (hasInvalid || collected.isEmpty()) {
            return new TimingParseResult(false, collected, "");
        }

        String normalized = collected.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        return new TimingParseResult(true, collected, normalized);
    }

    private Integer parseValidHour(String token) {
        try {
            int value = Integer.parseInt(token);
            if (value < MIN_TIMING_HOURS || value > MAX_TIMING_HOURS) {
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * バリデーションエラー時にユーザーが画面で変更した ON/OFF 状態を一時保持する（DB には保存しない）。
     */
    private void applySubmittedEnabledPreview(
            List<NotificationSetting> settings,
            Set<Long> submittedIds,
            Set<String> submittedCourses) {
        for (NotificationSetting setting : settings) {
            boolean enabled = submittedIds.contains(setting.getSettingId())
                    || submittedCourses.contains(setting.getCourseName());
            setting.setEnabled(enabled);
        }
    }

    /**
     * 画面描画用の共通モデル属性を設定する。
     */
    private void populateCommonModel(
            Model model,
            String userId,
            List<NotificationSetting> settings,
            Set<Integer> selectedTimings,
            String customTimingsText) {
        Optional<User> userOpt = userRepository.findById(userId);
        String displayName = userOpt
                .map(u -> u.getDisplayName() != null && !u.getDisplayName().isBlank() ? u.getDisplayName() : u.getEmail())
                .orElse(userId);
        String userEmail = userOpt.map(User::getEmail).orElse("");
        boolean pushSubscribed = userOpt
                .map(u -> u.getPushEndpoint() != null && !u.getPushEndpoint().isBlank())
                .orElse(false);

        long enabledCount = settings.stream().filter(NotificationSetting::isEnabled).count();
        String normalizedSummary = selectedTimings.stream()
                .map(h -> h + "時間前")
                .collect(Collectors.joining(" / "));
        String rawTimings = selectedTimings.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));

        model.addAttribute("userId", userId);
        model.addAttribute("displayName", displayName);
        model.addAttribute("userEmail", userEmail);
        model.addAttribute("pushSubscribed", pushSubscribed);
        model.addAttribute("settings", settings);
        model.addAttribute("enabledCourseCount", enabledCount);
        model.addAttribute("totalCourseCount", settings.size());
        model.addAttribute("presetTimings", PRESET_TIMING_OPTIONS);
        model.addAttribute("selectedTimings", selectedTimings);
        model.addAttribute("customTimingsText", customTimingsText);
        model.addAttribute("currentTimingsSummary", normalizedSummary.isEmpty() ? "未設定" : normalizedSummary);
        model.addAttribute("currentTimingsRaw", rawTimings);
    }

    private record TimingParseResult(boolean valid, Set<Integer> validIntegers, String normalizedString) {
    }
}
