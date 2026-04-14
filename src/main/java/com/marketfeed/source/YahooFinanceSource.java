package com.marketfeed.source;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.marketfeed.model.OhlcvBar;
import com.marketfeed.model.Quote;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class YahooFinanceSource implements MarketDataSource {

    private final RestTemplate restTemplate;

    private static final String BASE_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/%s?interval=%s&range=%s";
    private static final String HISTORY_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/%s?interval=1d&period1=%d&period2=%d";

    @Override
    public String getName() { return "yahoo_finance"; }

    @Override
    public boolean isAvailable() { return true; }

    @Override
    public Optional<Quote> getQuote(String symbol) {
        try {
            String url = String.format(BASE_URL, symbol, "1d", "1d");
            ResponseEntity<ChartResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, buildRequest(), ChartResponse.class);

            if (response.getBody() == null) return Optional.empty();
            ChartResponse.Result result = extractResult(response.getBody());
            if (result == null) return Optional.empty();

            ChartResponse.Meta meta = result.getMeta();

            // Yahoo sometimes returns 0 for change fields — compute from previousClose
            double price = meta.getRegularMarketPrice();
            double prevClose = meta.getChartPreviousClose() > 0
                    ? meta.getChartPreviousClose()
                    : meta.getRegularMarketPreviousClose();
            double change = meta.getRegularMarketChange() != 0
                    ? meta.getRegularMarketChange()
                    : (prevClose > 0 ? price - prevClose : 0);
            double changePct = meta.getRegularMarketChangePercent() != 0
                    ? meta.getRegularMarketChangePercent()
                    : (prevClose > 0 ? (change / prevClose) * 100 : 0);

            Quote quote = Quote.builder()
                    .symbol(meta.getSymbol())
                    .name(meta.getLongName() != null ? meta.getLongName() : meta.getSymbol())
                    .price(price)
                    .change(change)
                    .changePercent(changePct)
                    .open(meta.getRegularMarketOpen())
                    .high(meta.getRegularMarketDayHigh())
                    .low(meta.getRegularMarketDayLow())
                    .volume(meta.getRegularMarketVolume())
                    .currency(meta.getCurrency() != null ? meta.getCurrency() : "USD")
                    .assetType(inferAssetType(meta.getInstrumentType(), symbol))
                    .timestamp(Instant.now())
                    .marketCap(meta.getMarketCap())
                    .fiftyTwoWeekHigh(meta.getFiftyTwoWeekHigh())
                    .fiftyTwoWeekLow(meta.getFiftyTwoWeekLow())
                    .build();
            return Optional.of(quote);

        } catch (Exception e) {
            log.warn("YahooFinance getQuote failed for {}: {}", symbol, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<OhlcvBar> getHistory(String symbol, LocalDate from, LocalDate to) {
        try {
            long period1 = from.atStartOfDay(ZoneId.of("America/New_York")).toEpochSecond();
            long period2 = to.plusDays(1).atStartOfDay(ZoneId.of("America/New_York")).toEpochSecond();
            String url = String.format(HISTORY_URL, symbol, period1, period2);
            return fetchBars(url, true);
        } catch (Exception e) {
            log.warn("YahooFinance getHistory failed for {}: {}", symbol, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Fetch chart data using Yahoo's interval/range API — used for frontend charting.
     * interval: 1m, 5m, 15m, 60m, 1d, 1wk, 1mo
     * range:    1d, 5d, 1mo, 3mo, 6mo, 1y, 5y
     */
    public List<OhlcvBar> getChart(String symbol, String interval, String range) {
        try {
            String url = String.format(BASE_URL, symbol, interval, range);
            boolean isDaily = interval.equals("1d") || interval.equals("1wk") || interval.equals("1mo");
            return fetchBars(url, isDaily);
        } catch (Exception e) {
            log.warn("YahooFinance getChart failed for {} ({}/{}): {}", symbol, interval, range, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<OhlcvBar> fetchBars(String url, boolean useDateOnly) {
        ResponseEntity<ChartResponse> response = restTemplate.exchange(
                url, HttpMethod.GET, buildRequest(), ChartResponse.class);

        if (response.getBody() == null) return Collections.emptyList();
        ChartResponse.Result result = extractResult(response.getBody());
        if (result == null || result.getTimestamps() == null) return Collections.emptyList();

        List<Long> timestamps = result.getTimestamps();
        ChartResponse.Indicators.Quote q = result.getIndicators().getQuote().get(0);
        List<OhlcvBar> bars = new ArrayList<>();

        for (int i = 0; i < timestamps.size(); i++) {
            if (q.getClose() == null || i >= q.getClose().size()) continue;
            if (q.getClose().get(i) == null) continue;

            long epochSec = timestamps.get(i);
            OhlcvBar.OhlcvBarBuilder bar = OhlcvBar.builder()
                    .timestampEpoch(epochSec)
                    .open(nullSafe(q.getOpen() != null && i < q.getOpen().size() ? q.getOpen().get(i) : null))
                    .high(nullSafe(q.getHigh() != null && i < q.getHigh().size() ? q.getHigh().get(i) : null))
                    .low(nullSafe(q.getLow() != null && i < q.getLow().size() ? q.getLow().get(i) : null))
                    .close(nullSafe(q.getClose().get(i)))
                    .volume(q.getVolume() != null && i < q.getVolume().size() && q.getVolume().get(i) != null
                            ? q.getVolume().get(i) : 0L);

            if (useDateOnly) {
                bar.date(Instant.ofEpochSecond(epochSec)
                        .atZone(ZoneId.of("America/New_York")).toLocalDate());
            }
            bars.add(bar.build());
        }
        return bars;
    }

    private HttpEntity<Void> buildRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT,
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36");
        headers.set(HttpHeaders.ACCEPT, "application/json");
        return new HttpEntity<>(headers);
    }

    private ChartResponse.Result extractResult(ChartResponse body) {
        if (body.getChart() == null) return null;
        if (body.getChart().getError() != null) return null;
        List<ChartResponse.Result> results = body.getChart().getResult();
        if (results == null || results.isEmpty()) return null;
        return results.get(0);
    }

    private double nullSafe(Double d) { return d != null ? d : 0.0; }

    private Quote.AssetType inferAssetType(String instrumentType, String symbol) {
        if (instrumentType != null) {
            return switch (instrumentType.toUpperCase()) {
                case "FUTURE"         -> Quote.AssetType.FUTURE;
                case "CURRENCY"       -> Quote.AssetType.FOREX;
                case "INDEX"          -> Quote.AssetType.INDEX;
                case "CRYPTOCURRENCY" -> Quote.AssetType.CRYPTO;
                default               -> Quote.AssetType.EQUITY;
            };
        }
        if (symbol.endsWith("=F")) return Quote.AssetType.FUTURE;
        if (symbol.endsWith("=X")) return Quote.AssetType.FOREX;
        if (symbol.startsWith("^")) return Quote.AssetType.INDEX;
        return Quote.AssetType.EQUITY;
    }

    // ---------- response POJOs ----------

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChartResponse {
        private Chart chart;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Chart {
            private List<Result> result;
            private Object error;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Result {
            private Meta meta;
            @JsonProperty("timestamp")
            private List<Long> timestamps;
            private Indicators indicators;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Meta {
            private String symbol;
            private String longName;
            private String currency;
            private String instrumentType;
            private double regularMarketPrice;
            private double regularMarketChange;
            private double regularMarketChangePercent;
            private double regularMarketPreviousClose;
            private double chartPreviousClose;          // more reliable for futures
            private double regularMarketOpen;
            private double regularMarketDayHigh;
            private double regularMarketDayLow;
            private long   regularMarketVolume;
            private long   marketCap;
            private double fiftyTwoWeekHigh;
            private double fiftyTwoWeekLow;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Indicators {
            private List<Quote> quote;

            @Data @JsonIgnoreProperties(ignoreUnknown = true)
            public static class Quote {
                private List<Double> open;
                private List<Double> high;
                private List<Double> low;
                private List<Double> close;
                private List<Long>   volume;
            }
        }
    }
}
