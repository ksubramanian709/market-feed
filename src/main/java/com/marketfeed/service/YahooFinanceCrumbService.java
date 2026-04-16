package com.marketfeed.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared Yahoo Finance crumb + cookie session.
 * Lazily fetches a crumb on first use and auto-refreshes on 401.
 */
@Slf4j
@Service
public class YahooFinanceCrumbService {

    private static final String CONSENT_URL = "https://fc.yahoo.com";
    private static final String CRUMB_URL   = "https://query2.finance.yahoo.com/v1/test/getcrumb";

    private final CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient     httpClient    = HttpClient.newBuilder()
            .cookieHandler(cookieManager)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final AtomicReference<String> crumb = new AtomicReference<>(null);

    public HttpClient getHttpClient() { return httpClient; }

    /** Returns the current crumb, fetching one if necessary. */
    public synchronized String getCrumb() {
        String existing = crumb.get();
        if (existing != null) return existing;
        try {
            httpClient.send(
                HttpRequest.newBuilder().uri(URI.create(CONSENT_URL))
                    .header("User-Agent", "Mozilla/5.0").GET().build(),
                HttpResponse.BodyHandlers.discarding()
            );
            HttpResponse<String> resp = httpClient.send(
                HttpRequest.newBuilder().uri(URI.create(CRUMB_URL))
                    .header("User-Agent", "Mozilla/5.0").GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            String c = resp.body().trim();
            if (c.isEmpty() || c.startsWith("{")) {
                log.warn("Failed to obtain YF crumb: {}", c);
                return null;
            }
            crumb.set(c);
            log.info("Obtained Yahoo Finance crumb");
            return c;
        } catch (Exception e) {
            log.error("Error fetching YF crumb: {}", e.getMessage());
            return null;
        }
    }

    /** Call this when a 401 is received to force a fresh crumb on next call. */
    public void invalidate() {
        crumb.set(null);
    }

    /** Build a standard GET request with User-Agent header. */
    public HttpRequest get(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                .header("Accept", "application/json")
                .GET()
                .build();
    }
}
