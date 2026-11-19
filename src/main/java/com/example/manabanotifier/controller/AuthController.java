package com.example.manabanotifier.controller;

import com.example.manabanotifier.entity.User;
import com.example.manabanotifier.repository.UserRepository;
import com.example.manabanotifier.security.SecurityFilter;
import com.example.manabanotifier.security.SecurityUtils;
import com.example.manabanotifier.service.TaskFetchService;
import com.example.manabanotifier.service.TaskFetchService.GasFetchResult;
import com.example.manabanotifier.service.TaskFetchService.GmailFetchException;
import com.example.manabanotifier.service.TaskFetchService.ImportResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 連携・ログイン（ブラウザ経由 GAS「自分のみ」公開 / Google OAuth 2.0 / GAS JSON 貼付）・Web Push購読コントローラ（担当：大友）。
 */
@Controller
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private static final String SESSION_USER_ID = "user_id";
    private static final String SESSION_USER_EMAIL = "user_email";
    private static final String SESSION_OAUTH_STATE = "oauth_state";
    private static final String SESSION_PENDING_GAS_URL = "pending_gas_url";
    private static final String SESSION_PENDING_EMAIL = "pending_email";
    private static final String SESSION_SYNC_SCANNED = "lastSyncScannedCount";
    private static final String SESSION_SYNC_SYNCED = "lastSyncSyncedCount";
    private static final String SESSION_SYNC_SKIPPED = "lastSyncSkippedSamples";
    private static final String COOKIE_USER_ID = "user_id";
    private static final Duration PENDING_GAS_TTL = Duration.ofMinutes(10);

    private record PendingGasFlow(String gasUrl, String email, Instant createdAt) {
    }

    private final ConcurrentHashMap<String, PendingGasFlow> pendingGasFlows = new ConcurrentHashMap<>();
    private final UserRepository userRepository;
    private final TaskFetchService taskFetchService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${app.public-mode:false}")
    private boolean publicMode;

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
            @RequestParam(name = "oauth_saved", required = false) String oauthSaved,
            @RequestParam(name = "shared_gas_saved", required = false) String sharedGasSaved,
            HttpSession session,
            HttpServletResponse response,
            Model model) {

        String authenticatedUserId = resolveAuthenticatedUserId(session);
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
        } else if ("gas_empty".equals(error)) {
            model.addAttribute("errorMessage", "GAS から送信されたメールデータが空でした。もう一度お試しください。");
        } else if (error != null && !error.isBlank()) {
            model.addAttribute("errorMessage", "ログイン処理でエラーが発生しました。再度お試しください。");
        }

        if ("true".equals(oauthUnconfigured)) {
            model.addAttribute("infoMessage",
                    "Google OAuth 2.0 のクライアントID / シークレットが未設定です。"
                            + "大学アカウント等では設定不要の『方法①：GAS ブラウザ連携（自分のみ公開）』が最も簡単です。"
                            + "OAuth 2.0 を使う場合は下の『方法②』からクライアントID/Secretを設定してください。");
            model.addAttribute("openOAuthSetup", true);
        }

        if ("true".equals(oauthSaved)) {
            model.addAttribute("successMessage",
                    "Google OAuth 2.0 設定を保存しました。方法②の「Google アカウントでログイン & Gmail 連携」ボタンから認証できます。");
            model.addAttribute("openOAuthSetup", true);
        }

        if ("true".equals(sharedGasSaved)) {
            model.addAttribute("successMessage",
                    "大学内共有 GAS ウェブアプリ URL を保存しました。これで他の学生も1クリックでログイン＆小テスト取得できます！");
        }

        if ("true".equals(logout)) {
            model.addAttribute("logoutMessage", "ログアウトしました。");
        }

        populateLoginModel(model);
        return "login";
    }

    /**
     * マルチユーザー共有モード：管理者が登録済みの共有 GAS ウェブアプリ（「アクセスしているユーザーとして実行」）を使って
     * 他の学生が設定不要・1クリックで自分の Gmail から小テストを取得＆ログインする。
     */
    @PostMapping("/auth/gas-shared-start")
    public String startSharedGasBrowserFlow(
            HttpSession session,
            Model model) {

        String sharedUrl = taskFetchService.getSharedGasUrl();
        if (!SecurityUtils.isValidGoogleAppsScriptUrl(sharedUrl)) {
            model.addAttribute("errorMessage", "共有 GAS ウェブアプリ URL が設定されていません。下の設定パネルから設定してください。");
            populateLoginModel(model);
            return "login";
        }

        session.setAttribute(SESSION_PENDING_GAS_URL, sharedUrl);
        session.removeAttribute(SESSION_PENDING_EMAIL);

        String gasState = registerPendingGasFlow(sharedUrl, null);
        return "redirect:" + buildGasBrowserRedirectUrl(sharedUrl, null, gasState);
    }

    /**
     * ローカル環境から大学内共有 GAS ウェブアプリ URL を保存する（サーバ再起動不要）。
     */
    @PostMapping("/auth/shared-gas-config")
    public String saveSharedGasConfig(
            @RequestParam String sharedGasUrl,
            HttpServletRequest request,
            HttpSession session,
            Model model) {

        if (!SecurityUtils.isLoopbackAddress(request.getRemoteAddr())) {
            model.addAttribute("errorMessage",
                    "セキュリティ保護のため、共有 GAS URL の画面変更はローカル環境（localhost）からのみ許可されています。クラウド公開時は環境変数 SHARED_GAS_URL を設定してください。");
            populateLoginModel(model);
            return "login";
        }
        if (taskFetchService.isSharedGasConfigured() && resolveAuthenticatedUserId(session) == null) {
            model.addAttribute("errorMessage",
                    "共有 GAS URL は既に設定済みです。変更するにはログインした状態で更新するか、環境変数 SHARED_GAS_URL を設定してください。");
            populateLoginModel(model);
            return "login";
        }

        try {
            taskFetchService.saveSharedGasUrl(sharedGasUrl);
            return "redirect:/login?shared_gas_saved=true";
        } catch (IllegalArgumentException | IOException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            populateLoginModel(model);
            return "login";
        }
    }

    /**
     * 方法①（最推奨）：ブラウザ経由 GAS 連携（アクセス権限：「自分のみ」対応）を開始し、GAS ウェブアプリへ 302 リダイレクトする。
     */
    @PostMapping("/auth/gas-browser-start")
    public String startGasBrowserFlow(
            @RequestParam String gasUrl,
            @RequestParam(required = false) String email,
            HttpSession session,
            Model model) {

        String trimmedUrl = gasUrl != null ? gasUrl.trim() : "";
        String trimmedEmail = (email != null && !email.isBlank()) ? email.trim().toLowerCase() : null;

        if (!SecurityUtils.isValidGoogleAppsScriptUrl(trimmedUrl)) {
            model.addAttribute("errorMessage",
                    "セキュリティ保護のため、Google Apps Script 公式のウェブアプリ URL（https://script.google.com/.../exec）のみ指定できます。");
            model.addAttribute("attemptedGasUrl", trimmedUrl);
            model.addAttribute("attemptedEmail", trimmedEmail);
            populateLoginModel(model);
            return "login";
        }

        session.setAttribute(SESSION_PENDING_GAS_URL, trimmedUrl);
        if (trimmedEmail != null) {
            session.setAttribute(SESSION_PENDING_EMAIL, trimmedEmail);
        } else {
            session.removeAttribute(SESSION_PENDING_EMAIL);
        }

        String gasState = registerPendingGasFlow(trimmedUrl, trimmedEmail);
        String redirectTarget = buildGasBrowserRedirectUrl(trimmedUrl, trimmedEmail, gasState);
        return "redirect:" + redirectTarget;
    }

    /**
     * 方法①コールバック：Google にログイン済みのユーザーブラウザが GAS HTML フォームから POST 送信した JSON ペイロードを受け取る。
     */
    @PostMapping("/auth/gas-callback")
    public String handleGasBrowserCallback(
            @RequestParam(required = false) String payload,
            @RequestParam(required = false) String gasState,
            HttpServletRequest request,
            HttpSession session,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes,
            Model model) {

        if (payload == null || payload.isBlank()) {
            return "redirect:/login?error=gas_empty";
        }

        ImportResult meta = taskFetchService.peekGasPayloadMetadata(payload);
        User existingSessionUser = resolveExistingUser(session);

        PendingGasFlow matchedPending = consumePendingGasFlow(gasState, meta.getDetectedEmail());
        if (gasState != null && !gasState.isBlank() && matchedPending == null) {
            log.warn("GAS コールバックの gasState が無効または期限切れです");
            return "redirect:/login?error=oauth_failed";
        }
        // GAS v1（gasState 非対応）の場合も、payload 内の gasUrl が Google Apps Script 公式 URL であること、
        // または直近の GAS 開始フローに一致することを要求する
        if (matchedPending == null && !SecurityUtils.isValidGoogleAppsScriptUrl(meta.getDetectedGasUrl())) {
            log.warn("GAS コールバックに有効な gasState も公式 gasUrl も含まれていません");
            return "redirect:/login?error=oauth_failed";
        }

        String pendingEmail = firstNonBlank(
                getSessionString(session, SESSION_PENDING_EMAIL),
                matchedPending != null ? matchedPending.email() : null
        );
        String pendingGasUrl = firstNonBlank(
                getSessionString(session, SESSION_PENDING_GAS_URL),
                matchedPending != null ? matchedPending.gasUrl() : null
        );

        String resolvedGasUrl = firstNonBlank(
                SecurityUtils.isValidGoogleAppsScriptUrl(pendingGasUrl) ? pendingGasUrl : null,
                meta.getDetectedGasUrl(),
                existingSessionUser != null ? existingSessionUser.getGasUrl() : null
        );
        String resolvedEmail = firstNonBlank(
                meta.getDetectedEmail(),
                pendingEmail,
                existingSessionUser != null ? existingSessionUser.getEmail() : null,
                "gas-" + shortHash(resolvedGasUrl != null ? resolvedGasUrl : payload) + "@manaba-sync.local"
        ).toLowerCase();

        String resolvedName = resolvedEmail.contains("@")
                ? resolvedEmail.substring(0, resolvedEmail.indexOf('@'))
                : resolvedEmail;
        String userId = generateUserId(resolvedEmail);

        try {
            User user = upsertUser(userId, resolvedEmail, resolvedName, null, null, resolvedGasUrl, null, "GAS_BROWSER");
            establishLoginSession(user, request, session, response);

            ImportResult result = taskFetchService.importFromGasPayload(user.getUserId(), payload);
            saveSyncDiagnostics(session, result);
            session.removeAttribute(SESSION_PENDING_GAS_URL);
            session.removeAttribute(SESSION_PENDING_EMAIL);

            if (result.getSyncedCount() > 0) {
                redirectAttributes.addFlashAttribute("successMessage",
                        "GAS（" + resolvedEmail + "）から " + result.getScannedEmailCount()
                                + " 件のメールをスキャンし、" + result.getSyncedCount() + " 件の小テスト・課題を取り込みました。");
            } else {
                redirectAttributes.addFlashAttribute("infoMessage",
                        "GAS（" + resolvedEmail + "）から " + result.getScannedEmailCount()
                                + " 件のメールをスキャンしましたが、自動登録できた小テストは 0 件でした（スキップ "
                                + result.getSkippedSamples().size() + " 件）。デバッグパネルでメール形式を確認してください。");
            }
            return "redirect:/tasks?synced=true";
        } catch (GmailFetchException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            model.addAttribute("attemptedGasUrl", resolvedGasUrl);
            model.addAttribute("attemptedEmail", resolvedEmail);
            populateLoginModel(model);
            return "login";
        }
    }

    /**
     * 方法③：GAS 出力 JSON を直接貼り付けてログイン＆取り込みを行う。
     */
    @PostMapping("/auth/gas-json-login")
    public String loginWithGasJson(
            @RequestParam String jsonPayload,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String displayName,
            HttpServletRequest request,
            HttpSession session,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes,
            Model model) {

        if (jsonPayload == null || jsonPayload.isBlank()) {
            model.addAttribute("errorMessage", "GAS が出力した JSON データを貼り付けてください。");
            populateLoginModel(model);
            return "login";
        }

        ImportResult meta = taskFetchService.peekGasPayloadMetadata(jsonPayload);
        String resolvedEmail = firstNonBlank(
                email != null ? email.trim().toLowerCase() : null,
                meta.getDetectedEmail(),
                "gas-json-" + shortHash(jsonPayload) + "@manaba-sync.local"
        );
        if (isProtectedAccountTakeover(resolvedEmail, session)) {
            model.addAttribute("errorMessage",
                    "このメールアドレスは既に Google 連携アカウントとして登録されています。なりすまし防止のため、方法①（GAS連携）または方法②（Google OAuth 2.0）からログインしてください。");
            model.addAttribute("attemptedEmail", resolvedEmail);
            populateLoginModel(model);
            return "login";
        }
        String resolvedName = (displayName != null && !displayName.isBlank())
                ? displayName.trim()
                : (resolvedEmail.contains("@") ? resolvedEmail.substring(0, resolvedEmail.indexOf('@')) : resolvedEmail);
        String userId = generateUserId(resolvedEmail);

        try {
            User user = upsertUser(userId, resolvedEmail, resolvedName, null, null, meta.getDetectedGasUrl(), null, "GAS_JSON");
            establishLoginSession(user, request, session, response);

            ImportResult result = taskFetchService.importFromGasPayload(user.getUserId(), jsonPayload);
            saveSyncDiagnostics(session, result);

            if (result.getSyncedCount() > 0) {
                redirectAttributes.addFlashAttribute("successMessage",
                        "GAS JSON（" + resolvedEmail + "）から " + result.getScannedEmailCount()
                                + " 件中 " + result.getSyncedCount() + " 件の小テスト・課題を取り込みました。");
            } else {
                redirectAttributes.addFlashAttribute("infoMessage",
                        "GAS JSON（" + result.getScannedEmailCount()
                                + " 件）を読み込みましたが、自動登録できた小テストは 0 件でした。デバッグパネルを確認してください。");
            }
            return "redirect:/tasks?synced=true";
        } catch (GmailFetchException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            model.addAttribute("attemptedEmail", email);
            populateLoginModel(model);
            return "login";
        }
    }

    /**
     * 画面から Google OAuth 2.0 クライアントID / Secret を保存する（ローカルホスト限定・サーバ再起動不要）。
     */
    @PostMapping("/auth/oauth-config")
    public String saveOAuthConfig(
            @RequestParam String clientId,
            @RequestParam String clientSecret,
            HttpServletRequest request,
            HttpSession session,
            Model model) {

        if (!SecurityUtils.isLoopbackAddress(request.getRemoteAddr())) {
            model.addAttribute("errorMessage", "セキュリティ保護のため、OAuth 設定の変更はローカル環境（localhost）からのみ許可されています。");
            populateLoginModel(model);
            return "login";
        }
        if (taskFetchService.isOAuthConfigured() && resolveAuthenticatedUserId(session) == null) {
            model.addAttribute("errorMessage", "既に OAuth 設定が登録済みです。上書きするにはログインした状態で変更するか、サーバ設定ファイルを直接更新してください。");
            model.addAttribute("openOAuthSetup", true);
            populateLoginModel(model);
            return "login";
        }

        try {
            taskFetchService.saveOAuthConfig(clientId, clientSecret);
            return "redirect:/login?oauth_saved=true";
        } catch (IllegalArgumentException | IOException ex) {
            model.addAttribute("errorMessage", "OAuth 設定の保存に失敗しました。入力値を確認してください。");
            model.addAttribute("openOAuthSetup", true);
            populateLoginModel(model);
            return "login";
        }
    }

    /**
     * 方法②：本物の Google OAuth 2.0 認可開始エンドポイント。
     */
    @GetMapping("/auth/google")
    public String startGoogleOAuth(HttpSession session) {
        if (!taskFetchService.isOAuthConfigured()) {
            return "redirect:/login?oauth_unconfigured=true";
        }

        String state = UUID.randomUUID().toString();
        session.setAttribute(SESSION_OAUTH_STATE, state);

        String authUrl = UriComponentsBuilder.fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
                .queryParam("client_id", taskFetchService.getOAuthClientId())
                .queryParam("redirect_uri", taskFetchService.getOAuthRedirectUri())
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
     * 方法②：本物の Google OAuth 2.0 コールバック処理。
     */
    @GetMapping("/auth/callback")
    public String handleGoogleCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            @RequestParam(required = false) String state,
            HttpServletRequest request,
            HttpSession session,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes) {

        if ((error != null && !error.isBlank()) || code == null || code.isBlank()
                || !taskFetchService.isOAuthConfigured()) {
            log.warn("Google OAuth コールバック失敗: error={}, codePresent={}", error, code != null);
            return "redirect:/login?error=oauth_failed";
        }

        Object expectedState = session.getAttribute(SESSION_OAUTH_STATE);
        session.removeAttribute(SESSION_OAUTH_STATE);
        if (!(expectedState instanceof String expectedStr) || expectedStr.isBlank()
                || state == null || state.isBlank()
                || !SecurityUtils.constantTimeEquals(expectedStr, state)) {
            log.warn("Google OAuth state 検証失敗（欠落または不一致）");
            return "redirect:/login?error=oauth_failed";
        }

        try {
            String formBody = "code=" + urlEncode(code)
                    + "&client_id=" + urlEncode(taskFetchService.getOAuthClientId())
                    + "&client_secret=" + urlEncode(taskFetchService.getOAuthClientSecret())
                    + "&redirect_uri=" + urlEncode(taskFetchService.getOAuthRedirectUri())
                    + "&grant_type=authorization_code";

            HttpRequest tokenReq = HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> tokenRes = httpClient.send(
                    tokenReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (tokenRes.statusCode() < 200 || tokenRes.statusCode() >= 300) {
                log.error("Google token exchange failed: status={}", tokenRes.statusCode());
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
            User user = upsertUser(userId, email, name, accessToken, refreshToken, null, null, "OAUTH2");
            establishLoginSession(user, request, session, response);

            redirectAttributes.addFlashAttribute("successMessage",
                    "Google OAuth 2.0（" + email + "）で連携し、" + syncedCount + " 件の manaba 小テスト・課題を取得しました。");
            return "redirect:/tasks?synced=true";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "redirect:/login?error=oauth_failed";
        } catch (IOException | GmailFetchException ex) {
            log.error("Google OAuth 連携エラー: {}", ex.getMessage());
            return "redirect:/login?error=oauth_failed";
        }
    }

    /**
     * 方法②-B：OAuth 2.0 Playground 等で取得した Gmail API アクセストークン（ya29...）で即時連携する。
     * トークン所有者のメールアドレスを Google Gmail API (/users/me/profile) で直接検証する（なりすまし防止）。
     */
    @PostMapping("/auth/oauth-token")
    public String loginWithOAuthToken(
            @RequestParam(required = false) String email,
            @RequestParam String accessToken,
            @RequestParam(required = false) String displayName,
            HttpServletRequest request,
            HttpSession session,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes,
            Model model) {

        if (accessToken == null || accessToken.isBlank()) {
            model.addAttribute("errorMessage", "Gmail API アクセストークンを入力してください。");
            model.addAttribute("attemptedEmail", email != null ? email.trim() : "");
            populateLoginModel(model);
            return "login";
        }

        String trimmedToken = accessToken.trim();
        try {
            // 入力されたメールアドレスを信用せず、トークン本来の所有者メールアドレスを Google API で検証する
            String verifiedEmail = taskFetchService.verifyGmailApiProfileEmail(trimmedToken);
            String resolvedName = (displayName != null && !displayName.isBlank())
                    ? displayName.trim()
                    : verifiedEmail.substring(0, verifiedEmail.indexOf('@'));
            String userId = generateUserId(verifiedEmail);

            int syncedCount = taskFetchService.fetchTasksFromGmailApi(userId, trimmedToken);
            User user = upsertUser(userId, verifiedEmail, resolvedName, trimmedToken, null, null, null, "OAUTH2");
            establishLoginSession(user, request, session, response);

            redirectAttributes.addFlashAttribute("successMessage",
                    "Gmail API（" + verifiedEmail + "）に接続し、" + syncedCount + " 件の manaba 小テスト・課題を取得しました。");
            return "redirect:/tasks?synced=true";
        } catch (GmailFetchException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            model.addAttribute("attemptedEmail", email != null ? email.trim() : "");
            model.addAttribute("openOAuthSetup", true);
            populateLoginModel(model);
            return "login";
        }
    }

    /**
     * サーバ直接呼び出しによる GAS ウェブアプリ連携（互換性維持）。
     */
    @PostMapping("/auth/gas-login")
    public String loginWithGas(
            @RequestParam String gasUrl,
            @RequestParam(required = false, defaultValue = "manaba-sync-2026") String gasToken,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String displayName,
            HttpServletRequest request,
            HttpSession session,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes,
            Model model) {

        String trimmedUrl = gasUrl != null ? gasUrl.trim() : "";
        String trimmedToken = gasToken != null ? gasToken.trim() : "manaba-sync-2026";
        String trimmedEmail = (email != null && !email.isBlank()) ? email.trim().toLowerCase() : null;

        try {
            GasFetchResult result = taskFetchService.fetchTasksFromGasForLogin(trimmedEmail, trimmedUrl, trimmedToken);
            String resolvedEmail = result.getEmail();
            String resolvedName = (displayName != null && !displayName.isBlank())
                    ? displayName.trim()
                    : resolvedEmail.substring(0, resolvedEmail.indexOf('@'));

            User user = upsertUser(
                    result.getUserId(),
                    resolvedEmail,
                    resolvedName,
                    null,
                    null,
                    trimmedUrl,
                    trimmedToken,
                    "GAS"
            );
            establishLoginSession(user, request, session, response);

            redirectAttributes.addFlashAttribute("successMessage",
                    "GAS ウェブアプリ（" + resolvedEmail + "）から "
                            + result.getSyncedCount() + " 件の小テスト・課題を取り込みました。");
            return "redirect:/tasks?synced=true";
        } catch (GmailFetchException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            model.addAttribute("attemptedGasUrl", trimmedUrl);
            model.addAttribute("attemptedEmail", trimmedEmail);
            populateLoginModel(model);
            return "login";
        }
    }

    /**
     * メール連携なしでログイン（メールアドレスのみ入力・パスワード不要）。
     * 既存の Google / GAS 連携アカウントへのなりすましログインは拒否する。
     */
    @PostMapping("/auth/login")
    public String loginWithoutGmail(
            @RequestParam String email,
            @RequestParam(required = false) String displayName,
            HttpServletRequest request,
            HttpSession session,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes,
            Model model) {

        if (publicMode) {
            model.addAttribute("errorMessage",
                    "公開マルチユーザーモードでは、なりすまし防止のためメール連携なしの簡易ログインは無効化されています。大学 Google アカウント（GAS連携 / Google OAuth 2.0）からログインしてください。");
            populateLoginModel(model);
            return "login";
        }

        if (email == null || email.isBlank() || !email.contains("@") || email.trim().length() > 254) {
            model.addAttribute("errorMessage", "有効なメールアドレスを入力してください。");
            populateLoginModel(model);
            return "login";
        }

        String trimmedEmail = email.trim().toLowerCase();
        if (isProtectedAccountTakeover(trimmedEmail, session)) {
            model.addAttribute("errorMessage",
                    "このメールアドレスは既に Google / GAS 連携アカウントとして登録されています。なりすまし防止のため、方法①（GAS連携）または方法②（Google OAuth 2.0）からログインしてください。");
            model.addAttribute("attemptedEmail", trimmedEmail);
            populateLoginModel(model);
            return "login";
        }

        String resolvedName = (displayName != null && !displayName.isBlank())
                ? displayName.trim()
                : trimmedEmail.substring(0, trimmedEmail.indexOf('@'));
        String userId = generateUserId(trimmedEmail);

        User user = upsertUser(userId, trimmedEmail, resolvedName, null, null, null, null, "MANUAL");
        establishLoginSession(user, request, session, response);

        redirectAttributes.addFlashAttribute("infoMessage",
                "ログインしました（" + trimmedEmail + "）。「manaba通知メール / GAS JSON を貼り付けて追加」または手動追加から小テストを登録できます。");
        return "redirect:/tasks";
    }

    /**
     * 貼り付けられた manaba 通知メール本文または GAS JSON を解析して登録する。
     */
    @PostMapping("/auth/import-email")
    public String importEmailText(
            @RequestParam(required = false) String rawText,
            @RequestParam(required = false) String emailText,
            @RequestParam(required = false) String rawEmailText,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        String userId = resolveAuthenticatedUserId(session);
        if (userId == null || userRepository.findById(userId).isEmpty()) {
            return "redirect:/login";
        }

        String text = firstNonBlank(rawText, emailText, rawEmailText);
        if (text == null) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "取り込む manaba 通知メール本文または GAS JSON を入力してください。");
            return "redirect:/tasks";
        }

        String trimmed = text.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                ImportResult result = taskFetchService.importFromGasPayload(userId, trimmed);
                saveSyncDiagnostics(session, result);
                if (result.getSyncedCount() == 0) {
                    redirectAttributes.addFlashAttribute("infoMessage",
                            "JSON（" + result.getScannedEmailCount()
                                    + " 件）を読み込みましたが、自動登録できた小テストは 0 件でした。デバッグパネルで形式を確認してください。");
                } else {
                    redirectAttributes.addFlashAttribute("successMessage",
                            "GAS JSON から " + result.getSyncedCount() + " 件の小テスト・課題を取り込みました。");
                }
                return "redirect:/tasks";
            } catch (GmailFetchException ex) {
                redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
                return "redirect:/tasks";
            }
        }

        int count = taskFetchService.importFromRawEmailText(userId, text);
        if (count == 0) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "入力内容から manaba の小テスト・課題情報を抽出できませんでした。"
                            + "「[コース名]」「[小テスト名] / [課題名]」「[受付終了日時]」または GAS JSON 形式か確認してください。");
        } else {
            redirectAttributes.addFlashAttribute("successMessage",
                    "貼り付けた内容から " + count + " 件の小テスト・課題を取り込みました。");
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
            HttpSession session) {

        String userId = resolveAuthenticatedUserId(session);
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
                String candidate = String.valueOf(rawEndpoint).trim();
                if (candidate.length() <= 500
                        && (candidate.startsWith("https://") || candidate.startsWith("browser-notification://"))) {
                    endpoint = candidate;
                }
            }
            Object rawKeys = payload.get("keys");
            if (rawKeys != null) {
                String serialized;
                if (rawKeys instanceof String strKeys) {
                    serialized = strKeys;
                } else {
                    try {
                        serialized = objectMapper.writeValueAsString(rawKeys);
                    } catch (JsonProcessingException e) {
                        serialized = "{}";
                    }
                }
                if (serialized.length() <= 2000) {
                    keysJson = serialized;
                }
            }
        }

        user.setPushEndpoint(endpoint);
        user.setPushKeys(keysJson);
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * 連携済み方式（ブラウザ経由 GAS「自分のみ」 / Google OAuth 2.0 / GAS URL）から手動同期を実行する。
     */
    @PostMapping("/auth/fetch-tasks")
    public String fetchTasksManually(
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        String userId = resolveAuthenticatedUserId(session);
        if (userId == null) {
            return "redirect:/login";
        }
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return "redirect:/login";
        }

        User user = userOpt.get();
        if ("GAS_BROWSER".equalsIgnoreCase(user.getAuthType())
                && SecurityUtils.isValidGoogleAppsScriptUrl(user.getGasUrl())) {
            session.setAttribute(SESSION_PENDING_GAS_URL, user.getGasUrl());
            if (user.getEmail() != null) {
                session.setAttribute(SESSION_PENDING_EMAIL, user.getEmail());
            }
            String gasState = registerPendingGasFlow(user.getGasUrl(), user.getEmail());
            return "redirect:" + buildGasBrowserRedirectUrl(user.getGasUrl(), user.getEmail(), gasState);
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
     * ログアウト処理（CSRF 対策のため POST のみ受付。セッションおよび旧 Cookie を破棄）。
     */
    @PostMapping("/auth/logout")
    public String logout(HttpSession session, HttpServletResponse response) {
        if (session != null) {
            session.invalidate();
        }
        clearUserCookie(response);
        return "redirect:/login?logout=true";
    }

    private String registerPendingGasFlow(String gasUrl, String email) {
        purgeExpiredPendingGasFlows();
        String state = SecurityUtils.generateSecureToken();
        pendingGasFlows.put(state, new PendingGasFlow(gasUrl, email, Instant.now()));
        return state;
    }

    private PendingGasFlow consumePendingGasFlow(String gasState, String detectedEmail) {
        purgeExpiredPendingGasFlows();
        if (gasState != null && !gasState.isBlank()) {
            return pendingGasFlows.remove(gasState.trim());
        }
        if (detectedEmail != null && !detectedEmail.isBlank()) {
            String target = detectedEmail.trim().toLowerCase();
            for (Map.Entry<String, PendingGasFlow> entry : pendingGasFlows.entrySet()) {
                PendingGasFlow flow = entry.getValue();
                if (flow.email() != null && flow.email().equalsIgnoreCase(target)) {
                    return pendingGasFlows.remove(entry.getKey());
                }
            }
        }
        return null;
    }

    private void purgeExpiredPendingGasFlows() {
        Instant cutoff = Instant.now().minus(PENDING_GAS_TTL);
        pendingGasFlows.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(cutoff));
    }

    private String buildGasBrowserRedirectUrl(String gasUrl, String email, String gasState) {
        String origin = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        String returnUrl = origin + "/auth/gas-callback";

        // Google Apps Script (ESF) reserves the "callback" query param for JSONP and returns HTTP 400 for URL values.
        // Use "returnUrl" + one-time "gasState" instead.
        String separator = gasUrl.contains("?") ? "&" : "?";
        StringBuilder sb = new StringBuilder(gasUrl);
        sb.append(separator).append("returnUrl=").append(urlEncode(returnUrl));
        sb.append("&gasUrl=").append(urlEncode(gasUrl));
        if (gasState != null && !gasState.isBlank()) {
            sb.append("&gasState=").append(urlEncode(gasState));
        }
        if (email != null && !email.isBlank()) {
            sb.append("&email=").append(urlEncode(email));
        }
        return sb.toString();
    }

    private void saveSyncDiagnostics(HttpSession session, ImportResult result) {
        session.setAttribute(SESSION_SYNC_SCANNED, result.getScannedEmailCount());
        session.setAttribute(SESSION_SYNC_SYNCED, result.getSyncedCount());
        session.setAttribute(SESSION_SYNC_SKIPPED, result.getSkippedSamples());
    }

    private void populateLoginModel(Model model) {
        String currentOrigin;
        try {
            currentOrigin = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        } catch (Exception ex) {
            currentOrigin = "http://localhost:8080";
        }
        model.addAttribute("appOrigin", currentOrigin);
        model.addAttribute("sharedGasConfigured", taskFetchService.isSharedGasConfigured());
        model.addAttribute("sharedGasUrl", taskFetchService.getSharedGasUrl());
        model.addAttribute("publicMode", publicMode);
        model.addAttribute("oauthConfigured", taskFetchService.isOAuthConfigured());
        model.addAttribute("oauthRedirectUri", currentOrigin + "/auth/callback");
        model.addAttribute("oauthClientId", taskFetchService.getOAuthClientId());
    }

    private User resolveExistingUser(HttpSession session) {
        String userId = resolveAuthenticatedUserId(session);
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).orElse(null);
    }

    private boolean isProtectedAccountTakeover(String email, HttpSession session) {
        if (email == null || email.isBlank()) {
            return false;
        }
        Optional<User> existingOpt = userRepository.findByEmail(email.trim().toLowerCase());
        if (existingOpt.isEmpty()) {
            return false;
        }
        User existing = existingOpt.get();
        String currentUserId = resolveAuthenticatedUserId(session);
        if (currentUserId != null && currentUserId.equals(existing.getUserId())) {
            return false;
        }
        String authType = existing.getAuthType();
        return "OAUTH2".equalsIgnoreCase(authType)
                || "GAS_BROWSER".equalsIgnoreCase(authType)
                || "GAS".equalsIgnoreCase(authType);
    }

    private String getSessionString(HttpSession session, String key) {
        if (session == null) {
            return null;
        }
        Object val = session.getAttribute(key);
        if (val == null) {
            return null;
        }
        String s = String.valueOf(val).trim();
        return s.isEmpty() ? null : s;
    }

    private String generateUserId(String email) {
        return "user-" + UUID.nameUUIDFromBytes(email.getBytes(StandardCharsets.UTF_8))
                .toString().substring(0, 12);
    }

    private String shortHash(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8))
                .toString().substring(0, 8);
    }

    private User upsertUser(
            String userId,
            String email,
            String displayName,
            String accessToken,
            String refreshToken,
            String gasUrl,
            String gasToken,
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
        if (gasUrl != null) {
            user.setGasUrl(gasUrl);
        }
        if (gasToken != null) {
            user.setGasToken(gasToken);
        }
        if (authType != null) {
            user.setAuthType(authType);
        }
        return userRepository.save(user);
    }

    private void establishLoginSession(
            User user,
            HttpServletRequest request,
            HttpSession session,
            HttpServletResponse response) {
        // セッション固定化攻撃（Session Fixation）対策：ログイン時にセッション ID と CSRF トークンを再生成する
        if (request != null && request.getSession(false) != null) {
            request.changeSessionId();
        }
        session.setAttribute(SESSION_USER_ID, user.getUserId());
        session.setAttribute(SESSION_USER_EMAIL, user.getEmail());
        session.setAttribute(SecurityFilter.SESSION_CSRF_TOKEN, SecurityUtils.generateSecureToken());

        // 署名なし user_id Cookie による偽装認証を防ぐため、旧 Cookie が残っていれば破棄し、
        // 認証状態はサーバ側 HttpSession（暗号論的ランダムな HttpOnly + SameSite=Lax の JSESSIONID）のみで管理する
        clearUserCookie(response);
    }

    private void clearUserCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(COOKIE_USER_ID, "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    private String resolveAuthenticatedUserId(HttpSession session) {
        if (session != null) {
            Object sessionAttr = session.getAttribute(SESSION_USER_ID);
            if (sessionAttr != null && !String.valueOf(sessionAttr).isBlank()) {
                return String.valueOf(sessionAttr);
            }
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
