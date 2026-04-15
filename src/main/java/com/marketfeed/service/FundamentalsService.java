package com.marketfeed.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketfeed.model.Fundamentals;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class FundamentalsService {

    private static final String CONSENT_URL = "https://fc.yahoo.com";
    private static final String CRUMB_URL   = "https://query2.finance.yahoo.com/v1/test/getcrumb";
    private static final String SUMMARY_URL =
        "https://query2.finance.yahoo.com/v10/finance/quoteSummary/%s" +
        "?modules=summaryDetail,defaultKeyStatistics,financialData,summaryProfile,price&crumb=%s";

    private final ObjectMapper mapper = new ObjectMapper();

    // Re-use the same CookieManager so the consent cookie persists across calls
    private final CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient     httpClient    = HttpClient.newBuilder()
            .cookieHandler(cookieManager)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // Cached crumb — refreshed lazily when expired/null
    private final AtomicReference<String> crumb = new AtomicReference<>(null);

    @Cacheable(value = "fundamentals", key = "#symbol.toUpperCase()", unless = "#result.error != null")
    public Fundamentals getFundamentals(String symbol) {
        try {
            String crumbVal = ensureCrumb();
            if (crumbVal == null) {
                return Fundamentals.builder().symbol(symbol).error("Unable to authenticate with data provider").build();
            }

            String url = String.format(SUMMARY_URL, symbol.toUpperCase(), crumbVal);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 401) {
                // Crumb expired — refresh and retry once
                crumb.set(null);
                crumbVal = ensureCrumb();
                if (crumbVal == null) {
                    return Fundamentals.builder().symbol(symbol).error("Authentication failed").build();
                }
                url = String.format(SUMMARY_URL, symbol.toUpperCase(), crumbVal);
                req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            }

            YfSummaryResponse body = mapper.readValue(resp.body(), YfSummaryResponse.class);

            // Yahoo Finance wraps either under "quoteSummary" or "finance"
            List<YfResult> results = null;
            if (body.getQuoteSummary() != null && body.getQuoteSummary().getResult() != null) {
                results = body.getQuoteSummary().getResult();
            } else if (body.getFinance() != null && body.getFinance().getResult() != null) {
                results = body.getFinance().getResult();
            }

            if (results == null || results.isEmpty()) {
                log.warn("No fundamentals data for {}: {}", symbol, resp.body().substring(0, Math.min(200, resp.body().length())));
                return Fundamentals.builder().symbol(symbol).error("No fundamentals available for this symbol").build();
            }

            YfResult r  = results.get(0);
            YfSummaryDetail  sd = r.getSummaryDetail()        != null ? r.getSummaryDetail()        : new YfSummaryDetail();
            YfKeyStats       ks = r.getDefaultKeyStatistics() != null ? r.getDefaultKeyStatistics() : new YfKeyStats();
            YfFinancialData  fd = r.getFinancialData()        != null ? r.getFinancialData()        : new YfFinancialData();
            YfSummaryProfile sp = r.getSummaryProfile()       != null ? r.getSummaryProfile()       : new YfSummaryProfile();
            YfPrice          pr = r.getPrice()                != null ? r.getPrice()                : new YfPrice();

            String name = pr.getLongName() != null ? pr.getLongName()
                        : pr.getShortName() != null ? pr.getShortName()
                        : symbol.toUpperCase();

            return Fundamentals.builder()
                    .symbol(symbol.toUpperCase())
                    .name(name)
                    .description(sp.getLongBusinessSummary())
                    .sector(sp.getSector())
                    .industry(sp.getIndustry())
                    .exchange(pr.getExchangeName())
                    // Valuation
                    .peRatio(raw(sd.getTrailingPE()))
                    .forwardPE(raw(sd.getForwardPE()))
                    .pegRatio(raw(ks.getPegRatio()))
                    .priceToBook(raw(ks.getPriceToBook()))
                    .priceToSales(raw(sd.getPriceToSalesTrailing12Months()))
                    .evToEbitda(raw(ks.getEnterpriseToEbitda()))
                    .evToRevenue(raw(ks.getEnterpriseToRevenue()))
                    // Size & profitability
                    .marketCap(rawLong(sd.getMarketCap() != null ? sd.getMarketCap() : pr.getMarketCap()))
                    .revenueTtm(rawLong(fd.getTotalRevenue()))
                    .grossProfitTtm(rawLong(fd.getGrossProfits()))
                    .ebitda(rawLong(fd.getEbitda()))
                    .eps(raw(ks.getTrailingEps()))
                    .dilutedEpsTtm(raw(ks.getTrailingEps()))
                    .profitMargin(raw(fd.getProfitMargins()))
                    .operatingMargin(raw(fd.getOperatingMargins()))
                    .returnOnEquity(raw(fd.getReturnOnEquity()))
                    .returnOnAssets(raw(fd.getReturnOnAssets()))
                    // Growth
                    .revenueGrowthYoy(raw(fd.getRevenueGrowth()))
                    .earningsGrowthYoy(raw(fd.getEarningsGrowth()))
                    // Dividends
                    .dividendPerShare(raw(sd.getDividendRate()))
                    .dividendYield(raw(sd.getDividendYield()))
                    // Analyst & risk
                    .analystTargetPrice(raw(fd.getTargetMeanPrice()))
                    .beta(raw(sd.getBeta()))
                    // Moving averages
                    .ma50(raw(sd.getFiftyDayAverage()))
                    .ma200(raw(sd.getTwoHundredDayAverage()))
                    .build();

        } catch (Exception e) {
            log.error("Failed to fetch fundamentals for {}: {}", symbol, e.getMessage());
            return Fundamentals.builder().symbol(symbol).error(e.getMessage()).build();
        }
    }

    // ── Crumb management ─────────────────────────────────────────────────────

    private synchronized String ensureCrumb() {
        String existing = crumb.get();
        if (existing != null) return existing;
        try {
            // Step 1: hit fc.yahoo.com to get consent cookie
            HttpRequest consent = HttpRequest.newBuilder()
                    .uri(URI.create(CONSENT_URL))
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)")
                    .GET()
                    .build();
            httpClient.send(consent, HttpResponse.BodyHandlers.discarding());

            // Step 2: fetch crumb
            HttpRequest crumbReq = HttpRequest.newBuilder()
                    .uri(URI.create(CRUMB_URL))
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)")
                    .GET()
                    .build();
            HttpResponse<String> crumbResp = httpClient.send(crumbReq, HttpResponse.BodyHandlers.ofString());
            String c = crumbResp.body().trim();
            if (c.isEmpty() || c.startsWith("{")) {
                log.warn("Failed to obtain YF crumb, body: {}", c);
                return null;
            }
            crumb.set(c);
            log.info("Obtained Yahoo Finance crumb (len={})", c.length());
            return c;
        } catch (Exception e) {
            log.error("Error fetching Yahoo Finance crumb: {}", e.getMessage());
            return null;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Double raw(RawValue rv) {
        if (rv == null || rv.getRaw() == 0.0) return null;
        return rv.getRaw();
    }

    private Long rawLong(RawValue rv) {
        if (rv == null || rv.getRaw() == 0.0) return null;
        return (long) rv.getRaw();
    }

    // ── Yahoo Finance response POJOs ──────────────────────────────────────────

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfSummaryResponse {
        @JsonProperty("quoteSummary")
        private YfWrapper quoteSummary;
        @JsonProperty("finance")
        private YfWrapper finance;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfWrapper {
        private List<YfResult> result;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfResult {
        private YfSummaryDetail      summaryDetail;
        private YfKeyStats           defaultKeyStatistics;
        private YfFinancialData      financialData;
        private YfSummaryProfile     summaryProfile;
        private YfPrice              price;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfSummaryDetail {
        private RawValue trailingPE;
        private RawValue forwardPE;
        private RawValue beta;
        private RawValue dividendRate;
        private RawValue dividendYield;
        private RawValue marketCap;
        private RawValue fiftyDayAverage;
        private RawValue twoHundredDayAverage;
        private RawValue priceToSalesTrailing12Months;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfKeyStats {
        private RawValue trailingEps;
        private RawValue forwardEps;
        private RawValue pegRatio;
        private RawValue priceToBook;
        private RawValue enterpriseToEbitda;
        private RawValue enterpriseToRevenue;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfFinancialData {
        private RawValue profitMargins;
        private RawValue operatingMargins;
        private RawValue returnOnEquity;
        private RawValue returnOnAssets;
        private RawValue totalRevenue;
        private RawValue grossProfits;
        private RawValue ebitda;
        private RawValue revenueGrowth;
        private RawValue earningsGrowth;
        private RawValue targetMeanPrice;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfSummaryProfile {
        private String sector;
        private String industry;
        private String longBusinessSummary;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfPrice {
        private String longName;
        private String shortName;
        private String exchangeName;
        private RawValue marketCap;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawValue {
        private double raw;
    }
}
