package com.example.manabanotifier.controller;

import com.example.manabanotifier.entity.User;
import com.example.manabanotifier.repository.UserRepository;
import com.example.manabanotifier.service.TaskFetchService;
import com.example.manabanotifier.service.TaskFetchService.GmailFetchException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 連携・ログイン・Gmail同期・メール取り込み・Web Push購読コントローラ（担当：大友）。
 */
@Controller
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private static final String SESSION_USER_ID = "user_id";
    private static final String SESSION_USER_EMAIL = "user_email";
    private static final String SESSION_OAUTH_STATE = "oauth_state";
    private static final String COOKIE_USER_ID = "user_id";

    private static final String GMAIL_AUTH_GUIDE_MSG =
            "Gmail（imap.gmail.com）への接続認証に失敗しました。"
                    + "Googleの仕様上、通常のログインパスワードでは外部アプリからメールを取得できません。"
                    + "Googleアカウントの『16桁のアプリパスワード』（https://myaccount.google.com/apppasswords）を入力するか、"
                    + "OAuth 2.0 トークンで連携してください。";

    private final UserRepository userRepository;
    private final TaskFetchService taskFetchService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${google.oauth.client-id:}")
    private String oauthClientId;

    @Value("${google.oauth.client-secret:}")
    private String oauthClientSecret;

    @Value("${google.oauth.redirect-uri:http://localhost:8080/auth/callback}")
    private String oauthRedirectUri;

    public AuthController(
            UserRepository userRepository,
            TaskFetchService taskFetchService,
            ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.taskFetchService = taskFetchService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @GetMapping({"/", "/login"})
    public String loginPage(
            @RequestParam(required = false) String error,
            @RequestParam(required = false) String logout,
            @RequestParam(name = "oauth_unconfigured", required = false) String oauthUnconfigured,
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

        if ("true".equals(oauthUnconfigured)) {
            model.addAttribute("infoMessage",
                    "このサーバには Google Cloud の OAuth クライアントID/Secret が未設定です。"
                            + "設定不要で今すぐ実際の Gmail から小テストを取得できる『方法①：メールアドレス + Google 16桁アプリパスワード』、"
                            + "または『方法②：Gmail API アクセストークン直接入力』をご利用ください。");
        }

        if ("true".equals(logout)) {
            model.addAttribute("logoutMessage", "ログアウトしました。");
        }

        model.addAttribute("oauthConfigured", isOAuthConfigured());
        return "login";
    }

    /**
     * ログイン方法①：メールアドレス ＋ Google 16桁アプリパスワード（IMAPS 連携）。
     * または Gmail 連携なしのローカル利用（メール本文貼り付け／手動追加モード）。
     */
    @PostMapping("/auth/login")
    public String loginWithImap(
            @RequestParam String email,
            @RequestParam(required = false) String password,
            @RequestParam(required = false) String displayName,
            @RequestParam(required = false, defaultValue = "false") boolean allowManualWithoutGmail,
            HttpSession session,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes,
            Model model) {

        model.addAttribute("oauthConfigured", isOAuthConfigured());

        if (email == null || email.isBlank() || !email.contains("@")) {
            model.addAttribute("errorMessage", "有効なメールアドレスを入力してください。");
            return "login";
        }

        String trimmedEmail = email.trim().toLowerCase();
        String resolvedName = (displayName != null && !displayName.isBlank())
                ? displayName.trim()
                : trimmedEmail.substring(0, trimmedEmail.indexOf('@'));
        String userId = generateUserId(trimmedEmail);

        if (allowManualWithoutGmail) {
            User user = upsertUser(userId, trimmedEmail, resolvedName, null, null, null, "MANUAL");
            establishLoginSession(user, session, response);
            redirectAttributes.addFlashAttribute("infoMessage",
                    "Gmail連携なしでログインしました（" + trimmedEmail + "）。「manaba通知メール本文を貼り付けて追加」または手動追加をご利用ください。");
            return "redirect:/tasks";
        }

        if (password == null || password.isBlank()) {
            model.addAttribute("errorMessage", GMAIL_AUTH_GUIDE_MSG);
            model.addAttribute("attemptedEmail", trimmedEmail);
            model.addAttribute("attemptedDisplayName", displayName);
            model.addAttribute("showManualContinue", true);
            return "login";
        }

        String cleanPassword = password.replaceAll("\\s+", "");
        try {
            int syncedCount = taskFetchService.fetchTasksFromGmailImap(userId, trimmedEmail, cleanPassword);
            User user = upsertUser(userId, trimmedEmail, resolvedName, null, null, cleanPassword, "IMAP");
            establishLoginSession(user, session, response);

            if (syncedCount > 0) {
                redirectAttributes.addFlashAttribute("successMessage",
                        "Gmail（" + trimmedEmail + "）に接続し、" + syncedCount + " 件の manaba 小テスト・課題を自動取得しました。");
            } else {
                redirectAttributes.addFlashAttribute("infoMessage",
                        "Gmail（" + trimmedEmail + "）への接続に成功しましたが、該当する manaba 通知メールが見つかりませんでした。メール本文貼り付けや手動追加も利用できます。");
            }
            return "redirect:/tasks?synced=true";
        } catch (GmailFetchException ex) {
            log.warn("IMAP ログイン失敗: email={}, authFailed={}, msg={}", trimmedEmail, ex.isAuthFailed(), ex.getMessage());
            model.addAttribute("errorMessage", ex.isAuthFailed() ? GMAIL_AUTH_GUIDE_MSG : ex.getMessage());
            model.addAttribute("attemptedEmail", trimmedEmail);
            model.addAttribute("attemptedDisplayName", displayName);
            model.addAttribute("showManualContinue", true);
            return "login";
        }
    }

    /**
     * ログイン方法②-A：本物の Google OAuth 2.0 認可開始エンドポイント。
     */
    @GetMapping("/auth/google")
    public String startGoogleOAuth(HttpSession session) {
        if (!isOAuthConfigured()) {
            return "redirect:/login?oauth_unconfigured=true";
        }

        String state = UUID.randomUUID().toString();
        session.setAttribute(SESSION_OAUTH_STATE, state);

        String authUrl = UriComponentsBuilder.fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
                .queryParam("client_id", oauthClientId.trim())
                .queryParam("redirect_uri", oauthRedirectUri.trim())
                .queryParam("response_type", "code")
                .queryParam("scope", "https://www.googleapis.com/auth/gmail.readonly openid email profile")
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .queryParam("state", state)
                .encode(StandardCharsets.UTF_8)
                .toUriString();

        return "redirect:" + authUrl;
    }

    /**
     * ログイン方法②-A：本物の Google OAuth 2.0 コールバック処理。
     */
    @GetMapping("/auth/callback")
    public String handleGoogleCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            @RequestParam(required = false) String state,
            HttpSession session,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes) {

        if ((error != null && !error.isBlank()) || code == null || code.isBlank() || !isOAuthConfigured()) {
            log.warn("Google OAuth コールバック失敗: error={}, codePresent={}", error, code != null);
            return "redirect:/login?error=oauth_failed";
        }

        Object expectedState = session.getAttribute(SESSION_OAUTH_STATE);
        session.removeAttribute(SESSION_OAUTH_STATE);
        if (expectedState != null && state != null && !expectedState.toString().equals(state)) {
            log.warn("Google OAuth state 不一致");
            return "redirect:/login?error=oauth_failed";
        }

        try {
            String formBody = "code=" + urlEncode(code)
                    + "&client_id=" + urlEncode(oauthClientId.trim())
                    + "&client_secret=" + urlEncode(oauthClientSecret.trim())
                    + "&redirect_uri=" + urlEncode(oauthRedirectUri.trim())
                    + "&grant_type=authorization_code";

            HttpRequest tokenReq = HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> tokenRes = httpClient.send(
                    tokenReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (tokenRes.statusCode() < 200 || tokenRes.statusCode() >= 300) {
                log.error("Google token exchange failed: status={}, body={}", tokenRes.statusCode(), tokenRes.body());
                return "redirect:/login?error=oauth_failed";
            }

            JsonNode tokenJson = objectMapper.readTree(tokenRes.body());
            String accessToken = tokenJson.path("access_token").asText(null);
            String refreshToken = tokenJson.path("refresh_token").asText(null);
            if (accessToken == null || accessToken.isBlank()) {
                return "redirect:/login?error=oauth_failed";
            }

            HttpRequest userInfoReq = HttpRequest.newBuilder(URI.create("https://www.googleapis.com/oauth2/v2/userinfo"))
                    .header("Authorization", "Bearer " + accessToken)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> userInfoRes = httpClient.send(
                    userInfoReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (userInfoRes.statusCode() != 200) {
                return "redirect:/login?error=oauth_failed";
            }

            JsonNode profile = objectMapper.readTree(userInfoRes.body());
            String email = profile.path("email").asText("").trim().toLowerCase();
            String name = profile.path("name").asText("");
            if (email.isBlank()) {
                return "redirect:/login?error=oauth_failed";
            }
            if (name.isBlank()) {
                name = email.substring(0, email.indexOf('@'));
            }

            String userId = generateUserId(email);
            int syncedCount = taskFetchService.fetchTasksFromGmailApi(userId, accessToken);
            User user = upsertUser(userId, email, name, accessToken, refreshToken, null, "OAUTH2");
            establishLoginSession(user, session, response);

            redirectAttributes.addFlashAttribute("successMessage",
                    "Google OAuth 2.0 で連携し、" + syncedCount + " 件の manaba 小テスト・課題を取得しました。");
            return "redirect:/tasks?synced=true";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "redirect:/login?error=oauth_failed";
        } catch (IOException | GmailFetchException ex) {
            log.error("Google OAuth 連携エラー: {}", ex.getMessage(), ex);
            return "redirect:/login?error=oauth_failed";
        }
    }

    /**
     * ログイン方法②-B：Gmail API アクセストークン（ya29...）を直接入力して本物の Gmail API から取得する。
     */
    @PostMapping("/auth/oauth-token")
    public String loginWithOAuthToken(
            @RequestParam String email,
            @RequestParam String accessToken,
            @RequestParam(required = false) String displayName,
            HttpSession session,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes,
            Model model) {

        model.addAttribute("oauthConfigured", isOAuthConfigured());

        if (email == null || email.isBlank() || !email.contains("@")) {
            model.addAttribute("errorMessage", "有効なメールアドレスを入力してください。");
            return "login";
        }
        if (accessToken == null || accessToken.isBlank()) {
            model.addAttribute("errorMessage", "Gmail API アクセストークンを入力してください。");
            model.addAttribute("attemptedEmail", email.trim());
            return "login";
        }

        String trimmedEmail = email.trim().toLowerCase();
        String trimmedToken = accessToken.trim();
        String resolvedName = (displayName != null && !displayName.isBlank())
                ? displayName.trim()
                : trimmedEmail.substring(0, trimmedEmail.indexOf('@'));
        String userId = generateUserId(trimmedEmail);

        try {
            int syncedCount = taskFetchService.fetchTasksFromGmailApi(userId, trimmedToken);
            User user = upsertUser(userId, trimmedEmail, resolvedName, trimmedToken, null, null, "OAUTH2");
            establishLoginSession(user, session, response);

            redirectAttributes.addFlashAttribute("successMessage",
                    "Gmail API に接続し、" + syncedCount + " 件の manaba 小テスト・課題を取得しました。");
            return "redirect:/tasks?synced=true";
        } catch (GmailFetchException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            model.addAttribute("attemptedEmail", trimmedEmail);
            model.addAttribute("showManualContinue", true);
            return "login";
        }
    }

    /**
     * 貼り付けられた manaba 通知メール本文を解析して小テスト・課題を登録する。
     */
    @PostMapping("/auth/import-email")
    public String importEmailText(
            @RequestParam(required = false) String rawText,
            @RequestParam(required = false) String emailText,
            @RequestParam(required = false) String rawEmailText,
            HttpSession session,
            @CookieValue(value = COOKIE_USER_ID, required = false) String cookieUserId,
            RedirectAttributes redirectAttributes) {

        String userId = resolveAuthenticatedUserId(session, cookieUserId);
        if (userId == null || userRepository.findById(userId).isEmpty()) {
            return "redirect:/login";
        }

        String text = firstNonBlank(rawText, emailText, rawEmailText);
        if (text == null) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "取り込む manaba 通知メールの本文を入力してください。");
            return "redirect:/tasks";
        }

        int count = taskFetchService.importFromRawEmailText(userId, text);
        if (count == 0) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "メール本文から manaba の小テスト・課題情報を抽出できませんでした。"
                            + "「[コース名]」「[小テスト名] / [課題名]」「[受付終了日時]」が含まれているか確認してください。");
        } else {
            redirectAttributes.addFlashAttribute("successMessage",
                    "貼り付けたメール本文から " + count + " 件の小テスト・課題を取り込みました。");
        }
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
     * 実際の Gmail から manaba 課題・小テスト通知メールの手動同期を実行する。
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

        try {
            int syncedCount = taskFetchService.fetchTasksFromGmail(userId);
            if (syncedCount > 0) {
                redirectAttributes.addFlashAttribute("successMessage",
                        "Gmail から manaba の小テスト・課題メールを同期しました（" + syncedCount + "件）。");
            } else {
                redirectAttributes.addFlashAttribute("infoMessage",
                        "Gmail の確認が完了しましたが、新しく取り込める manaba 通知メールはありませんでした。");
            }
            return "redirect:/tasks?synced=true";
        } catch (GmailFetchException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/tasks";
        }
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

    private boolean isOAuthConfigured() {
        return oauthClientId != null && !oauthClientId.isBlank()
                && oauthClientSecret != null && !oauthClientSecret.isBlank();
    }

    private String generateUserId(String email) {
        return "user-" + UUID.nameUUIDFromBytes(email.getBytes(StandardCharsets.UTF_8))
                .toString().substring(0, 12);
    }

    private User upsertUser(
            String userId,
            String email,
            String displayName,
            String accessToken,
            String refreshToken,
            String imapPassword,
            String authType) {

        Optional<User> existingOpt = userRepository.findByEmail(email);
        User user;
        if (existingOpt.isPresent()) {
            user = existingOpt.get();
            if (displayName != null && !displayName.isBlank()) {
                user.setDisplayName(displayName);
            }
        } else {
            user = new User();
            user.setUserId(userId);
            user.setEmail(email);
            user.setDisplayName(displayName);
        }
        if (accessToken != null) {
            user.setAccessToken(accessToken);
        }
        if (refreshToken != null) {
            user.setRefreshToken(refreshToken);
        }
        if (imapPassword != null) {
            user.setImapPassword(imapPassword);
        }
        if (authType != null) {
            user.setAuthType(authType);
        }
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

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
