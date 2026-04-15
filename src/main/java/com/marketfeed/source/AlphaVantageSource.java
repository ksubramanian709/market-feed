package com.marketfeed.source;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.marketfeed.model.OhlcvBar;
import com.marketfeed.model.Quote;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlphaVantageSource implements MarketDataSource {

    private final RestTemplate restTemplate;

    @Value("${market-feed.alpha-vantage.api-key}")
    private String apiKey;

    @Value("${market-feed.alpha-vantage.base-url}")
    private String baseUrl;

    // Simple rate-limit backoff: if we get a 429 or rate-limit message,
    // back off until this timestamp passes.
    private final AtomicLong backoffUntilMs = new AtomicLong(0);

    @Override
    public String getName() {
        return "alpha_vantage";
    }

    @Override
    public boolean isAvailable() {
        return System.currentTimeMillis() > backoffUntilMs.get();
    }

    @Override
    public Optional<Quote> getQuote(String symbol) {
        if (!isAvailable()) {
            log.debug("AlphaVantage in backoff, skipping for {}", symbol);
            return Optional.empty();
        }
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .queryParam("function", "GLOBAL_QUOTE")
                    .queryParam("symbol", symbol)
                    .queryParam("apikey", apiKey)
                    .toUriString();

            GlobalQuoteResponse response = restTemplate.getForObject(url, GlobalQuoteResponse.class);
            if (response == null || response.getGlobalQuote() == null
                    || response.getGlobalQuote().getSymbol() == null) {
                // Rate-limit message looks like {"Note": "..."}
                log.warn("AlphaVantage returned empty quote for {}; possible rate limit", symbol);
                triggerBackoff();
                return Optional.empty();
            }

            GlobalQuoteResponse.GlobalQuote gq = response.getGlobalQuote();
            Quote quote = Quote.builder()
                    .symbol(gq.getSymbol())
                    .name(gq.getSymbol())
                    .price(parseDouble(gq.getPrice()))
                    .open(parseDouble(gq.getOpen()))
                    .high(parseDouble(gq.getHigh()))
                    .low(parseDouble(gq.getLow()))
                    .change(parseDouble(gq.getChange()))
                    .changePercent(parsePercent(gq.getChangePercent()))
                    .volume(parseLong(gq.getVolume()))
                    .currency("USD")
                    .assetType(inferAssetType(symbol))
                    .timestamp(Instant.now())
                    .build();
            return Optional.of(quote);

        } catch (Exception e) {
            log.warn("AlphaVantage getQuote failed for {}: {}", symbol, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<OhlcvBar> getHistory(String symbol, LocalDate from, LocalDate to) {
        if (!isAvailable()) return Collections.emptyList();
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .queryParam("function", "TIME_SERIES_DAILY")
                    .queryParam("symbol", symbol)
                    .queryParam("outputsize", "compact")
                    .queryParam("apikey", apiKey)
                    .toUriString();

            DailyResponse response = restTemplate.getForObject(url, DailyResponse.class);
            if (response == null || response.getTimeSeries() == null) {
                triggerBackoff();
                return Collections.emptyList();
            }

            List<OhlcvBar> bars = new ArrayList<>();
            DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;
            response.getTimeSeries().forEach((dateStr, bar) -> {
                LocalDate date = LocalDate.parse(dateStr, fmt);
                if (!date.isBefore(from) && !date.isAfter(to)) {
                    bars.add(OhlcvBar.builder()
                            .date(date)
                            .open(parseDouble(bar.getOpen()))
                            .high(parseDouble(bar.getHigh()))
                            .low(parseDouble(bar.getLow()))
                            .close(parseDouble(bar.getClose()))
                            .volume(parseLong(bar.getVolume()))
                            .build());
                }
            });
            bars.sort(Comparator.comparing(OhlcvBar::getDate));
            return bars;

        } catch (Exception e) {
            log.warn("AlphaVantage getHistory failed for {}: {}", symbol, e.getMessage());
            return Collections.emptyList();
        }
    }

    private void triggerBackoff() {
        // Back off for 60 seconds on rate limit
        backoffUntilMs.set(System.currentTimeMillis() + 60_000);
        log.warn("AlphaVantage rate limited — backing off for 60s");
    }

    private double parseDouble(String s) {
        if (s == null || s.isBlank()) return 0.0;
        return Double.parseDouble(s.trim());
    }

    private long parseLong(String s) {
        if (s == null || s.isBlank()) return 0L;
        return Long.parseLong(s.trim());
    }

    private double parsePercent(String s) {
        if (s == null || s.isBlank()) return 0.0;
        return Double.parseDouble(s.replace("%", "").trim());
    }

    private Quote.AssetType inferAssetType(String symbol) {
        if (symbol.endsWith("=F")) return Quote.AssetType.FUTURE;
        if (symbol.contains("=X")) return Quote.AssetType.FOREX;
        if (symbol.startsWith("^")) return Quote.AssetType.INDEX;
        return Quote.AssetType.EQUITY;
    }

    // ---------- response POJOs ----------

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GlobalQuoteResponse {
        @JsonProperty("Global Quote")
        private GlobalQuote globalQuote;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class GlobalQuote {
            @JsonProperty("01. symbol")       private String symbol;
            @JsonProperty("02. open")         private String open;
            @JsonProperty("03. high")         private String high;
            @JsonProperty("04. low")          private String low;
            @JsonProperty("05. price")        private String price;
            @JsonProperty("06. volume")       private String volume;
            @JsonProperty("09. change")       private String change;
            @JsonProperty("10. change percent") private String changePercent;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DailyResponse {
        @JsonProperty("Time Series (Daily)")
        private Map<String, DailyBar> timeSeries;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class DailyBar {
            @JsonProperty("1. open")   private String open;
            @JsonProperty("2. high")   private String high;
            @JsonProperty("3. low")    private String low;
            @JsonProperty("4. close")  private String close;
            @JsonProperty("5. volume") private String volume;
        }
    }
}
