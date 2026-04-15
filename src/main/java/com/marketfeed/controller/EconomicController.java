package com.marketfeed.controller;

import com.marketfeed.model.ApiResponse;
import com.marketfeed.model.EconomicIndicator;
import com.marketfeed.service.FredService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/economic")
@RequiredArgsConstructor
@Tag(name = "Economic", description = "Macro indicators from FRED (Fed Funds, CPI, GDP, Treasury yields, etc.)")
public class EconomicController {

    private final FredService fredService;

    @GetMapping
    @Operation(summary = "All key macro indicators",
               description = "Returns latest values for: FEDFUNDS, DGS10, DGS2, CPIAUCSL, GDP, UNRATE, DTWEXBGS. Cached 1h.")
    public ResponseEntity<ApiResponse<Map<String, EconomicIndicator>>> getMacroSummary() {
        return ResponseEntity.ok(fredService.getMacroSummary());
    }

}
