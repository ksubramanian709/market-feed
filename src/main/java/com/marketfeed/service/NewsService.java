package com.marketfeed.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.marketfeed.model.ApiResponse;
import com.marketfeed.model.NewsItem;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsService {

    private final RestTemplate restTemplate;

    @Value("${market-feed.alpha-vantage.api-key:demo}")
    private String alphaVantageKey;

    private static final String AV_NEWS_URL = "https://www.alphavantage.co/query";
    private static final DateTimeFormatter AV_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    /**
     * General market news (no ticker filter). Cached 15 minutes.
     */
    @Cacheable(value = "news", key = "'market_headlines'")
    public ApiResponse<List<NewsItem>> getMarketNews() {
        return fetchNews(null, "financial_markets,economy_macro", 12);
    }

    /**
     * News for a specific ticker. Cached 15 minutes.
     */
    @Cacheable(value = "news", key = "#symbol.toUpperCase()")
    public ApiResponse<List<NewsItem>> getTickerNews(String symbol) {
        return fetchNews(symbol.toUpperCase(), null, 10);
    }

    private ApiResponse<List<NewsItem>> fetchNews(String ticker, String topics, int limit) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder
                    .fromHttpUrl(AV_NEWS_URL)
                    .queryParam("function", "NEWS_SENTIMENT")
                    .queryParam("limit", limit)
                    .queryParam("sort", "LATEST")
                    .queryParam("apikey", alphaVantageKey);

            if (ticker != null && !ticker.isBlank()) {
                builder.queryParam("tickers", ticker);
            }
            if (topics != null && !topics.isBlank()) {
                builder.queryParam("topics", topics);
            }

            String url = builder.toUriString();
            AvNewsResponse response = restTemplate.getForObject(url, AvNewsResponse.class);

            if (response == null || response.getFeed() == null) {
                return ApiResponse.error("No news data available");
            }

            // Alpha Vantage returns an "Information" key when rate-limited
            if (response.getInformation() != null) {
                log.warn("Alpha Vantage news rate-limited: {}", response.getInformation());
                return ApiResponse.error("News rate-limited — upgrade Alpha Vantage key for full access");
            }

            List<NewsItem> items = response.getFeed().stream()
                    .map(this::toNewsItem)
                    .filter(n -> n.getTitle() != null && !n.getTitle().isBlank())
                    .collect(Collectors.toList());

            return ApiResponse.success(items, "alpha_vantage_news");

        } catch (Exception e) {
            log.warn("News fetch failed (ticker={}, topics={}): {}", ticker, topics, e.getMessage());
            return ApiResponse.error("Failed to fetch news: " + e.getMessage());
        }
    }

    private NewsItem toNewsItem(AvNewsResponse.Article a) {
        Instant publishedAt = null;
        try {
            if (a.getTimePublished() != null) {
                publishedAt = LocalDateTime.parse(a.getTimePublished(), AV_DATE_FMT)
                        .toInstant(ZoneOffset.UTC);
            }
        } catch (Exception ignored) {}

        // Extract overall sentiment label
        String sentiment = "Neutral";
        if (a.getOverallSentimentLabel() != null) {
            sentiment = a.getOverallSentimentLabel();
        }

        return NewsItem.builder()
                .title(a.getTitle())
                .url(a.getUrl())
                .source(a.getSource())
                .summary(a.getSummary())
                .publishedAt(publishedAt)
                .sentiment(sentiment)
                .imageUrl(a.getBannerImage())
                .build();
    }

    // ---- Alpha Vantage response POJOs ----

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AvNewsResponse {
        private String information;     // present when rate-limited

        @JsonProperty("feed")
        private List<Article> feed;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Article {
            private String title;
            private String url;
            private String summary;
            private String source;
            @JsonProperty("time_published")
            private String timePublished;
            @JsonProperty("banner_image")
            private String bannerImage;
            @JsonProperty("overall_sentiment_label")
            private String overallSentimentLabel;
        }
    }
}
