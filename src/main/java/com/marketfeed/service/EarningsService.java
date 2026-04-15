package com.marketfeed.service;

import com.marketfeed.model.EarningsCalendarItem;
import com.marketfeed.model.EarningsHistory;
import com.marketfeed.model.QuarterlyEarning;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EarningsService {

    private final RestTemplate restTemplate;

    @Value("${market-feed.alpha-vantage.api-key:demo}")
    private String apiKey;

    private static final String BASE = "https://www.alphavantage.co/query";

    // ─── Earnings history (per symbol) ───────────────────────────────────────────

    @Cacheable(value = "earnings", key = "#symbol.toUpperCase()", unless = "#result.error != null")
    public EarningsHistory getEarningsHistory(String symbol) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(BASE)
                    .queryParam("function", "EARNINGS")
                    .queryParam("symbol", symbol.toUpperCase())
                    .queryParam("apikey", apiKey)
                    .toUriString();

            ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.GET, null, Map.class);
            if (resp.getBody() == null) {
                return EarningsHistory.builder().symbol(symbol).error("No data returned").build();
            }

            Map<?, ?> body = resp.getBody();

            // Alpha Vantage error response
            if (body.containsKey("Information") || body.containsKey("Note")) {
                Object msgObj = body.containsKey("Information") ? body.get("Information") : body.get("Note");
                String msg = msgObj != null ? msgObj.toString() : "rate limit";
                log.warn("Alpha Vantage earnings limit for {}: {}", symbol, msg);
                return EarningsHistory.builder().symbol(symbol).error("API rate limit reached").build();
            }

            @SuppressWarnings("unchecked")
            List<Map<String, String>> raw = (List<Map<String, String>>) body.get("quarterlyEarnings");
            if (raw == null || raw.isEmpty()) {
                return EarningsHistory.builder().symbol(symbol).error("No earnings data available").build();
            }

            List<QuarterlyEarning> quarters = new ArrayList<>();
            for (Map<String, String> q : raw) {
                quarters.add(QuarterlyEarning.builder()
                        .fiscalDateEnding(q.get("fiscalDateEnding"))
                        .reportedDate(q.get("reportedDate"))
                        .reportedEps(parseDouble(q.get("reportedEPS")))
                        .estimatedEps(parseDouble(q.get("estimatedEPS")))
                        .surprise(parseDouble(q.get("surprise")))
                        .surprisePercentage(parseDouble(q.get("surprisePercentage")))
                        .build());
                if (quarters.size() >= 8) break;
            }

            return EarningsHistory.builder().symbol(symbol).quarterlyEarnings(quarters).build();

        } catch (Exception e) {
            log.error("Failed to fetch earnings history for {}: {}", symbol, e.getMessage());
            return EarningsHistory.builder().symbol(symbol).error(e.getMessage()).build();
        }
    }

    // ─── Earnings calendar (upcoming) ────────────────────────────────────────────

    @Cacheable(value = "earnings-calendar", key = "'upcoming'")
    public List<EarningsCalendarItem> getEarningsCalendar() {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(BASE)
                    .queryParam("function", "EARNINGS_CALENDAR")
                    .queryParam("horizon", "1month")
                    .queryParam("apikey", apiKey)
                    .toUriString();

            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, null, String.class);
            if (resp.getBody() == null || resp.getBody().isBlank()) return List.of();

            String csv = resp.getBody().trim();
            String[] lines = csv.split("\n");
            if (lines.length < 2) return List.of();

            // Skip header row, parse each line
            List<EarningsCalendarItem> items = new ArrayList<>();
            LocalDate today = LocalDate.now();
            LocalDate cutoff = today.plusDays(30);

            for (int i = 1; i < lines.length; i++) {
                String[] cols = lines[i].split(",");
                if (cols.length < 4) continue;

                String sym        = cols[0].trim();
                String name       = cols.length > 1 ? cols[1].trim() : "";
                String reportDate = cols.length > 2 ? cols[2].trim() : "";
                String fiscal     = cols.length > 3 ? cols[3].trim() : "";
                Double estimate   = cols.length > 4 ? parseDouble(cols[4].trim()) : null;
                String currency   = cols.length > 5 ? cols[5].trim() : "USD";

                // Only include future dates within 30 days
                try {
                    LocalDate rd = LocalDate.parse(reportDate);
                    if (rd.isBefore(today) || rd.isAfter(cutoff)) continue;
                } catch (Exception ignored) { continue; }

                items.add(EarningsCalendarItem.builder()
                        .symbol(sym)
                        .name(name)
                        .reportDate(reportDate)
                        .fiscalDateEnding(fiscal)
                        .estimate(estimate)
                        .currency(currency)
                        .build());
            }

            // Sort by date
            items.sort(Comparator.comparing(EarningsCalendarItem::getReportDate));
            return items;

        } catch (Exception e) {
            log.error("Failed to fetch earnings calendar: {}", e.getMessage());
            return List.of();
        }
    }

    private Double parseDouble(String s) {
        if (s == null || s.isBlank() || s.equals("None") || s.equals("-")) return null;
        try { return Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
