/**
 * ブラウザ Notification API / Service Worker 連携クライアント（担当：大友）
 */
(function () {
    "use strict";

    let swRegistration = null;

    function getNotificationSupport() {
        return typeof window !== "undefined" && "Notification" in window;
    }

    function updatePushStatusUI() {
        const badge = document.getElementById("push-status-badge");
        const text = document.getElementById("push-status-text");

        if (!badge && !text) {
            return;
        }

        if (!getNotificationSupport()) {
            if (badge) {
                badge.className = "badge badge-muted";
                badge.textContent = "非対応ブラウザ";
            }
            if (text) {
                text.textContent = "お使いのブラウザは通知APIに対応していません。";
            }
            return;
        }

        const perm = Notification.permission;
        if (perm === "granted") {
            if (badge) {
                badge.className = "badge badge-success";
                badge.textContent = "許可済み (ON)";
            }
            if (text) {
                text.textContent = "ブラウザ / スマートフォンへのプッシュ通知を受信できます。";
            }
        } else if (perm === "denied") {
            if (badge) {
                badge.className = "badge badge-danger";
                badge.textContent = "ブロック中 (OFF)";
            }
            if (text) {
                text.textContent = "ブラウザのサイト設定から通知を許可してください。";
            }
        } else {
            if (badge) {
                badge.className = "badge badge-warning";
                badge.textContent = "未許可";
            }
            if (text) {
                text.textContent = "「通知を許可する」ボタンを押すと締切リマインドを受信できます。";
            }
        }
    }

    async function registerServiceWorker() {
        if (!("serviceWorker" in navigator)) {
            return null;
        }
        try {
            swRegistration = await navigator.serviceWorker.register("/sw.js");
            return swRegistration;
        } catch (err) {
            console.warn("Service Worker registration skipped:", err);
            return null;
        }
    }

    function getCsrfToken() {
        const meta = document.querySelector('meta[name="csrf-token"]');
        return meta ? meta.getAttribute("content") : "";
    }

    function sanitizeInternalUrl(url) {
        if (typeof url === "string" && url.startsWith("/") && !url.startsWith("//")) {
            return url;
        }
        return "/tasks";
    }

    async function sendSubscriptionToServer(subscriptionPayload) {
        try {
            const headers = { "Content-Type": "application/json" };
            const csrfToken = getCsrfToken();
            if (csrfToken) {
                headers["X-CSRF-TOKEN"] = csrfToken;
            }
            const res = await fetch("/auth/push-subscribe", {
                method: "POST",
                headers: headers,
                credentials: "same-origin",
                body: JSON.stringify(subscriptionPayload)
            });
            if (res.ok) {
                return await res.json();
            }
            return { status: "skipped", httpStatus: res.status };
        } catch (err) {
            console.warn("Push subscription upload failed:", err);
            return { status: "error" };
        }
    }

    async function showNotification(title, body, url) {
        if (!getNotificationSupport() || Notification.permission !== "granted") {
            return false;
        }
        const targetUrl = sanitizeInternalUrl(url);
        try {
            if (swRegistration && swRegistration.showNotification) {
                await swRegistration.showNotification(title, {
                    body: body,
                    data: { url: targetUrl }
                });
                return true;
            }
        } catch (err) {
            // Fall back to standard Notification constructor
        }

        try {
            const n = new Notification(title, { body: body });
            n.onclick = function () {
                window.focus();
                window.location.href = targetUrl;
            };
            return true;
        } catch (err) {
            console.warn("Notification display failed:", err);
            return false;
        }
    }

    async function requestPermissionAndSubscribe() {
        if (!getNotificationSupport()) {
            alert("このブラウザは Web 通知に対応していません。");
            updatePushStatusUI();
            return false;
        }

        const permission = await Notification.requestPermission();
        updatePushStatusUI();

        if (permission !== "granted") {
            return false;
        }

        if (!swRegistration) {
            await registerServiceWorker();
        }

        let payload = {
            endpoint: "browser-notification://web/" + (navigator.userAgent ? btoa(unescape(encodeURIComponent(navigator.userAgent))).slice(0, 16) : "client"),
            keys: {
                mode: "browser-notification-api",
                registeredAt: new Date().toISOString()
            }
        };

        if (swRegistration && swRegistration.pushManager) {
            try {
                const existingSub = await swRegistration.pushManager.getSubscription();
                if (existingSub) {
                    const subJson = existingSub.toJSON();
                    payload = {
                        endpoint: subJson.endpoint || payload.endpoint,
                        keys: subJson.keys || payload.keys
                    };
                }
            } catch (err) {
                // Use fallback browser notification descriptor when VAPID key is not set
            }
        }

        await sendSubscriptionToServer(payload);
        await showNotification(
            "manaba 課題通知システム",
            "通知が有効になりました。締切前のリマインドをお届けします。",
            "/tasks"
        );
        return true;
    }

    async function pollRecentNotifications() {
        if (window.location.pathname === "/" || window.location.pathname === "/login") {
            return;
        }
        try {
            const res = await fetch("/api/notifications/recent", { credentials: "same-origin" });
            if (!res.ok) {
                return;
            }
            const items = await res.json();
            if (!Array.isArray(items)) {
                return;
            }

            const seenKey = "manaba_seen_notification_ids";
            const seenRaw = sessionStorage.getItem(seenKey);
            const seenIds = new Set(seenRaw ? JSON.parse(seenRaw) : []);
            let updated = false;

            for (const item of items) {
                const id = item.logId || item.id;
                if (!id || seenIds.has(id)) {
                    continue;
                }
                seenIds.add(id);
                updated = true;

                if (item.status === "SUCCESS" && getNotificationSupport() && Notification.permission === "granted") {
                    await showNotification(
                        "manaba 課題リマインド",
                        item.message || "未提出課題の締切が近づいています。",
                        "/tasks"
                    );
                }
            }

            if (updated) {
                sessionStorage.setItem(seenKey, JSON.stringify(Array.from(seenIds).slice(-50)));
            }
        } catch (err) {
            // Silently ignore if endpoint is unavailable
        }
    }

    window.ManabaPush = {
        requestPermissionAndSubscribe: requestPermissionAndSubscribe,
        showNotification: showNotification,
        updatePushStatusUI: updatePushStatusUI,
        pollRecentNotifications: pollRecentNotifications
    };

    document.addEventListener("DOMContentLoaded", async function () {
        await registerServiceWorker();
        updatePushStatusUI();

        const bindButtons = document.querySelectorAll('[data-action="enable-push"], #enable-push-btn');
        bindButtons.forEach((btn) => {
            btn.addEventListener("click", function (e) {
                e.preventDefault();
                requestPermissionAndSubscribe();
            });
        });

        // ログイン状態かつ通知許可済みの場合はサーバーへ購読情報を自動同期
        if (getNotificationSupport() && Notification.permission === "granted"
            && window.location.pathname !== "/" && window.location.pathname !== "/login") {
            sendSubscriptionToServer({
                endpoint: "browser-notification://web/active",
                keys: { mode: "browser-notification-api", syncedAt: new Date().toISOString() }
            });
        }

        pollRecentNotifications();
        setInterval(pollRecentNotifications, 30000);
    });
})();
