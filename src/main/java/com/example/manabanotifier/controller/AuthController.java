package com.example.manabanotifier.controller;

import com.example.manabanotifier.entity.User;
import com.example.manabanotifier.repository.UserRepository;
import com.example.manabanotifier.service.TaskFetchService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 連携・ログイン・Web Push購読・課題同期コントローラ（担当：大友）。
 */
@Controller
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private static final String SESSION_USER_ID = "user_id";
    private static final String SESSION_USER_EMAIL = "user_email";
    private static final String COOKIE_USER_ID = "user_id";
    private static final String DEFAULT_GOOGLE_EMAIL = "student12@example.ac.jp";
    private static final String DEFAULT_GOOGLE_NAME = "情報太郎（チーム12）";

    private final UserRepository userRepository;
    private final TaskFetchService taskFetchService;
    private final ObjectMapper objectMapper;

    public AuthController(
            UserRepository userRepository,
            TaskFetchService taskFetchService,
            ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.taskFetchService = taskFetchService;
        this.objectMapper = objectMapper;
    }

    @GetMapping({"/", "/login"})
    public String loginPage(
            @RequestParam(required = false) String error,
            @RequestParam(required = false) String logout,
            HttpSession session,
            @CookieValue(value = COOKIE_USER_ID, required = false) String cookieUserId,
            HttpServletResponse response,
            Model model) {

        String authenticatedUserId = resolveAuthenticatedUserId(session, cookieUserId);
        if (authenticatedUserId != null) {
            Optional<User> userOpt = userRepository.findById(authenticatedUserId);
            if (userOpt.isPresent()) {
                User user = userOpt.get();
                session.setAttribute(SESSION_USER_ID, user.getUserId());
                session.setAttribute(SESSION_USER_EMAIL, user.getEmail());
                return "redirect:/tasks";
            }
            // DBに存在しない古いCookieの場合はクリアしてログイン画面を表示
            clearUserCookie(response);
            session.removeAttribute(SESSION_USER_ID);
            session.removeAttribute(SESSION_USER_EMAIL);
        }

        if ("oauth_failed".equals(error)) {
            model.addAttribute("errorMessage", "Google 連携に失敗しました。再度ログインしてください");
        } else if ("invalid_email".equals(error)) {
            model.addAttribute("errorMessage", "有効なメールアドレスを入力してください。");
        } else if (error != null && !error.isBlank()) {
            model.addAttribute("errorMessage", "ログイン処理でエラーが発生しました。再度お試しください。");
        }

        if ("true".equals(logout)) {
            model.addAttribute("logoutMessage", "ログアウトしました。");
        }

        return "login";
    }

    /**
     * Google OAuth 2.0 認証開始エンドポイント。
     * デモ環境でも即座に動作するよう OAuth コールバックへ認可コードを付与して遷移する。
     */
    @GetMapping("/auth/google")
    public String startGoogleOAuth(
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String name,
            @RequestParam(required = false, defaultValue = "false") boolean simulateError) {

        if (simulateError) {
            return "redirect:/auth/callback?error=access_denied";
        }

        String resolvedEmail = (email != null && !email.isBlank()) ? email.trim() : DEFAULT_GOOGLE_EMAIL;
        String resolvedName = (name != null && !name.isBlank()) ? name.trim() : DEFAULT_GOOGLE_NAME;
        String authCode = "demo-oauth-code-" + UUID.randomUUID().toString().substring(0, 8);

        String redirectUrl = UriComponentsBuilder.fromPath("/auth/callback")
                .queryParam("code", authCode)
                .queryParam("email", resolvedEmail)
                .queryParam("name", resolvedName)
                .encode(StandardCharsets.UTF_8)
                .toUriString();

        return "redirect:" + redirectUrl;
    }

    /**
     * Google OAuth 2.0 コールバックエンドポイント。
     */
    @GetMapping("/auth/callback")
    public String handleGoogleCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String name,
            HttpSession session,
            HttpServletResponse response) {

        if ((error != null && !error.isBlank()) || code == null || code.isBlank()) {
            log.warn("Google OAuth コールバック失敗: error={}, codePresent={}", error, code != null);
            return "redirect:/login?error=oauth_failed";
        }

        String resolvedEmail = (email != null && !email.isBlank()) ? email.trim() : DEFAULT_GOOGLE_EMAIL;
        String resolvedName = (name != null && !name.isBlank()) ? name.trim() : DEFAULT_GOOGLE_NAME;

        User user = upsertUser(
                resolvedEmail,
                resolvedName,
                "ya29.oauth-access-" + code,
                "1//oauth-refresh-" + UUID.nameUUIDFromBytes(resolvedEmail.getBytes(StandardCharsets.UTF_8))
        );

        establishLoginSession(user, session, response);
        taskFetchService.fetchTasksFromGmail(user.getUserId());

        return "redirect:/tasks";
    }

    /**
     * メールアドレス等によるフォームログイン処理（デモ・テスト用含む）。
     */
    @PostMapping("/auth/login")
    public String loginWithEmail(
            @RequestParam String email,
            @RequestParam(required = false) String studentId,
            @RequestParam(required = false) String password,
            @RequestParam(required = false) String displayName,
            HttpSession session,
            HttpServletResponse response) {

        if (email == null || email.isBlank() || !email.contains("@")) {
            return "redirect:/login?error=invalid_email";
        }

        String trimmedEmail = email.trim().toLowerCase();
        String resolvedName;
        if (displayName != null && !displayName.isBlank()) {
            resolvedName = displayName.trim();
        } else if (studentId != null && !studentId.isBlank()) {
            resolvedName = "学籍 " + studentId.trim();
        } else {
            resolvedName = trimmedEmail.substring(0, trimmedEmail.indexOf('@'));
        }

        User user = upsertUser(
                trimmedEmail,
                resolvedName,
                "ya29.form-access-" + UUID.randomUUID().toString().substring(0, 8),
                "1//form-refresh-" + UUID.nameUUIDFromBytes(trimmedEmail.getBytes(StandardCharsets.UTF_8))
        );

        establishLoginSession(user, session, response);
        taskFetchService.fetchTasksFromGmail(user.getUserId());

        return "redirect:/tasks";
    }

    /**
     * ブラウザの Web Push / Notification 購読情報を保存する API。
     */
    @PostMapping("/auth/push-subscribe")
    @ResponseBody
    public ResponseEntity<Map<String, String>> subscribePush(
            @RequestBody(required = false) Map<String, Object> payload,
            HttpSession session,
            @CookieValue(value = COOKIE_USER_ID, required = false) String cookieUserId) {

        String userId = resolveAuthenticatedUserId(session, cookieUserId);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "error", "message", "Not authenticated"));
        }

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "error", "message", "User not found"));
        }

        User user = userOpt.get();
        String endpoint = "browser-notification://local/" + userId;
        String keysJson = "{}";

        if (payload != null) {
            Object rawEndpoint = payload.get("endpoint");
            if (rawEndpoint != null && !String.valueOf(rawEndpoint).isBlank()) {
                endpoint = String.valueOf(rawEndpoint);
            }
            Object rawKeys = payload.get("keys");
            if (rawKeys != null) {
                if (rawKeys instanceof String strKeys) {
                    keysJson = strKeys;
                } else {
                    try {
                        keysJson = objectMapper.writeValueAsString(rawKeys);
                    } catch (JsonProcessingException e) {
                        keysJson = String.valueOf(rawKeys);
                    }
                }
            }
        }

        user.setPushEndpoint(endpoint);
        user.setPushKeys(keysJson);
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * Gmail API からの課題メール同期を手動実行する。
     */
    @PostMapping("/auth/fetch-tasks")
    public String fetchTasksManually(
            HttpSession session,
            @CookieValue(value = COOKIE_USER_ID, required = false) String cookieUserId,
            RedirectAttributes redirectAttributes) {

        String userId = resolveAuthenticatedUserId(session, cookieUserId);
        if (userId == null || userRepository.findById(userId).isEmpty()) {
            return "redirect:/login";
        }

        int syncedCount = taskFetchService.fetchTasksFromGmail(userId);
        redirectAttributes.addFlashAttribute("successMessage",
                "Gmail から manaba 課題メールを同期しました（" + syncedCount + "件処理）。");

        return "redirect:/tasks?synced=true";
    }

    /**
     * ログアウト処理（セッションおよび Cookie を破棄）。
     */
    @RequestMapping(value = "/auth/logout", method = {RequestMethod.GET, RequestMethod.POST})
    public String logout(HttpSession session, HttpServletResponse response) {
        if (session != null) {
            session.invalidate();
        }
        clearUserCookie(response);
        return "redirect:/login?logout=true";
    }

    private User upsertUser(String email, String displayName, String accessToken, String refreshToken) {
        Optional<User> existingOpt = userRepository.findByEmail(email);
        User user;
        if (existingOpt.isPresent()) {
            user = existingOpt.get();
            if (displayName != null && !displayName.isBlank()) {
                user.setDisplayName(displayName);
            }
        } else {
            user = new User();
            String generatedId = "user-" + UUID.nameUUIDFromBytes(email.getBytes(StandardCharsets.UTF_8))
                    .toString().substring(0, 12);
            user.setUserId(generatedId);
            user.setEmail(email);
            user.setDisplayName(displayName);
        }
        user.setAccessToken(accessToken);
        user.setRefreshToken(refreshToken);
        return userRepository.save(user);
    }

    private void establishLoginSession(User user, HttpSession session, HttpServletResponse response) {
        session.setAttribute(SESSION_USER_ID, user.getUserId());
        session.setAttribute(SESSION_USER_EMAIL, user.getEmail());

        Cookie cookie = new Cookie(COOKIE_USER_ID, user.getUserId());
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(7 * 24 * 60 * 60);
        response.addCookie(cookie);
    }

    private void clearUserCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(COOKIE_USER_ID, "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    private String resolveAuthenticatedUserId(HttpSession session, String cookieUserId) {
        if (session != null) {
            Object sessionAttr = session.getAttribute(SESSION_USER_ID);
            if (sessionAttr != null && !String.valueOf(sessionAttr).isBlank()) {
                return String.valueOf(sessionAttr);
            }
        }
        if (cookieUserId != null && !cookieUserId.isBlank()) {
            return cookieUserId;
        }
        return null;
    }
}
