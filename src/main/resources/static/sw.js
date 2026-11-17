// Service Worker for manaba 課題通知システム (Team 12)
self.addEventListener("install", (event) => {
    self.skipWaiting();
});

self.addEventListener("activate", (event) => {
    event.waitUntil(self.clients.claim());
});

self.addEventListener("push", (event) => {
    let data = {
        title: "manaba 課題通知",
        body: "未提出の課題の締め切りが近づいています。",
        url: "/tasks"
    };

    if (event.data) {
        try {
            const parsed = event.data.json();
            data = {
                title: parsed.title || data.title,
                body: parsed.body || data.body,
                url: parsed.url || data.url
            };
        } catch (e) {
            data.body = event.data.text();
        }
    }

    event.waitUntil(
        self.registration.showNotification(data.title, {
            body: data.body,
            tag: "manaba-task-reminder",
            data: { url: data.url }
        })
    );
});

self.addEventListener("notificationclick", (event) => {
    event.notification.close();
    const targetUrl = (event.notification.data && event.notification.data.url) || "/tasks";

    event.waitUntil(
        self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((clientList) => {
            for (const client of clientList) {
                if (client.url.includes(targetUrl) && "focus" in client) {
                    return client.focus();
                }
            }
            if (self.clients.openWindow) {
                return self.clients.openWindow(targetUrl);
            }
        })
    );
});
