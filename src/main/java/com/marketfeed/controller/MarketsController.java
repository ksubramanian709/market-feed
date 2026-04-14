package com.marketfeed.controller;

import com.marketfeed.model.ApiResponse;
import com.marketfeed.model.Quote;
import com.marketfeed.service.QuoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/v1/markets")
@RequiredArgsConstructor
@Tag(name = "Markets", description = "Multi-market overview strip")
public class MarketsController {

    private final QuoteService quoteService;

    // Ordered for display: indices → rates → crypto → commodities
    private static final List<String> OVERVIEW_SYMBOLS = List.of(
        "^GSPC",    // S&P 500
        "^IXIC",    // Nasdaq
        "^DJI",     // Dow Jones
        "^RUT",     // Russell 2000
        "^VIX",     // VIX
        "^TNX",     // 10Y Treasury yield
        "BTC-USD",  // Bitcoin
        "ETH-USD",  // Ethereum
        "GC=F",     // Gold
        "CL=F"      // WTI Crude Oil
    );

    private static final Map<String, String> LABELS = Map.of(
        "^GSPC",   "S&P 500",
        "^IXIC",   "Nasdaq",
        "^DJI",    "Dow",
        "^RUT",    "Russell 2000",
        "^VIX",    "VIX",
        "^TNX",    "10Y Yield",
        "BTC-USD", "Bitcoin",
        "ETH-USD", "Ethereum",
        "GC=F",    "Gold",
        "CL=F",    "Oil (WTI)"
    );

    @GetMapping("/overview")
    @Operation(summary = "Multi-market overview strip",
               description = "Returns quotes for S&P 500, Nasdaq, Dow, Russell 2000, VIX, 10Y Yield, Bitcoin, Ethereum, Gold, and Oil. Cached 60s.")
    @Cacheable("markets")
    public ApiResponse<List<Map<String, Object>>> getOverview() {
        List<Map<String, Object>> results = new ArrayList<>();

        for (String symbol : OVERVIEW_SYMBOLS) {
            try {
                ApiResponse<Quote> r = quoteService.getQuote(symbol);
                if (r.getData() == null) continue;
                Quote q = r.getData();

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("symbol",        symbol);
                item.put("label",         LABELS.getOrDefault(symbol, symbol));
                item.put("price",         q.getPrice());
                item.put("change",        q.getChange());
                item.put("changePercent", q.getChangePercent());
                item.put("currency",      q.getCurrency());
                results.add(item);
            } catch (Exception e) {
                // Skip failed symbols — overview should never crash
            }
        }

        return ApiResponse.<List<Map<String, Object>>>builder()
                .data(results)
                .source("aggregated")
                .fetchedAt(Instant.now())
                .build();
    }
}
