package com.marketfeed.controller;

import com.marketfeed.model.ApiResponse;
import com.marketfeed.model.NewsItem;
import com.marketfeed.service.NewsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/news")
@RequiredArgsConstructor
@Tag(name = "News", description = "Market news and company-specific headlines via Alpha Vantage")
public class NewsController {

    private final NewsService newsService;

    @GetMapping
    @Operation(summary = "General market news",
               description = "Latest financial markets and macro headlines. Cached 15 min.")
    public ResponseEntity<ApiResponse<List<NewsItem>>> getMarketNews() {
        ApiResponse<List<NewsItem>> response = newsService.getMarketNews();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{symbol}")
    @Operation(summary = "Company-specific news",
               description = "Latest news for a given ticker symbol. Cached 15 min.")
    public ResponseEntity<ApiResponse<List<NewsItem>>> getTickerNews(@PathVariable String symbol) {
        ApiResponse<List<NewsItem>> response = newsService.getTickerNews(symbol);
        return ResponseEntity.ok(response);
    }
}
