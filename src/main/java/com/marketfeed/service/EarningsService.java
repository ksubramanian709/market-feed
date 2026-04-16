package com.marketfeed.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketfeed.model.EarningsCalendarItem;
import com.marketfeed.model.EarningsHistory;
import com.marketfeed.model.QuarterlyEarning;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.http.HttpResponse;
import java.time.*;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EarningsService {

    private final RestTemplate restTemplate;
    private final YahooFinanceCrumbService crumbService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${market-feed.alpha-vantage.api-key:demo}")
    private String apiKey;

    private static final String BASE = "https://www.alphavantage.co/query";

    // Yahoo Finance upcoming earnings screener — returns 250 companies per page
    private static final String YF_EARNINGS_URL =
        "https://query1.finance.yahoo.com/v1/finance/screener/predefined/saved" +
        "?formatted=false&lang=en-US&region=US&count=250&offset=%d&scrIds=upcoming_earnings&crumb=%s";

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

    // ─── Earnings calendar: YF first, Alpha Vantage fallback ─────────────────────

    @Cacheable(value = "earnings-calendar", key = "'upcoming'", unless = "#result == null || #result.isEmpty()")
    public List<EarningsCalendarItem> getEarningsCalendar() {
        // Try Yahoo Finance first — live data, no API key required
        List<EarningsCalendarItem> yfItems = fetchYFEarningsCalendar();
        if (!yfItems.isEmpty()) {
            log.info("Earnings calendar loaded from Yahoo Finance: {} companies", yfItems.size());
            return yfItems;
        }

        // Fall back to Alpha Vantage
        log.info("YF earnings calendar empty — falling back to Alpha Vantage");
        return fetchAlphaVantageCalendar();
    }

    // ─── Yahoo Finance upcoming_earnings screener ─────────────────────────────────

    private List<EarningsCalendarItem> fetchYFEarningsCalendar() {
        String crumb = crumbService.getCrumb();
        if (crumb == null) return List.of();

        List<EarningsCalendarItem> items = new ArrayList<>();
        LocalDate today  = LocalDate.now(ZoneId.of("America/New_York"));
        LocalDate cutoff = today.plusDays(30);

        try {
            // Two pages (0, 250) should cover ~500 companies — more than enough for 30 days
            for (int offset : new int[]{0, 250}) {
                String url = String.format(YF_EARNINGS_URL, offset, crumb);
                HttpResponse<String> resp = crumbService.getHttpClient()
                        .send(crumbService.get(url), HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() == 401) { crumbService.invalidate(); break; }
                if (resp.statusCode() != 200)  break;

                YfEarningsResp body = mapper.readValue(resp.body(), YfEarningsResp.class);
                List<YfEarningsQuote> quotes = Optional.ofNullable(body.getFinance())
                    .map(YfEarningsWrapper::getResult)
                    .filter(r -> !r.isEmpty())
                    .map(r -> r.get(0).getQuotes())
                    .orElse(List.of());

                for (YfEarningsQuote q : quotes) {
                    if (q.getSymbol() == null) continue;

                    // Prefer earningsTimestampStart; fall back to earningsTimestamp
                    Long ts = q.getEarningsTimestampStart() != null
                            ? q.getEarningsTimestampStart()
                            : q.getEarningsTimestamp();
                    if (ts == null) continue;

                    LocalDate reportDate = Instant.ofEpochSecond(ts)
                            .atZone(ZoneId.of("America/New_York")).toLocalDate();
                    if (reportDate.isBefore(today) || reportDate.isAfter(cutoff)) continue;

                    String name = q.getLongName() != null ? q.getLongName() : q.getShortName();
                    Double estimate = q.getEpsForward() != null ? q.getEpsForward()
                                    : q.getEpsCurrentYear();

                    items.add(EarningsCalendarItem.builder()
                            .symbol(q.getSymbol())
                            .name(name)
                            .reportDate(reportDate.toString())
                            .fiscalDateEnding(reportDate.toString())
                            .estimate(estimate)
                            .currency("USD")
                            .build());
                }

                // If the first page had fewer than 250, no need to fetch next
                if (quotes.size() < 250) break;
            }
        } catch (Exception e) {
            log.warn("YF earnings calendar fetch failed: {}", e.getMessage());
        }

        items.sort(Comparator.comparing(EarningsCalendarItem::getReportDate));
        return items;
    }

    // ─── Alpha Vantage fallback ───────────────────────────────────────────────────

    private List<EarningsCalendarItem> fetchAlphaVantageCalendar() {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(BASE)
                    .queryParam("function", "EARNINGS_CALENDAR")
                    .queryParam("horizon", "1month")
                    .queryParam("apikey", apiKey)
                    .toUriString();

            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, null, String.class);
            if (resp.getBody() == null || resp.getBody().isBlank()) return List.of();

            String[] lines = resp.getBody().trim().split("\n");
            if (lines.length < 2) return List.of();

            List<EarningsCalendarItem> items = new ArrayList<>();
            LocalDate today  = LocalDate.now();
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

                try {
                    LocalDate rd = LocalDate.parse(reportDate);
                    if (rd.isBefore(today) || rd.isAfter(cutoff)) continue;
                } catch (Exception ignored) { continue; }

                items.add(EarningsCalendarItem.builder()
                        .symbol(sym).name(name).reportDate(reportDate)
                        .fiscalDateEnding(fiscal).estimate(estimate).currency(currency)
                        .build());
            }

            items.sort(Comparator.comparing(EarningsCalendarItem::getReportDate));
            return items;

        } catch (Exception e) {
            log.error("Alpha Vantage earnings calendar failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private Double parseDouble(String s) {
        if (s == null || s.isBlank() || s.equals("None") || s.equals("-")) return null;
        try { return Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    // ─── YF DTOs ─────────────────────────────────────────────────────────────────

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfEarningsResp { private YfEarningsWrapper finance; }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfEarningsWrapper { private List<YfEarningsResult> result; }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfEarningsResult { private int total; private List<YfEarningsQuote> quotes; }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfEarningsQuote {
        private String  symbol;
        private String  shortName;
        private String  longName;
        private Long    earningsTimestamp;
        private Long    earningsTimestampStart;
        private Long    earningsTimestampEnd;
        private Double  epsForward;
        private Double  epsCurrentYear;
    }
}
