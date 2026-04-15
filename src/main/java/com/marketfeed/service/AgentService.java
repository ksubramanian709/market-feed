package com.marketfeed.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketfeed.model.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final QuoteService quoteService;
    private final FredService fredService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${market-feed.anthropic.api-key:}")
    private String anthropicKey;

    @Value("${market-feed.anthropic.model:claude-sonnet-4-6}")
    private String model;

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";

    // Always-included baseline symbols: major indices, volatility, rates, safe havens
    private static final List<String> BASELINE_SYMBOLS =
            List.of("^GSPC", "^IXIC", "^DJI", "^VIX", "^TNX", "GC=F", "CL=F", "DX-Y.NYB");

    // Commodity / index keyword → symbol
    private static final Map<String, String> KEYWORD_SYMBOLS = new LinkedHashMap<>();
    static {
        KEYWORD_SYMBOLS.put("corn",         "ZC=F");
        KEYWORD_SYMBOLS.put("wheat",        "ZW=F");
        KEYWORD_SYMBOLS.put("soybean",      "ZS=F");
        KEYWORD_SYMBOLS.put("soybeans",     "ZS=F");
        KEYWORD_SYMBOLS.put("crude",        "CL=F");
        KEYWORD_SYMBOLS.put("wti",          "CL=F");
        KEYWORD_SYMBOLS.put("brent",        "BZ=F");
        KEYWORD_SYMBOLS.put("natural gas",  "NG=F");
        KEYWORD_SYMBOLS.put("natgas",       "NG=F");
        KEYWORD_SYMBOLS.put("gold",         "GC=F");
        KEYWORD_SYMBOLS.put("silver",       "SI=F");
        KEYWORD_SYMBOLS.put("copper",       "HG=F");
        KEYWORD_SYMBOLS.put("bitcoin",      "BTC-USD");
        KEYWORD_SYMBOLS.put("btc",          "BTC-USD");
        KEYWORD_SYMBOLS.put("ethereum",     "ETH-USD");
        KEYWORD_SYMBOLS.put("eth",          "ETH-USD");
        KEYWORD_SYMBOLS.put("vix",          "^VIX");
        KEYWORD_SYMBOLS.put("volatility",   "^VIX");
        KEYWORD_SYMBOLS.put("s&p",          "^GSPC");
        KEYWORD_SYMBOLS.put("s&p 500",      "^GSPC");
        KEYWORD_SYMBOLS.put("sp500",        "^GSPC");
        KEYWORD_SYMBOLS.put("nasdaq",       "^IXIC");
        KEYWORD_SYMBOLS.put("dow",          "^DJI");
        KEYWORD_SYMBOLS.put("russell",      "^RUT");
        KEYWORD_SYMBOLS.put("dollar",       "DX-Y.NYB");
        KEYWORD_SYMBOLS.put("dxy",          "DX-Y.NYB");
        KEYWORD_SYMBOLS.put("treasury",     "^TNX");
        KEYWORD_SYMBOLS.put("10-year",      "^TNX");
        KEYWORD_SYMBOLS.put("10yr",         "^TNX");
        KEYWORD_SYMBOLS.put("bonds",        "^TNX");
    }

    // Matches bare ticker symbols: 1–5 uppercase letters, optionally followed by =F or -USD
    private static final Pattern TICKER_RE =
            Pattern.compile("\\b([A-Z]{1,5}(?:[=-][A-Z]+)?)\\b");

    // Common English words to exclude from ticker detection
    private static final Set<String> STOPWORDS = Set.of(
            "A", "I", "AM", "AN", "AS", "AT", "BE", "BY", "DO", "GO", "HE", "IF",
            "IN", "IS", "IT", "ME", "MY", "NO", "OF", "ON", "OR", "SO", "TO", "UP",
            "US", "WE", "AI", "OK", "THE", "AND", "BUT", "FOR", "NOT", "ARE", "WAS",
            "HAS", "HAD", "CAN", "MAY", "DID", "HOW", "WHO", "WHY", "ALL", "ANY",
            "ITS", "NEW", "NOW", "OLD", "OWN", "SAY", "SEE", "GET", "GOT", "PUT",
            "SET", "LET", "OUT", "OFF", "YET", "ALSO", "JUST", "LIKE", "BEEN",
            "FROM", "HAVE", "THEY", "WILL", "WITH", "THIS", "THAT", "THAN", "THEN",
            "WHEN", "WHAT", "OVER", "RATE", "MARKET", "STOCK", "PRICE", "TRADE",
            "NEWS", "DATA", "HIGH", "MOVE", "BULL", "BEAR", "SELL", "HOLD", "BUY",
            "LONG", "NEXT", "LAST", "WEEK", "YEAR", "DAY", "TODAY", "USD", "ETF",
            "IPO", "GDP", "CPI", "FED", "FOMC", "SEC", "PE", "EPS", "CEO", "CFO"
    );

    public AgentResponse query(AgentRequest request) {
        if (anthropicKey == null || anthropicKey.isBlank()) {
            return AgentResponse.builder()
                    .error("ANTHROPIC_API_KEY not configured")
                    .generatedAt(Instant.now())
                    .build();
        }

        String question = request.getQuestion();
        List<String> symbols = resolveSymbols(request);
        Map<String, Object> context = buildMarketContext(symbols);

        try {
            String answer = callClaude(question, context, request.getHistory());
            return AgentResponse.builder()
                    .answer(answer)
                    .model(model)
                    .symbolsAnalyzed(symbols)
                    .marketContext(context)
                    .generatedAt(Instant.now())
                    .build();
        } catch (Exception e) {
            log.error("Claude API call failed: {}", e.getMessage());
            return AgentResponse.builder()
                    .error("Failed to get answer: " + e.getMessage())
                    .marketContext(context)
                    .symbolsAnalyzed(symbols)
                    .generatedAt(Instant.now())
                    .build();
        }
    }

    // ---------- symbol resolution ----------

    private List<String> resolveSymbols(AgentRequest request) {
        Set<String> symbols = new LinkedHashSet<>();

        // 1. Caller-pinned symbols
        if (request.getSymbols() != null) {
            request.getSymbols().stream().map(String::toUpperCase).forEach(symbols::add);
        }

        // 2. Keyword map (commodities, indices, crypto)
        String q = request.getQuestion().toLowerCase();
        KEYWORD_SYMBOLS.forEach((kw, sym) -> {
            if (q.contains(kw)) symbols.add(sym);
        });

        // 3. Bare ticker detection (e.g. "AAPL", "NVDA", "PLTR")
        Matcher m = TICKER_RE.matcher(request.getQuestion());
        while (m.find()) {
            String candidate = m.group(1);
            if (!STOPWORDS.contains(candidate)) symbols.add(candidate);
        }

        // 4. Always include baseline
        symbols.addAll(BASELINE_SYMBOLS);

        return new ArrayList<>(symbols);
    }

    // ---------- context building ----------

    private Map<String, Object> buildMarketContext(List<String> symbols) {
        Map<String, Object> context = new LinkedHashMap<>();

        // Live quotes
        Map<String, Object> quotes = new LinkedHashMap<>();
        for (String symbol : symbols) {
            ApiResponse<Quote> r = quoteService.getQuote(symbol);
            if (r.getData() != null) {
                Quote q = r.getData();
                quotes.put(symbol, Map.of(
                        "name",          q.getName(),
                        "price",         q.getPrice(),
                        "change",        q.getChange(),
                        "changePct",     q.getChangePercent(),
                        "high",          q.getHigh(),
                        "low",           q.getLow(),
                        "open",          q.getOpen(),
                        "volume",        q.getVolume(),
                        "currency",      q.getCurrency()
                ));
            }
        }
        context.put("quotes", quotes);

        // 10-day price history for specifically-mentioned symbols (not just baseline)
        List<String> specificSymbols = symbols.stream()
                .filter(s -> !BASELINE_SYMBOLS.contains(s))
                .collect(Collectors.toList());
        if (!specificSymbols.isEmpty()) {
            Map<String, Object> histories = new LinkedHashMap<>();
            LocalDate to = LocalDate.now();
            LocalDate from = to.minusDays(14);
            for (String symbol : specificSymbols) {
                ApiResponse<List<OhlcvBar>> r = quoteService.getHistory(symbol, from, to);
                if (r.getData() != null && !r.getData().isEmpty()) {
                    List<Map<String, Object>> bars = r.getData().stream()
                            .map(b -> Map.<String, Object>of(
                                    "date",   b.getDate() != null ? b.getDate().toString() : "",
                                    "open",   b.getOpen(),
                                    "high",   b.getHigh(),
                                    "low",    b.getLow(),
                                    "close",  b.getClose(),
                                    "volume", b.getVolume()
                            ))
                            .collect(Collectors.toList());
                    histories.put(symbol, bars);
                }
            }
            if (!histories.isEmpty()) context.put("priceHistory", histories);
        }

        // Macro from FRED
        ApiResponse<Map<String, EconomicIndicator>> macro = fredService.getMacroSummary();
        if (macro.getData() != null && !macro.getData().isEmpty()) {
            Map<String, Object> macroData = new LinkedHashMap<>();
            macro.getData().forEach((id, ind) ->
                    macroData.put(ind.getName(), Map.of(
                            "value", ind.getValue(),
                            "unit",  ind.getUnit(),
                            "date",  ind.getDate().toString()
                    ))
            );
            context.put("macroIndicators", macroData);
        }

        context.put("dataAsOf", Instant.now().toString());
        return context;
    }

    // ---------- Claude API call ----------

    private String callClaude(String question, Map<String, Object> context,
                               List<AgentRequest.Turn> history) throws Exception {
        String contextJson = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(context);

        String systemPrompt = """
                You are an expert financial market analyst and educator with deep knowledge of:
                - Equities, ETFs, options, and corporate fundamentals
                - Fixed income: Treasuries, credit, yield curves
                - Commodities: energy, metals, agriculture
                - Forex and macro FX dynamics
                - Cryptocurrencies
                - Macroeconomics: inflation, monetary policy, Fed, GDP, employment
                - Technical analysis and market structure
                - Trading strategies and risk management

                You have access to live market data pulled at the time of this request.
                Use BOTH your training knowledge AND the live data to give accurate, insightful answers.
                When referencing specific prices or changes, use the data provided.
                For conceptual or educational questions, draw on your full knowledge.
                Be concise and clear. Use bullet points for lists. Use numbers when available.
                Today's date: %s
                """.formatted(LocalDate.now());

        String userContent = """
                Live market data as of %s:
                %s

                Question: %s
                """.formatted(Instant.now(), contextJson, question);

        // Build messages array: history turns + current question
        List<Map<String, String>> messages = new ArrayList<>();
        if (history != null) {
            for (AgentRequest.Turn turn : history) {
                messages.add(Map.of("role", turn.getRole(), "content", turn.getContent()));
            }
        }
        messages.add(Map.of("role", "user", "content", userContent));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", 2048);
        body.put("system", systemPrompt);
        body.put("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", anthropicKey);
        headers.set("anthropic-version", "2023-06-01");

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<ClaudeResponse> response = restTemplate.exchange(
                ANTHROPIC_URL, HttpMethod.POST, entity, ClaudeResponse.class);

        if (response.getBody() == null || response.getBody().getContent() == null
                || response.getBody().getContent().isEmpty()) {
            throw new RuntimeException("Empty response from Claude");
        }
        return response.getBody().getContent().get(0).getText();
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClaudeResponse {
        private List<ContentBlock> content;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        public static class ContentBlock {
            private String type;
            private String text;
        }
    }
}
