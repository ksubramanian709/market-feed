package com.marketfeed.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketfeed.model.Fundamentals;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.net.http.HttpResponse;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FundamentalsService {

    private final YahooFinanceCrumbService crumbService;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String SUMMARY_URL =
        "https://query2.finance.yahoo.com/v10/finance/quoteSummary/%s" +
        "?modules=summaryDetail,defaultKeyStatistics,financialData,summaryProfile,price&crumb=%s";

    @Cacheable(value = "fundamentals", key = "#symbol.toUpperCase()", unless = "#result.error != null")
    public Fundamentals getFundamentals(String symbol) {
        try {
            String crumbVal = crumbService.getCrumb();
            if (crumbVal == null) {
                return Fundamentals.builder().symbol(symbol).error("Unable to authenticate with data provider").build();
            }

            HttpResponse<String> resp = crumbService.getHttpClient()
                    .send(crumbService.get(String.format(SUMMARY_URL, symbol.toUpperCase(), crumbVal)),
                          HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 401) {
                crumbService.invalidate();
                crumbVal = crumbService.getCrumb();
                if (crumbVal == null) return Fundamentals.builder().symbol(symbol).error("Authentication failed").build();
                resp = crumbService.getHttpClient()
                        .send(crumbService.get(String.format(SUMMARY_URL, symbol.toUpperCase(), crumbVal)),
                              HttpResponse.BodyHandlers.ofString());
            }

            YfSummaryResponse body = mapper.readValue(resp.body(), YfSummaryResponse.class);

            List<YfResult> results = null;
            if (body.getQuoteSummary() != null && body.getQuoteSummary().getResult() != null) {
                results = body.getQuoteSummary().getResult();
            } else if (body.getFinance() != null && body.getFinance().getResult() != null) {
                results = body.getFinance().getResult();
            }

            if (results == null || results.isEmpty()) {
                log.warn("No fundamentals data for {}", symbol);
                return Fundamentals.builder().symbol(symbol).error("No fundamentals available for this symbol").build();
            }

            YfResult r  = results.get(0);
            YfSummaryDetail  sd = nvl(r.getSummaryDetail(),        new YfSummaryDetail());
            YfKeyStats       ks = nvl(r.getDefaultKeyStatistics(), new YfKeyStats());
            YfFinancialData  fd = nvl(r.getFinancialData(),        new YfFinancialData());
            YfSummaryProfile sp = nvl(r.getSummaryProfile(),       new YfSummaryProfile());
            YfPrice          pr = nvl(r.getPrice(),                new YfPrice());

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
                    .peRatio(raw(sd.getTrailingPE()))
                    .forwardPE(raw(sd.getForwardPE()))
                    .pegRatio(raw(ks.getPegRatio()))
                    .priceToBook(raw(ks.getPriceToBook()))
                    .priceToSales(raw(sd.getPriceToSalesTrailing12Months()))
                    .evToEbitda(raw(ks.getEnterpriseToEbitda()))
                    .evToRevenue(raw(ks.getEnterpriseToRevenue()))
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
                    .revenueGrowthYoy(raw(fd.getRevenueGrowth()))
                    .earningsGrowthYoy(raw(fd.getEarningsGrowth()))
                    .dividendPerShare(raw(sd.getDividendRate()))
                    .dividendYield(raw(sd.getDividendYield()))
                    .analystTargetPrice(raw(fd.getTargetMeanPrice()))
                    .beta(raw(sd.getBeta()))
                    .ma50(raw(sd.getFiftyDayAverage()))
                    .ma200(raw(sd.getTwoHundredDayAverage()))
                    .currentPrice(raw(pr.getRegularMarketPrice()))
                    .changePercent(raw(pr.getRegularMarketChangePercent()))
                    .build();

        } catch (Exception e) {
            log.error("Failed to fetch fundamentals for {}: {}", symbol, e.getMessage());
            return Fundamentals.builder().symbol(symbol).error(e.getMessage()).build();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private <T> T nvl(T val, T def) { return val != null ? val : def; }

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
        @JsonProperty("quoteSummary") private YfWrapper quoteSummary;
        @JsonProperty("finance")      private YfWrapper finance;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfWrapper { private List<YfResult> result; }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfResult {
        private YfSummaryDetail  summaryDetail;
        private YfKeyStats       defaultKeyStatistics;
        private YfFinancialData  financialData;
        private YfSummaryProfile summaryProfile;
        private YfPrice          price;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfSummaryDetail {
        private RawValue trailingPE, forwardPE, beta, dividendRate, dividendYield;
        private RawValue marketCap, fiftyDayAverage, twoHundredDayAverage;
        private RawValue priceToSalesTrailing12Months;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfKeyStats {
        private RawValue trailingEps, forwardEps, pegRatio, priceToBook;
        private RawValue enterpriseToEbitda, enterpriseToRevenue;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfFinancialData {
        private RawValue profitMargins, operatingMargins, returnOnEquity, returnOnAssets;
        private RawValue totalRevenue, grossProfits, ebitda;
        private RawValue revenueGrowth, earningsGrowth, targetMeanPrice;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfSummaryProfile {
        private String sector, industry, longBusinessSummary;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class YfPrice {
        private String longName, shortName, exchangeName;
        private RawValue marketCap, regularMarketPrice, regularMarketChangePercent;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawValue { private double raw; }
}
