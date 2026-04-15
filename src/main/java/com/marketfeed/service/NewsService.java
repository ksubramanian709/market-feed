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
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.stream.*;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsService {

    private final RestTemplate restTemplate;

    @Value("${market-feed.alpha-vantage.api-key:}")
    private String alphaVantageKey;

    // ─── RSS source URLs ────────────────────────────────────────────────────────

    // Yahoo Finance RSS — company-specific (most reliable)
    private static final String YAHOO_RSS =
        "https://feeds.finance.yahoo.com/rss/2.0/headline?s=%s&region=US&lang=en-US";

    // Google News RSS — keyword search (broad coverage, any topic)
    private static final String GOOGLE_NEWS_RSS =
        "https://news.google.com/rss/search?q=%s+stock+market&hl=en-US&gl=US&ceid=US:en";

    // Seeking Alpha RSS — equity-focused editorial
    private static final String SEEKING_ALPHA_RSS =
        "https://seekingalpha.com/api/sa/combined/%s.xml";

    // MarketWatch headlines (general market news)
    private static final String MARKETWATCH_RSS =
        "https://feeds.marketwatch.com/marketwatch/realtimeheadlines/";

    // CNBC general markets
    private static final String CNBC_RSS =
        "https://www.cnbc.com/id/100003114/device/rss/rss.html";

    // Alpha Vantage news (used when API key is configured)
    private static final String AV_NEWS_URL = "https://www.alphavantage.co/query";

    // ─── Public API ─────────────────────────────────────────────────────────────

    /**
     * General market news from multiple RSS sources. Cached 15 min.
     */
    @Cacheable(value = "news", key = "'market_headlines'")
    public ApiResponse<List<NewsItem>> getMarketNews() {
        List<NewsItem> items = new ArrayList<>();

        // Pull from 3 general-market RSS feeds in parallel (sequential here, fast enough)
        items.addAll(fetchRss(CNBC_RSS,        "CNBC"));
        items.addAll(fetchRss(MARKETWATCH_RSS, "MarketWatch"));
        items.addAll(fetchRss(
            String.format(GOOGLE_NEWS_RSS, "financial+markets+economy"), "Google News"));

        // Supplement with Alpha Vantage if key configured
        if (alphaVantageKey != null && !alphaVantageKey.isBlank()) {
            items.addAll(fetchAlphaVantageNews(null, "financial_markets,economy_macro", 8));
        }

        List<NewsItem> deduplicated = deduplicate(items).stream()
                .sorted(Comparator.comparing(NewsItem::getPublishedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(15)
                .collect(Collectors.toList());

        return deduplicated.isEmpty()
            ? ApiResponse.error("No news available")
            : ApiResponse.success(deduplicated, "rss_aggregated");
    }

    /**
     * News for a specific ticker from multiple sources. Cached 15 min.
     */
    @Cacheable(value = "news", key = "#symbol.toUpperCase()")
    public ApiResponse<List<NewsItem>> getTickerNews(String symbol) {
        String sym = symbol.toUpperCase();
        List<NewsItem> items = new ArrayList<>();

        // Yahoo Finance RSS — most targeted for a ticker
        items.addAll(fetchRss(String.format(YAHOO_RSS, sym), "Yahoo Finance"));

        // Seeking Alpha RSS
        items.addAll(fetchRss(String.format(SEEKING_ALPHA_RSS, sym), "Seeking Alpha"));

        // Google News search — catches news from across the web
        items.addAll(fetchRss(
            String.format(GOOGLE_NEWS_RSS, sym), "Google News"));

        // Alpha Vantage if key configured
        if (alphaVantageKey != null && !alphaVantageKey.isBlank()) {
            items.addAll(fetchAlphaVantageNews(sym, null, 10));
        }

        List<NewsItem> deduplicated = deduplicate(items).stream()
                .sorted(Comparator.comparing(NewsItem::getPublishedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(15)
                .collect(Collectors.toList());

        return deduplicated.isEmpty()
            ? ApiResponse.error("No news found for " + sym)
            : ApiResponse.success(deduplicated, "rss_aggregated");
    }

    // ─── RSS fetching & parsing ──────────────────────────────────────────────────

    private List<NewsItem> fetchRss(String url, String sourceName) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT,
                "Mozilla/5.0 (compatible; MarketFeedBot/1.0)");
            headers.set(HttpHeaders.ACCEPT, "application/rss+xml, application/xml, text/xml");
            ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                return Collections.emptyList();
            }
            return parseRss(resp.getBody(), sourceName);
        } catch (Exception e) {
            log.debug("RSS fetch failed [{}]: {}", sourceName, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<NewsItem> parseRss(String xml, String sourceName) {
        List<NewsItem> items = new ArrayList<>();
        try {
            // Strip leading BOM / whitespace that breaks some parsers
            String cleaned = xml.trim().replaceFirst("^([\\W]+)<", "<");
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Document doc = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(cleaned.getBytes(StandardCharsets.UTF_8)));

            NodeList itemNodes = doc.getElementsByTagName("item");
            for (int i = 0; i < itemNodes.getLength() && i < 8; i++) {
                Element el     = (Element) itemNodes.item(i);
                String title   = text(el, "title");
                String link    = text(el, "link");
                String desc    = text(el, "description");
                String pubDate = text(el, "pubDate");
                String source  = text(el, "source");
                if (source == null || source.isBlank()) source = sourceName;

                if (title == null || title.isBlank()) continue;

                // Extract thumbnail image
                String imageUrl = extractImageUrl(el, desc, link);

                items.add(NewsItem.builder()
                    .title(cleanHtml(title))
                    .url(link)
                    .source(source)
                    .summary(cleanHtml(desc))
                    .publishedAt(parseDate(pubDate))
                    .sentiment("Neutral")
                    .imageUrl(imageUrl)
                    .build());
            }
        } catch (Exception e) {
            log.debug("RSS parse failed [{}]: {}", sourceName, e.getMessage());
        }
        return items;
    }

    private String text(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0) return null;
        return nl.item(0).getTextContent();
    }

    private String extractImageUrl(Element el, String desc, String articleUrl) {
        // 1. <media:content url="..."> — most RSS feeds with images
        for (String tag : List.of("media:content", "content", "media:thumbnail", "thumbnail")) {
            NodeList nl = el.getElementsByTagName(tag);
            if (nl.getLength() > 0) {
                String url = ((Element) nl.item(0)).getAttribute("url");
                if (url != null && !url.isBlank()) return url;
            }
        }
        // 2. <enclosure url="..."> — podcast-style enclosures
        NodeList enclosures = el.getElementsByTagName("enclosure");
        if (enclosures.getLength() > 0) {
            String url  = ((Element) enclosures.item(0)).getAttribute("url");
            String type = ((Element) enclosures.item(0)).getAttribute("type");
            if (url != null && !url.isBlank() && (type == null || type.startsWith("image"))) return url;
        }
        // 3. <img src="..."> embedded in the description HTML
        if (desc != null) {
            java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(desc);
            if (m.find()) {
                String url = m.group(1);
                if (!url.contains("pixel") && !url.contains("tracker") && !url.contains("beacon")) return url;
            }
        }
        // 4. Fallback: Google favicon for the publication domain
        if (articleUrl != null && !articleUrl.isBlank()) {
            try {
                java.net.URI uri = java.net.URI.create(articleUrl);
                String domain = uri.getHost();
                if (domain != null) {
                    domain = domain.replaceFirst("^www\\.", "");
                    return "https://www.google.com/s2/favicons?domain=" + domain + "&sz=128";
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private String cleanHtml(String s) {
        if (s == null) return null;
        return s.replaceAll("<[^>]+>", "").replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<").replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"").replaceAll("&#39;", "'")
                .replaceAll("&nbsp;", " ").trim();
    }

    private static final List<DateTimeFormatter> DATE_FMTS = List.of(
        DateTimeFormatter.RFC_1123_DATE_TIME,                            // RSS standard
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ENGLISH) // Alpha Vantage
    );

    private Instant parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        for (DateTimeFormatter fmt : DATE_FMTS) {
            try {
                return ZonedDateTime.parse(s.trim(), fmt).toInstant();
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ─── Alpha Vantage (supplementary) ──────────────────────────────────────────

    private List<NewsItem> fetchAlphaVantageNews(String ticker, String topics, int limit) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(AV_NEWS_URL)
                .queryParam("function", "NEWS_SENTIMENT")
                .queryParam("limit", limit)
                .queryParam("sort", "LATEST")
                .queryParam("apikey", alphaVantageKey);
            if (ticker != null) builder.queryParam("tickers", ticker);
            if (topics != null) builder.queryParam("topics", topics);

            AvNewsResponse response = restTemplate.getForObject(
                builder.toUriString(), AvNewsResponse.class);
            if (response == null || response.getFeed() == null
                    || response.getInformation() != null) return Collections.emptyList();

            return response.getFeed().stream().map(a -> NewsItem.builder()
                .title(a.getTitle())
                .url(a.getUrl())
                .source(a.getSource() != null ? a.getSource() : "Alpha Vantage")
                .summary(a.getSummary())
                .publishedAt(parseDate(a.getTimePublished()))
                .sentiment(a.getOverallSentimentLabel() != null
                    ? a.getOverallSentimentLabel() : "Neutral")
                .imageUrl(a.getBannerImage())
                .build()).collect(Collectors.toList());
        } catch (Exception e) {
            log.debug("Alpha Vantage news failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─── Deduplication ──────────────────────────────────────────────────────────

    /** Remove items with near-duplicate titles (first 60 chars match). */
    private List<NewsItem> deduplicate(List<NewsItem> items) {
        Set<String> seen = new LinkedHashSet<>();
        List<NewsItem> out = new ArrayList<>();
        for (NewsItem item : items) {
            if (item.getTitle() == null) continue;
            String key = item.getTitle().toLowerCase()
                .replaceAll("[^a-z0-9]", "")
                .substring(0, Math.min(60, item.getTitle().replaceAll("[^a-zA-Z0-9]", "").length()));
            if (seen.add(key)) out.add(item);
        }
        return out;
    }

    // ─── Alpha Vantage POJOs ────────────────────────────────────────────────────

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AvNewsResponse {
        private String information;
        @JsonProperty("feed")
        private List<Article> feed;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Article {
            private String title;
            private String url;
            private String summary;
            private String source;
            @JsonProperty("time_published")     private String timePublished;
            @JsonProperty("banner_image")       private String bannerImage;
            @JsonProperty("overall_sentiment_label") private String overallSentimentLabel;
        }
    }
}
