package com.marketfeed.service;

import com.marketfeed.model.Fundamentals;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class FundamentalsService {

    private final RestTemplate restTemplate;

    @Value("${market-feed.alpha-vantage.api-key:demo}")
    private String apiKey;

    @Value("${market-feed.alpha-vantage.base-url:https://www.alphavantage.co/query}")
    private String baseUrl;

    @Cacheable(value = "fundamentals", key = "#symbol.toUpperCase()", unless = "#result.error != null")
    public Fundamentals getFundamentals(String symbol) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .queryParam("function", "OVERVIEW")
                    .queryParam("symbol", symbol.toUpperCase())
                    .queryParam("apikey", apiKey)
                    .toUriString();

            ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.GET, null, Map.class);
            Map<?, ?> body = resp.getBody();

            if (body == null || body.isEmpty()) {
                return Fundamentals.builder().symbol(symbol).error("No data returned").build();
            }
            if (body.containsKey("Information") || body.containsKey("Note")) {
                Object msg = body.containsKey("Information") ? body.get("Information") : body.get("Note");
                log.warn("AV rate limit for {}: {}", symbol, msg);
                return Fundamentals.builder().symbol(symbol).error("API rate limit reached").build();
            }
            // AV returns empty object {} for unknown symbols
            if (!body.containsKey("Symbol")) {
                return Fundamentals.builder().symbol(symbol).error("No fundamentals available for this symbol").build();
            }

            return Fundamentals.builder()
                    .symbol(str(body, "Symbol"))
                    .name(str(body, "Name"))
                    .description(str(body, "Description"))
                    .sector(str(body, "Sector"))
                    .industry(str(body, "Industry"))
                    .exchange(str(body, "Exchange"))
                    .peRatio(dbl(body, "TrailingPE"))
                    .forwardPE(dbl(body, "ForwardPE"))
                    .pegRatio(dbl(body, "PEGRatio"))
                    .priceToBook(dbl(body, "PriceToBookRatio"))
                    .priceToSales(dbl(body, "PriceToSalesRatioTTM"))
                    .evToEbitda(dbl(body, "EVToEBITDA"))
                    .evToRevenue(dbl(body, "EVToRevenue"))
                    .marketCap(lng(body, "MarketCapitalization"))
                    .revenueTtm(lng(body, "RevenueTTM"))
                    .grossProfitTtm(lng(body, "GrossProfitTTM"))
                    .ebitda(lng(body, "EBITDA"))
                    .eps(dbl(body, "EPS"))
                    .dilutedEpsTtm(dbl(body, "DilutedEPSTTM"))
                    .profitMargin(dbl(body, "ProfitMargin"))
                    .operatingMargin(dbl(body, "OperatingMarginTTM"))
                    .returnOnEquity(dbl(body, "ReturnOnEquityTTM"))
                    .returnOnAssets(dbl(body, "ReturnOnAssetsTTM"))
                    .revenueGrowthYoy(dbl(body, "QuarterlyRevenueGrowthYOY"))
                    .earningsGrowthYoy(dbl(body, "QuarterlyEarningsGrowthYOY"))
                    .dividendPerShare(dbl(body, "DividendPerShare"))
                    .dividendYield(dbl(body, "DividendYield"))
                    .analystTargetPrice(dbl(body, "AnalystTargetPrice"))
                    .beta(dbl(body, "Beta"))
                    .ma50(dbl(body, "50DayMovingAverage"))
                    .ma200(dbl(body, "200DayMovingAverage"))
                    .build();

        } catch (Exception e) {
            log.error("Failed to fetch fundamentals for {}: {}", symbol, e.getMessage());
            return Fundamentals.builder().symbol(symbol).error(e.getMessage()).build();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() || s.equals("None") || s.equals("-") ? null : s;
    }

    private Double dbl(Map<?, ?> m, String key) {
        String s = str(m, key);
        if (s == null) return null;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }

    private Long lng(Map<?, ?> m, String key) {
        String s = str(m, key);
        if (s == null) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }
}
