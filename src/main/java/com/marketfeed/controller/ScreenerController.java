package com.marketfeed.controller;

import com.marketfeed.model.Fundamentals;
import com.marketfeed.service.ScreenerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/screener")
@RequiredArgsConstructor
@Tag(name = "Screener", description = "Screen all US-listed equities by sector, valuation, and dividends")
public class ScreenerController {

    private final ScreenerService screenerService;

    @GetMapping
    @Operation(summary = "Screen stocks")
    public ResponseEntity<List<Fundamentals>> screen(
        @RequestParam(required = false) String  sector,
        @RequestParam(required = false) Double  minPE,
        @RequestParam(required = false) Double  maxPE,
        @RequestParam(required = false) Double  minMarketCapB,
        @RequestParam(required = false) Double  maxMarketCapB,
        @RequestParam(required = false) Double  minDivYield,
        @RequestParam(required = false) Double  minEps,
        @RequestParam(required = false) Double  maxBeta,
        @RequestParam(defaultValue = "marketCap") String  sortBy,
        @RequestParam(defaultValue = "true")      boolean sortDesc
    ) {
        return ResponseEntity.ok(screenerService.screen(
            new ScreenerService.ScreenerParams(
                sector, minPE, maxPE, minMarketCapB, maxMarketCapB,
                minDivYield, minEps, maxBeta, sortBy, sortDesc
            )
        ));
    }

    @GetMapping("/sectors")
    @Operation(summary = "List all available sectors")
    public ResponseEntity<List<String>> sectors() {
        return ResponseEntity.ok(screenerService.getSectors());
    }
}
