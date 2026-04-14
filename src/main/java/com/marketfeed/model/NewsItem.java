package com.marketfeed.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class NewsItem {
    private String title;
    private String url;
    private String source;
    private String summary;
    private Instant publishedAt;
    private String sentiment;   // Bullish / Bearish / Neutral (from Alpha Vantage)
    private String imageUrl;
}
