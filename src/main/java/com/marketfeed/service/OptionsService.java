package com.marketfeed.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.marketfeed.model.ApiResponse;
import com.marketfeed.model.OptionsChain;
import com.marketfeed.model.OptionsContract;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OptionsService {

    private final RestTemplate restTemplate;

    @Value("${market-feed.marketdata.token:}")
    private String token;

    @Value("${market-feed.marketdata.base-url:https://api.marketdata.app/v1}")
    private String baseUrl;

    @Cacheable(value = "options", key = "#symbol.toUpperCase() + '_' + #expirationEpoch")
    public ApiResponse<OptionsChain> getOptionsChain(String symbol, Long expirationEpoch) {
        if (token == null || token.isBlank()) {
            return ApiResponse.error("MARKETDATA_TOKEN not configured");
        }

        String sym = symbol.toUpperCase();
        try {
            // ── Step 1: get all expirations ───────────────────────────────────
            List<String> expirations = fetchExpirations(sym);
            if (expirations.isEmpty()) {
                return ApiResponse.error("No options expirations found for " + sym
                        + ". It may not have listed options.");
            }

            // ── Step 2: pick expiration ────────────────────────────────────────
            String targetExp = expirationEpoch != null
                    ? expirations.stream()
                        .filter(e -> e.equals(epochToDate(expirationEpoch)))
                        .findFirst().orElse(expirations.get(0))
                    : expirations.get(0);

            // ── Step 3: fetch chain ────────────────────────────────────────────
            String url = baseUrl + "/options/chain/" + sym + "/?expiration=" + targetExp
                    + "&token=" + token;

            ResponseEntity<MarketDataResponse> resp = restTemplate.exchange(
                    url, HttpMethod.GET, jsonRequest(), MarketDataResponse.class);

            MarketDataResponse body = resp.getBody();
            if (body == null || !"ok".equals(body.getS()) || body.getOptionSymbol() == null) {
                return ApiResponse.error("No options data for " + sym + " on " + targetExp);
            }

            // ── Step 4: unpack columnar arrays → contracts ────────────────────
            int n = body.getOptionSymbol().size();
            List<OptionsContract> calls = new ArrayList<>();
            List<OptionsContract> puts  = new ArrayList<>();

            double underlyingPrice = (body.getUnderlyingPrice() != null && !body.getUnderlyingPrice().isEmpty())
                    ? safeDouble(body.getUnderlyingPrice().get(0)) : 0;

            for (int i = 0; i < n; i++) {
                String side = safeStr(body.getSide(), i);
                OptionsContract c = OptionsContract.builder()
                        .contractSymbol(safeStr(body.getOptionSymbol(), i))
                        .strike(safeDouble(body.getStrike(), i))
                        .bid(safeDouble(body.getBid(), i))
                        .ask(safeDouble(body.getAsk(), i))
                        .lastPrice(safeDouble(body.getLast(), i))
                        .volume(safeLong(body.getVolume(), i))
                        .openInterest(safeLong(body.getOpenInterest(), i))
                        .impliedVolatility(safeDouble(body.getIv(), i))
                        .inTheMoney(safeBool(body.getInTheMoney(), i))
                        .expiration(safeLong(body.getExpiration(), i))
                        .change(0).changePercent(0).lastTradeDate(0)
                        .contractSize("100")
                        .delta(safeDouble(body.getDelta(), i))
                        .gamma(safeDouble(body.getGamma(), i))
                        .theta(safeDouble(body.getTheta(), i))
                        .vega(safeDouble(body.getVega(), i))
                        .build();

                if ("call".equalsIgnoreCase(side)) calls.add(c);
                else if ("put".equalsIgnoreCase(side)) puts.add(c);
            }

            List<Double> strikes = calls.stream().map(OptionsContract::getStrike)
                    .sorted().collect(Collectors.toList());

            List<Long> allExpEpochs = expirations.stream()
                    .map(this::dateToEpoch).collect(Collectors.toList());

            return ApiResponse.success(OptionsChain.builder()
                    .underlyingSymbol(sym)
                    .underlyingPrice(underlyingPrice)
                    .expirationDate(dateToEpoch(targetExp))
                    .allExpirationDates(allExpEpochs)
                    .strikes(strikes)
                    .calls(calls)
                    .puts(puts)
                    .build(), "marketdata");

        } catch (Exception e) {
            log.warn("Options fetch failed for {}: {}", sym, e.getMessage());
            return ApiResponse.error("Failed to fetch options for " + sym + ": " + e.getMessage());
        }
    }

    // ─── Expirations ──────────────────────────────────────────────────────────

    private List<String> fetchExpirations(String symbol) {
        try {
            String url = baseUrl + "/options/expirations/" + symbol + "/?token=" + token;
            ResponseEntity<ExpirationsResponse> resp = restTemplate.exchange(
                    url, HttpMethod.GET, jsonRequest(), ExpirationsResponse.class);
            if (resp.getBody() == null || resp.getBody().getExpirations() == null) return List.of();
            return resp.getBody().getExpirations();
        } catch (Exception e) {
            log.warn("Expirations fetch failed for {}: {}", symbol, e.getMessage());
            return List.of();
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpEntity<Void> jsonRequest() {
        HttpHeaders h = new HttpHeaders();
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(h);
    }

    private long dateToEpoch(String yyyyMmDd) {
        try {
            return LocalDate.parse(yyyyMmDd, DateTimeFormatter.ISO_LOCAL_DATE)
                    .atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        } catch (Exception e) { return 0; }
    }

    private String epochToDate(long epoch) {
        return Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC)
                .toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private String safeStr(List<String> list, int i) {
        return (list != null && i < list.size()) ? list.get(i) : "";
    }

    private double safeDouble(List<? extends Number> list, int i) {
        if (list == null || i >= list.size() || list.get(i) == null) return 0;
        return list.get(i).doubleValue();
    }

    private double safeDouble(Number n) {
        return n != null ? n.doubleValue() : 0;
    }

    private long safeLong(List<? extends Number> list, int i) {
        if (list == null || i >= list.size() || list.get(i) == null) return 0;
        return list.get(i).longValue();
    }

    private boolean safeBool(List<Boolean> list, int i) {
        return list != null && i < list.size() && Boolean.TRUE.equals(list.get(i));
    }

    // ─── MarketData.app response POJOs ───────────────────────────────────────

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MarketDataResponse {
        private String         s;               // "ok" or "error"
        private List<String>   optionSymbol;
        private List<String>   underlying;
        private List<String>   side;            // "call" | "put"
        private List<Long>     expiration;
        private List<Double>   strike;
        private List<Double>   bid;
        private List<Double>   ask;
        private List<Double>   last;
        private List<Long>     volume;
        private List<Long>     openInterest;
        private List<Double>   underlyingPrice;
        private List<Double>   iv;
        private List<Boolean>  inTheMoney;
        private List<Double>   delta;
        private List<Double>   gamma;
        private List<Double>   theta;
        private List<Double>   vega;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExpirationsResponse {
        private String       s;
        private List<String> expirations;
    }
}
