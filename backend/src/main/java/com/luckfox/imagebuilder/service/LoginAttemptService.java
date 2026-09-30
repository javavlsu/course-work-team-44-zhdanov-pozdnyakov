package com.luckfox.imagebuilder.service;

import com.luckfox.imagebuilder.exception.TooManyRequestsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Счётчик неудачных входов по адресу клиента и по email в фиксированном окне.
 * Превысивший лимит получает 429 ещё до проверки пароля — перебор не тратит CPU на BCrypt.
 * Хранится в памяти процесса: для нескольких экземпляров backend нужен общий Redis.
 */
@Service
public class LoginAttemptService {

    private static final int PRUNE_THRESHOLD = 10_000;

    private record Window(long startMillis, int failures) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxPerEmail;
    private final int maxPerIp;
    private final long windowMillis;

    public LoginAttemptService(@Value("${security.login.max-failures-per-email:5}") int maxPerEmail,
                               @Value("${security.login.max-failures-per-ip:20}") int maxPerIp,
                               @Value("${security.login.window-seconds:300}") long windowSeconds) {
        this.maxPerEmail = maxPerEmail;
        this.maxPerIp = maxPerIp;
        this.windowMillis = windowSeconds * 1000;
    }

    public void checkAllowed(String ip, String email) {
        long retryAfter = Math.max(retryAfter(ipKey(ip), maxPerIp), retryAfter(emailKey(email), maxPerEmail));
        if (retryAfter > 0) {
            throw new TooManyRequestsException(
                    "Слишком много неудачных попыток входа. Повторите через " + retryAfter + " с", retryAfter);
        }
    }

    public void onFailure(String ip, String email) {
        long now = System.currentTimeMillis();
        if (windows.size() > PRUNE_THRESHOLD) {
            windows.values().removeIf(w -> now - w.startMillis() >= windowMillis);
        }
        bump(ipKey(ip), now);
        bump(emailKey(email), now);
    }

    public void onSuccess(String email) {
        windows.remove(emailKey(email));
    }

    private void bump(String key, long now) {
        windows.compute(key, (k, w) -> w == null || now - w.startMillis() >= windowMillis
                ? new Window(now, 1) : new Window(w.startMillis(), w.failures() + 1));
    }

    private long retryAfter(String key, int max) {
        Window w = windows.get(key);
        if (w == null || w.failures() < max) return 0;
        long left = w.startMillis() + windowMillis - System.currentTimeMillis();
        return left > 0 ? (left + 999) / 1000 : 0;
    }

    private static String ipKey(String ip) {
        return "ip:" + ip;
    }

    private static String emailKey(String email) {
        return "email:" + (email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
    }
}
