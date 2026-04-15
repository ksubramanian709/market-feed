package com.marketfeed.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketfeed.model.*;
import com.marketfeed.model.BriefingResponse;
import com.marketfeed.model.TickerBriefing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final QuoteService quoteService;
    private final FredService fredService;
    private final NewsService newsService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${market-feed.anthropic.api-key:}")
    private String anthropicKey;

    @Value("${market-feed.anthropic.model:claude-sonnet-4-6}")
    private String model;

    @Value("${market-feed.brave.api-key:}")
    private String braveKey;

    private static final String ANTHROPIC_URL  = "https://api.anthropic.com/v1/messages";
    private static final String BRAVE_SEARCH_URL = "https://api.search.brave.com/res/v1/web/search";
    private static final int    MAX_ITERATIONS  = 10;

    // ─── Public entry point ──────────────────────────────────────────────────────

    public AgentResponse query(AgentRequest request) {
        if (anthropicKey == null || anthropicKey.isBlank()) {
            return AgentResponse.builder()
                    .error("ANTHROPIC_API_KEY not configured")
                    .generatedAt(Instant.now())
                    .build();
        }

        Set<String> symbolsUsed = new LinkedHashSet<>();
        try {
            String answer = runAgentLoop(request, symbolsUsed);
            return AgentResponse.builder()
                    .answer(answer)
                    .model(model)
                    .symbolsAnalyzed(new ArrayList<>(symbolsUsed))
                    .generatedAt(Instant.now())
                    .build();
        } catch (Exception e) {
            log.error("Agent loop failed: {}", e.getMessage(), e);
            return AgentResponse.builder()
                    .error("Failed to get answer: " + e.getMessage())
                    .generatedAt(Instant.now())
                    .build();
        }
    }

    // ─── Daily suggestions ───────────────────────────────────────────────────────

    @org.springframework.cache.annotation.Cacheable(value = "suggestions", key = "'daily'")
    public List<String> getDailySuggestions() {
        if (anthropicKey == null || anthropicKey.isBlank()) return List.of();
        try {
            // Pull today's market headlines
            ApiResponse<List<com.marketfeed.model.NewsItem>> newsResp = newsService.getMarketNews();
            String headlines = "";
            if (newsResp.getData() != null && !newsResp.getData().isEmpty()) {
                headlines = newsResp.getData().stream()
                        .limit(12)
                        .map(n -> "- " + n.getTitle())
                        .collect(java.util.stream.Collectors.joining("\n"));
            }

            String prompt = """
                    Today's market headlines:
                    %s

                    Based on these headlines, generate exactly 6 questions a retail investor would want to ask today.
                    Rules:
                    - Each question must reference a specific event, company, or trend from the headlines above
                    - Mix question types: "why is X moving?", "what does Y mean for markets?", "how should I think about Z?"
                    - Keep each question under 12 words
                    - No generic questions — every question must be specific to today's news
                    - Return ONLY a JSON array of 6 strings, no other text

                    Example format: ["Question 1?", "Question 2?", ...]
                    """.formatted(headlines.replace("%", "%%"));

            List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", prompt));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_tokens", 400);
            body.put("messages", messages);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", anthropicKey);
            headers.set("anthropic-version", "2023-06-01");

            ResponseEntity<Map> resp = restTemplate.exchange(
                    ANTHROPIC_URL, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

            if (resp.getBody() == null) return List.of();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
            if (content == null || content.isEmpty()) return List.of();

            String raw = (String) content.get(0).get("text");
            if (raw == null || raw.isBlank()) return List.of();

            // Extract the JSON array from the response
            int start = raw.indexOf('[');
            int end   = raw.lastIndexOf(']');
            if (start == -1 || end == -1) return List.of();

            @SuppressWarnings("unchecked")
            List<String> suggestions = objectMapper.readValue(raw.substring(start, end + 1), List.class);
            return suggestions != null ? suggestions : List.of();

        } catch (Exception e) {
            log.warn("Failed to generate daily suggestions: {}", e.getMessage());
            return List.of();
        }
    }

    // ─── Watchlist briefing ──────────────────────────────────────────────────────

    public BriefingResponse getBriefing(List<String> symbolsRaw) {
        if (anthropicKey == null || anthropicKey.isBlank()) {
            return BriefingResponse.builder()
                    .error("ANTHROPIC_API_KEY not configured")
                    .generatedAt(Instant.now())
                    .build();
        }

        List<String> symbols = symbolsRaw.stream()
                .map(String::toUpperCase)
                .distinct()
                .limit(20)
                .collect(Collectors.toList());

        // Assemble per-ticker context (quote + news)
        StringBuilder ctx = new StringBuilder();
        ctx.append("Today's date: ").append(LocalDate.now()).append("\n\n");

        Map<String, Quote> quoteMap = new LinkedHashMap<>();
        for (String sym : symbols) {
            ctx.append("[TICKER: ").append(sym).append("]\n");
            try {
                ApiResponse<Quote> qr = quoteService.getQuote(sym);
                if (qr.getData() != null) {
                    Quote q = qr.getData();
                    quoteMap.put(sym, q);
                    String sign = q.getChangePercent() >= 0 ? "+" : "";
                    ctx.append("Price: $").append(String.format("%.2f", q.getPrice()))
                            .append(" (").append(sign)
                            .append(String.format("%.2f", q.getChangePercent())).append("%)\n");
                    if (q.getName() != null) ctx.append("Name: ").append(q.getName()).append("\n");
                }
            } catch (Exception e) {
                log.warn("Briefing: quote fetch failed for {}: {}", sym, e.getMessage());
            }
            try {
                ApiResponse<List<NewsItem>> nr = newsService.getTickerNews(sym);
                if (nr.getData() != null && !nr.getData().isEmpty()) {
                    ctx.append("Recent news:\n");
                    nr.getData().stream().limit(4)
                            .forEach(n -> ctx.append("- ").append(n.getTitle()).append("\n"));
                }
            } catch (Exception e) {
                log.warn("Briefing: news fetch failed for {}: {}", sym, e.getMessage());
            }
            ctx.append("\n");
        }

        String prompt = """
                You are an expert financial analyst delivering a concise morning briefing.

                Here is today's market data for the user's watchlist:

                %s

                For each ticker, write what the investor needs to know TODAY — specific to the data above.

                Return ONLY a valid JSON array in this exact format, no other text:
                [
                  {
                    "symbol": "TICKER",
                    "sentiment": "bullish",
                    "summary": "One sentence key takeaway with specific data point",
                    "keyPoints": ["Point 1", "Point 2", "Point 3"]
                  }
                ]

                Rules:
                - sentiment must be exactly "bullish", "bearish", or "neutral"
                - summary ≤ 18 words, must reference a specific price, %, or headline
                - exactly 3 keyPoints, each ≤ 15 words, no generic filler
                - same order as tickers listed above
                """.formatted(ctx.toString().replace("%", "%%"));

        try {
            List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", prompt));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model",      model);
            body.put("max_tokens", 2000);
            body.put("messages",   messages);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key",         anthropicKey);
            headers.set("anthropic-version", "2023-06-01");

            ResponseEntity<Map> resp = restTemplate.exchange(
                    ANTHROPIC_URL, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

            if (resp.getBody() == null) throw new RuntimeException("Empty response from Claude");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
            if (content == null || content.isEmpty()) throw new RuntimeException("No content in response");

            String raw   = (String) content.get(0).get("text");
            int    start = raw.indexOf('[');
            int    end   = raw.lastIndexOf(']');
            if (start == -1 || end == -1) throw new RuntimeException("No JSON array in response");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = objectMapper.readValue(raw.substring(start, end + 1), List.class);

            List<TickerBriefing> briefings = items.stream().map(item -> {
                String sym = (String) item.get("symbol");
                Quote  q   = quoteMap.get(sym);
                @SuppressWarnings("unchecked")
                List<String> kp = (List<String>) item.getOrDefault("keyPoints", List.of());
                return TickerBriefing.builder()
                        .symbol(sym)
                        .name(q != null ? q.getName() : null)
                        .price(q != null ? q.getPrice() : 0)
                        .changePercent(q != null ? q.getChangePercent() : 0)
                        .sentiment((String) item.getOrDefault("sentiment", "neutral"))
                        .summary((String) item.getOrDefault("summary", ""))
                        .keyPoints(kp)
                        .build();
            }).collect(Collectors.toList());

            return BriefingResponse.builder()
                    .briefings(briefings)
                    .generatedAt(Instant.now())
                    .build();

        } catch (Exception e) {
            log.error("Briefing generation failed: {}", e.getMessage(), e);
            return BriefingResponse.builder()
                    .error("Failed to generate briefing: " + e.getMessage())
                    .generatedAt(Instant.now())
                    .build();
        }
    }

    // ─── Agentic loop ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String runAgentLoop(AgentRequest request, Set<String> symbolsUsed) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();

        // Prior conversation turns
        if (request.getHistory() != null) {
            for (AgentRequest.Turn turn : request.getHistory()) {
                messages.add(Map.of("role", turn.getRole(), "content", turn.getContent()));
            }
        }

        // Pin caller-supplied symbols into the question context so Claude is aware of them
        String question = request.getQuestion();
        if (request.getSymbols() != null && !request.getSymbols().isEmpty()) {
            question += "\n\n[Focus on these symbols if relevant: "
                    + String.join(", ", request.getSymbols()) + "]";
        }
        messages.add(Map.of("role", "user", "content", question));

        List<Map<String, Object>> tools = buildTools();

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            Map<String, Object> response = callClaude(messages, tools);
            String stopReason = (String) response.get("stop_reason");
            List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");

            // Record assistant turn (required by Anthropic for multi-turn tool use)
            messages.add(Map.of("role", "assistant", "content", content));

            if ("end_turn".equals(stopReason)) {
                return content.stream()
                        .filter(b -> "text".equals(b.get("type")))
                        .map(b -> (String) b.get("text"))
                        .collect(Collectors.joining("\n"));
            }

            if ("tool_use".equals(stopReason)) {
                List<Map<String, Object>> toolResults = new ArrayList<>();
                for (Map<String, Object> block : content) {
                    if (!"tool_use".equals(block.get("type"))) continue;
                    String toolName  = (String) block.get("name");
                    String toolUseId = (String) block.get("id");
                    Map<String, Object> input = (Map<String, Object>) block.get("input");

                    log.debug("Tool call: {} {}", toolName, input);
                    String result = executeTool(toolName, input, symbolsUsed);

                    toolResults.add(Map.of(
                            "type",        "tool_result",
                            "tool_use_id", toolUseId,
                            "content",     result
                    ));
                }
                messages.add(Map.of("role", "user", "content", toolResults));
            }
        }

        throw new RuntimeException("Agent loop exceeded " + MAX_ITERATIONS + " iterations without finishing");
    }

    // ─── Tool execution ──────────────────────────────────────────────────────────

    private String executeTool(String name, Map<String, Object> input, Set<String> symbolsUsed) {
        try {
            return switch (name) {
                case "get_quote" -> {
                    String symbol = ((String) input.get("symbol")).toUpperCase();
                    symbolsUsed.add(symbol);
                    ApiResponse<Quote> r = quoteService.getQuote(symbol);
                    yield r.getData() != null
                            ? objectMapper.writeValueAsString(r.getData())
                            : "No quote available for " + symbol;
                }
                case "get_price_history" -> {
                    String symbol = ((String) input.get("symbol")).toUpperCase();
                    int days = input.get("days") instanceof Number n ? n.intValue() : 14;
                    days = Math.min(days, 90);
                    symbolsUsed.add(symbol);
                    LocalDate to   = LocalDate.now();
                    LocalDate from = to.minusDays(days);
                    ApiResponse<List<OhlcvBar>> r = quoteService.getHistory(symbol, from, to);
                    yield r.getData() != null && !r.getData().isEmpty()
                            ? objectMapper.writeValueAsString(r.getData())
                            : "No price history available for " + symbol;
                }
                case "get_news" -> {
                    Object tickerObj = input.get("ticker");
                    String ticker = tickerObj instanceof String s && !s.isBlank() ? s.toUpperCase() : null;
                    ApiResponse<List<NewsItem>> r = ticker != null
                            ? newsService.getTickerNews(ticker)
                            : newsService.getMarketNews();
                    yield r.getData() != null && !r.getData().isEmpty()
                            ? objectMapper.writeValueAsString(r.getData())
                            : "No news available" + (ticker != null ? " for " + ticker : "");
                }
                case "get_economic_indicators" -> {
                    ApiResponse<Map<String, EconomicIndicator>> r = fredService.getMacroSummary();
                    yield r.getData() != null
                            ? objectMapper.writeValueAsString(r.getData())
                            : "Economic data unavailable (FRED_API_KEY may not be configured)";
                }
                case "web_search" -> {
                    String query = (String) input.get("query");
                    yield performWebSearch(query);
                }
                default -> "Unknown tool: " + name;
            };
        } catch (Exception e) {
            log.warn("Tool '{}' failed: {}", name, e.getMessage());
            return "Tool error: " + e.getMessage();
        }
    }

    // ─── Web search (Brave) ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String performWebSearch(String query) {
        if (braveKey == null || braveKey.isBlank()) {
            return "Web search is not available (BRAVE_API_KEY not configured). "
                    + "Use the other tools to answer from live market data.";
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Subscription-Token", braveKey);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            String url = UriComponentsBuilder.fromHttpUrl(BRAVE_SEARCH_URL)
                    .queryParam("q", query)
                    .queryParam("count", 6)
                    .queryParam("freshness", "pd")   // prefer results from the past day
                    .toUriString();

            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

            if (resp.getBody() == null) return "No search results returned.";

            Map<String, Object> web = (Map<String, Object>) resp.getBody().get("web");
            if (web == null) return "No web results found for: " + query;

            List<Map<String, Object>> results = (List<Map<String, Object>>) web.get("results");
            if (results == null || results.isEmpty()) return "No results found for: " + query;

            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> r : results) {
                sb.append("Title: ").append(r.get("title")).append("\n");
                sb.append("URL: ").append(r.get("url")).append("\n");
                Object desc = r.get("description");
                if (desc != null) sb.append("Summary: ").append(desc).append("\n");
                sb.append("\n");
            }
            return sb.toString().trim();

        } catch (Exception e) {
            log.warn("Web search failed for '{}': {}", query, e.getMessage());
            return "Web search failed: " + e.getMessage();
        }
    }

    // ─── Tool definitions ────────────────────────────────────────────────────────

    private List<Map<String, Object>> buildTools() {
        List<Map<String, Object>> tools = new ArrayList<>();

        tools.add(tool(
            "get_quote",
            "Get the current live price quote for any stock, ETF, index, commodity, or cryptocurrency. " +
            "Returns price, change, % change, open, high, low, volume, market cap, 52-week range. " +
            "Symbol examples: AAPL, MSFT, NVDA, ^GSPC (S&P 500), ^IXIC (Nasdaq), ^DJI (Dow), " +
            "^VIX, ^TNX (10Y Treasury), GC=F (Gold), CL=F (WTI Crude), BZ=F (Brent), " +
            "NG=F (Natural Gas), ZC=F (Corn), ZW=F (Wheat), ZS=F (Soybeans), " +
            "BTC-USD, ETH-USD, DX-Y.NYB (Dollar Index).",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "symbol", Map.of(
                        "type", "string",
                        "description", "Ticker symbol to fetch a quote for"
                    )
                ),
                "required", List.of("symbol")
            )
        ));

        tools.add(tool(
            "get_price_history",
            "Get daily OHLCV (open/high/low/close/volume) price bars for any symbol over the past N calendar days. " +
            "Use this to analyze trends, momentum, support/resistance, or to compare recent performance.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "symbol", Map.of("type", "string", "description", "Ticker symbol"),
                    "days",   Map.of("type", "integer", "description", "Number of calendar days of history to fetch (default 14, max 90)")
                ),
                "required", List.of("symbol")
            )
        ));

        tools.add(tool(
            "get_news",
            "Fetch recent financial news headlines. Pass a ticker symbol for company-specific news " +
            "(sourced from Yahoo Finance, Seeking Alpha, Google News, Alpha Vantage). " +
            "Omit ticker for broad market/economy news (sourced from CNBC, MarketWatch, Google News). " +
            "Use this for earnings, announcements, sector events, or general market sentiment.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "ticker", Map.of(
                        "type", "string",
                        "description", "Optional ticker symbol for company-specific news. Leave empty for general market news."
                    )
                ),
                "required", List.of()
            )
        ));

        tools.add(tool(
            "get_economic_indicators",
            "Get the latest US macroeconomic data from FRED (Federal Reserve Bank of St. Louis): " +
            "Federal Funds Rate, 10-Year Treasury Yield, 2-Year Treasury Yield, CPI, GDP, " +
            "Unemployment Rate, US Dollar Index. Use for macro analysis and rate/inflation context.",
            Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of()
            )
        ));

        if (braveKey != null && !braveKey.isBlank()) {
            tools.add(tool(
                "web_search",
                "Search the live web for any information not covered by the other tools. " +
                "Use for: breaking news and current events, company earnings/announcements, " +
                "analyst ratings/price targets, regulatory filings, IPOs, M&A activity, " +
                "macroeconomic reports (CPI, jobs, Fed decisions), investment theses, " +
                "sector research, or any general question. Results are from the past 24 hours when available.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "query", Map.of("type", "string", "description", "Search query")
                    ),
                    "required", List.of("query")
                )
            ));
        }

        return tools;
    }

    private Map<String, Object> tool(String name, String description, Map<String, Object> inputSchema) {
        return Map.of(
            "name",         name,
            "description",  description,
            "input_schema", inputSchema
        );
    }

    // ─── Claude API call ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> callClaude(List<Map<String, Object>> messages,
                                            List<Map<String, Object>> tools) throws Exception {
        boolean hasWebSearch = braveKey != null && !braveKey.isBlank();

        String systemPrompt = """
                You are an expert financial market analyst, investment advisor, and general assistant with access to live tools.

                You have these tools available:
                - get_quote: live price for any stock, ETF, index, commodity, crypto
                - get_price_history: daily OHLCV bars for trend analysis
                - get_news: recent headlines (company-specific or general market)
                - get_economic_indicators: US macro data (Fed rate, CPI, GDP, unemployment, yields)
                %s

                Guidelines:
                - For price questions, always use get_quote to get current data.
                - For news or recent events, use get_news%s.
                - For macro/Fed/inflation questions, use get_economic_indicators.
                - For trend or technical questions, use get_price_history.
                - You can call multiple tools in one turn when a question spans several assets or topics.
                - For general investment ideas, strategy discussion, or educational questions you can answer from your training knowledge without fetching data — but still use tools if live data would improve the answer.
                - After fetching data, give a clear, insightful, direct answer. Use real numbers from the data. Use bullet points for lists. Be concise.
                - Never refuse to discuss investment ideas, market opinions, or financial concepts. You are a knowledgeable analyst, not a compliance officer.

                Today's date: %s
                """.formatted(
                hasWebSearch ? "- web_search: search the live internet for anything else" : "",
                hasWebSearch ? " or web_search" : "",
                LocalDate.now()
        );

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model",      model);
        body.put("max_tokens", 4096);
        body.put("system",     systemPrompt);
        body.put("tools",      tools);
        body.put("messages",   messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key",           anthropicKey);
        headers.set("anthropic-version",   "2023-06-01");

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.exchange(
                ANTHROPIC_URL, HttpMethod.POST, entity, Map.class);

        if (response.getBody() == null) {
            throw new RuntimeException("Empty response from Claude API");
        }
        return response.getBody();
    }
}
