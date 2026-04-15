package com.marketfeed.controller;

import com.marketfeed.source.AlphaVantageSource;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1/fundamentals")
@RequiredArgsConstructor
public class FundamentalsController {

    private final AlphaVantageSource alphaVantageSource;

    /**
     * Returns key fundamentals for a ticker: market cap, 52-week range, P/E, EPS, beta.
     * Backed by Alpha Vantage OVERVIEW. Cached for 1 hour.
     * Returns zero-value fields on failure (client should treat 0 as unavailable).
     */
    @GetMapping("/{symbol}")
    @Cacheable(value = "fundamentals", key = "#symbol.toUpperCase()")
    public ResponseEntity<Map<String, Object>> getFundamentals(@PathVariable String symbol) {
        AlphaVantageSource.FundamentalsData fd = alphaVantageSource.getFundamentals(symbol);
        if (fd == null) {
            return ResponseEntity.ok(Map.of(
                "marketCap", 0L,
                "fiftyTwoWeekHigh", 0.0,
                "fiftyTwoWeekLow", 0.0,
                "peRatio", 0.0,
                "dividendYield", 0.0,
                "eps", 0.0,
                "beta", 0.0
            ));
        }
        return ResponseEntity.ok(Map.of(
            "marketCap",        fd.getMarketCap(),
            "fiftyTwoWeekHigh", fd.getFiftyTwoWeekHigh(),
            "fiftyTwoWeekLow",  fd.getFiftyTwoWeekLow(),
            "peRatio",          fd.getPeRatio(),
            "dividendYield",    fd.getDividendYield(),
            "eps",              fd.getEps(),
            "beta",             fd.getBeta()
        ));
    }
}
