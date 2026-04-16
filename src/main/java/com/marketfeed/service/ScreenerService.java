package com.marketfeed.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketfeed.model.Fundamentals;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScreenerService {

    private final YahooFinanceCrumbService crumbService;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final int PAGE_SIZE = 250;
    private static final int PRICE_BATCH = 200;

    // Yahoo Finance predefined sector screener IDs → human-readable sector names
    private static final Map<String, String> SECTORS = Map.ofEntries(
        Map.entry("ms_technology",               "Technology"),
        Map.entry("ms_financial_services",       "Financial Services"),
        Map.entry("ms_healthcare",               "Healthcare"),
        Map.entry("ms_consumer_cyclical",        "Consumer Cyclical"),
        Map.entry("ms_industrials",              "Industrials"),
        Map.entry("ms_basic_materials",          "Basic Materials"),
        Map.entry("ms_communication_services",   "Communication Services"),
        Map.entry("ms_consumer_defensive",       "Consumer Defensive"),
        Map.entry("ms_energy",                   "Energy"),
        Map.entry("ms_real_estate",              "Real Estate"),
        Map.entry("ms_utilities",                "Utilities")
    );

    private static final String SCREENER_URL =
        "https://query1.finance.yahoo.com/v1/finance/screener/predefined/saved" +
        "?formatted=false&lang=en-US&region=US&count=%d&offset=%d&scrIds=%s&crumb=%s";

    private static final String BULK_QUOTE_URL =
        "https://query1.finance.yahoo.com/v7/finance/quote?symbols=%s" +
        "&fields=regularMarketPrice,regularMarketChangePercent&crumb=%s";

    // Live price overlay: symbol → [price, changePercent]
    private final ConcurrentHashMap<String, double[]> priceCache = new ConcurrentHashMap<>();

    // Shared thread pool for both universe fetch and price refresh
    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    // ── Universe fetch ────────────────────────────────────────────────────────

    @Cacheable(value = "screener-universe", key = "'all'", unless = "#result == null || #result.isEmpty()")
    public List<Fundamentals> getUniverse() {
        String crumb = crumbService.getCrumb();
        if (crumb == null) {
            log.error("Cannot fetch screener universe — no YF crumb");
            return List.of();
        }

        log.info("Loading screener universe from Yahoo Finance sector screens…");

        try {
            // First pass: get page 0 for each sector to learn total counts
            List<CompletableFuture<SectorPage>> firstPages = SECTORS.entrySet().stream()
                .map(e -> CompletableFuture.supplyAsync(
                    () -> fetchPage(e.getKey(), e.getValue(), 0, crumb), pool))
                .toList();

            List<SectorPage> pages = firstPages.stream()
                .map(f -> { try { return f.get(15, TimeUnit.SECONDS); } catch (Exception ex) { return null; } })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

            // Second pass: fetch remaining pages for sectors with >250 stocks
            List<CompletableFuture<SectorPage>> extraPages = new ArrayList<>();
            for (SectorPage p : pages) {
                for (int offset = PAGE_SIZE; offset < p.total; offset += PAGE_SIZE) {
                    final int off = offset;
                    extraPages.add(CompletableFuture.supplyAsync(
                        () -> fetchPage(p.scrId, p.sector, off, crumb), pool));
                }
            }
            for (CompletableFuture<SectorPage> f : extraPages) {
                try {
                    SectorPage p = f.get(15, TimeUnit.SECONDS);
                    if (p != null) pages.add(p);
                } catch (Exception ignored) {}
            }

            // Merge, deduplicate by symbol
            Map<String, Fundamentals> bySymbol = new LinkedHashMap<>();
            pages.stream()
                .flatMap(p -> p.stocks.stream())
                .forEach(f -> bySymbol.putIfAbsent(f.getSymbol(), f));

            List<Fundamentals> result = new ArrayList<>(bySymbol.values());
            log.info("Screener universe loaded: {} stocks across {} sectors", result.size(), SECTORS.size());
            return result;

        } catch (Exception e) {
            log.error("Failed to load screener universe: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Background price refresh every 60 seconds ─────────────────────────────

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void refreshPrices() {
        List<Fundamentals> universe;
        try {
            universe = getUniverse();
        } catch (Exception e) {
            return;
        }
        if (universe.isEmpty()) return;

        String crumb = crumbService.getCrumb();
        if (crumb == null) return;

        List<String> symbols = universe.stream().map(Fundamentals::getSymbol).toList();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < symbols.size(); i += PRICE_BATCH) {
            List<String> batch = symbols.subList(i, Math.min(i + PRICE_BATCH, symbols.size()));
            String syms = String.join(",", batch);
            final String finalCrumb = crumb;
            futures.add(CompletableFuture.runAsync(() -> fetchPriceBatch(syms, finalCrumb), pool));
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                             .get(30, TimeUnit.SECONDS);
            log.debug("Price cache refreshed: {} symbols", priceCache.size());
        } catch (Exception e) {
            log.warn("Price refresh timed out: {}", e.getMessage());
        }
    }

    private void fetchPriceBatch(String symbols, String crumb) {
        try {
            String url = String.format(BULK_QUOTE_URL, symbols, crumb);
            HttpResponse<String> resp = crumbService.getHttpClient()
                    .send(crumbService.get(url), HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 401) {
                crumbService.invalidate();
                return;
            }

            YfQuoteResponse body = mapper.readValue(resp.body(), YfQuoteResponse.class);
            if (body.getQuoteResponse() == null || body.getQuoteResponse().getResult() == null) return;

            for (YfBulkQuote q : body.getQuoteResponse().getResult()) {
                if (q.getSymbol() == null) continue;
                double price  = q.getRegularMarketPrice()         != null ? q.getRegularMarketPrice()         : 0;
                double change = q.getRegularMarketChangePercent() != null ? q.getRegularMarketChangePercent() : 0;
                if (price > 0) priceCache.put(q.getSymbol(), new double[]{price, change});
            }
        } catch (Exception e) {
            log.warn("Price batch fetch failed for batch starting {}: {}", symbols.split(",")[0], e.getMessage());
        }
    }

    // ── Fetch one page from a sector screener ─────────────────────────────────

    private SectorPage fetchPage(String scrId, String sector, int offset, String crumb) {
        try {
            String url = String.format(SCREENER_URL, PAGE_SIZE, offset, scrId, crumb);
            HttpResponse<String> resp = crumbService.getHttpClient()
                    .send(crumbService.get(url), HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 401) {
                crumbService.invalidate();
                return new SectorPage(scrId, sector, 0, List.of());
            }

            YfScreenerResponse body = mapper.readValue(resp.body(), YfScreenerResponse.class);
            List<YfQuote> quotes = Optional.ofNullable(body.getFinance())
                .map(YfFinanceWrapper::getResult)
                .filter(r -> !r.isEmpty())
                .map(r -> r.get(0).getQuotes())
                .orElse(List.of());

            int total = Optional.ofNullable(body.getFinance())
                .map(YfFinanceWrapper::getResult)
                .filter(r -> !r.isEmpty())
                .map(r -> r.get(0).getTotal())
                .orElse(0);

            List<Fundamentals> stocks = quotes.stream()
                .filter(q -> q.getSymbol() != null)
                .map(q -> mapToFundamentals(q, sector))
                .collect(Collectors.toList());

            return new SectorPage(scrId, sector, total, stocks);

        } catch (Exception e) {
            log.warn("Failed fetching screener page scrId={} offset={}: {}", scrId, offset, e.getMessage());
            return new SectorPage(scrId, sector, 0, List.of());
        }
    }

    // ── Map YF screener quote to Fundamentals model ───────────────────────────

    private Fundamentals mapToFundamentals(YfQuote q, String sector) {
        // marketCap: YF screener may return as Double or Long; use whichever is non-null
        Double cap = q.getMarketCap() != null ? q.getMarketCap()
                   : q.getRegularMarketCap() != null ? q.getRegularMarketCap() : null;

        return Fundamentals.builder()
                .symbol(q.getSymbol())
                .name(q.getLongName() != null ? q.getLongName() : q.getShortName())
                .sector(sector)
                .currentPrice(q.getRegularMarketPrice())
                .changePercent(q.getRegularMarketChangePercent())
                .marketCap(cap != null && cap > 0 ? cap.longValue() : null)
                .peRatio(q.getTrailingPE())
                .forwardPE(q.getForwardPE())
                .eps(q.getEpsTrailingTwelveMonths())
                .priceToBook(q.getPriceToBook())
                .dividendYield(q.getDividendYield() != null && q.getDividendYield() > 0
                    ? q.getDividendYield() / 100.0
                    : null)
                .beta(q.getBeta())
                .ma50(q.getFiftyDayAverage())
                .ma200(q.getTwoHundredDayAverage())
                .evToEbitda(q.getLastCloseTevEbitLtm())
                .build();
    }

    // ── Screen with filters ───────────────────────────────────────────────────

    public List<Fundamentals> screen(ScreenerParams p) {
        return getUniverse().stream()
            .filter(f -> p.sector()        == null || p.sector().isBlank()
                         || p.sector().equalsIgnoreCase(f.getSector()))
            .filter(f -> p.minPE()         == null || (f.getPeRatio()      != null && f.getPeRatio()      >= p.minPE()))
            .filter(f -> p.maxPE()         == null || (f.getPeRatio()      != null && f.getPeRatio()      <= p.maxPE()))
            .filter(f -> p.minMarketCapB() == null || (f.getMarketCap()    != null && f.getMarketCap()    >= (long)(p.minMarketCapB() * 1e9)))
            .filter(f -> p.maxMarketCapB() == null || (f.getMarketCap()    != null && f.getMarketCap()    <= (long)(p.maxMarketCapB() * 1e9)))
            .filter(f -> p.minDivYield()   == null || (f.getDividendYield()!= null && f.getDividendYield() >= p.minDivYield() / 100.0))
            .filter(f -> p.minEps()        == null || (f.getEps()          != null && f.getEps()          >= p.minEps()))
            .filter(f -> p.maxBeta()       == null || (f.getBeta()         != null && f.getBeta()         <= p.maxBeta()))
            .map(this::overlayLivePrice)
            .sorted(comparator(p.sortBy(), p.sortDesc()))
            .collect(Collectors.toList());
    }

    private Fundamentals overlayLivePrice(Fundamentals f) {
        double[] live = priceCache.get(f.getSymbol());
        if (live == null) return f;
        return f.toBuilder().currentPrice(live[0]).changePercent(live[1]).build();
    }

    public List<String> getSectors() {
        return SECTORS.values().stream().sorted().toList();
    }

    // ── Sort comparator ───────────────────────────────────────────────────────

    private Comparator<Fundamentals> comparator(String field, boolean desc) {
        Comparator<Fundamentals> c = switch (field == null ? "marketCap" : field) {
            case "peRatio"       -> Comparator.comparingDouble(f -> nvl(f.getPeRatio()));
            case "forwardPE"     -> Comparator.comparingDouble(f -> nvl(f.getForwardPE()));
            case "eps"           -> Comparator.comparingDouble(f -> nvl(f.getEps()));
            case "dividendYield" -> Comparator.comparingDouble(f -> nvl(f.getDividendYield()));
            case "beta"          -> Comparator.comparingDouble(f -> nvl(f.getBeta()));
            case "changePercent" -> Comparator.comparingDouble(f -> nvl(f.getChangePercent()));
            case "priceToBook"   -> Comparator.comparingDouble(f -> nvl(f.getPriceToBook()));
            default              -> Comparator.comparingDouble(f -> nvl((double)(f.getMarketCap() != null ? f.getMarketCap() : 0L)));
        };
        return desc ? c.reversed() : c;
    }

    private double nvl(Double d) { return d != null ? d : 0.0; }

    // ── Filter params record ──────────────────────────────────────────────────

    public record ScreenerParams(
        String  sector,
        Double  minPE,
        Double  maxPE,
        Double  minMarketCapB,
        Double  maxMarketCapB,
        Double  minDivYield,
        Double  minEps,
        Double  maxBeta,
        String  sortBy,
        boolean sortDesc
    ) {}

    // ── Internal DTOs ─────────────────────────────────────────────────────────

    private record SectorPage(String scrId, String sector, int total, List<Fundamentals> stocks) {}

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfScreenerResponse {
        private YfFinanceWrapper finance;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfFinanceWrapper {
        private List<YfScreenerResult> result;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfScreenerResult {
        private int total;
        private List<YfQuote> quotes;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfQuote {
        private String  symbol;
        private String  shortName;
        private String  longName;
        private Double  regularMarketPrice;
        private Double  regularMarketChangePercent;
        private Double  marketCap;           // YF may return as float — use Double
        private Double  regularMarketCap;    // fallback field name
        private Double  trailingPE;
        private Double  forwardPE;
        private Double  epsTrailingTwelveMonths;
        private Double  priceToBook;
        private Double  dividendYield;
        private Double  beta;
        private Double  fiftyDayAverage;
        private Double  twoHundredDayAverage;
        private Double  lastCloseTevEbitLtm;
    }

    // ── Bulk quote response DTOs ──────────────────────────────────────────────

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfQuoteResponse {
        private YfQuoteWrapper quoteResponse;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfQuoteWrapper {
        private List<YfBulkQuote> result;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfBulkQuote {
        private String symbol;
        private Double regularMarketPrice;
        private Double regularMarketChangePercent;
    }
}
