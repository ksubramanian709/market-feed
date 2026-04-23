package com.marketfeed.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketfeed.model.QuoteUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceTickerService {

    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "crypto-poll"); t.setDaemon(true); return t; });

    @Value("${market-feed.binance.enabled:true}")
    private boolean enabled;

    @Value("${market-feed.binance.poll-seconds:10}")
    private int pollSeconds;

    // CoinGecko IDs → standard symbols (BTC-USD, ETH-USD, ...)
    private static final Map<String, String> COINGECKO_IDS = Map.ofEntries(
        Map.entry("bitcoin",         "BTC-USD"),
        Map.entry("ethereum",        "ETH-USD"),
        Map.entry("solana",          "SOL-USD"),
        Map.entry("ripple",          "XRP-USD"),
        Map.entry("binancecoin",     "BNB-USD"),
        Map.entry("dogecoin",        "DOGE-USD"),
        Map.entry("cardano",         "ADA-USD"),
        Map.entry("avalanche-2",     "AVAX-USD"),
        Map.entry("chainlink",       "LINK-USD"),
        Map.entry("matic-network",   "MATIC-USD")
    );

    private static final String COINGECKO_URL =
        "https://api.coingecko.com/api/v3/coins/markets" +
        "?vs_currency=usd" +
        "&ids=bitcoin,ethereum,solana,ripple,binancecoin,dogecoin,cardano,avalanche-2,chainlink,matic-network" +
        "&order=market_cap_desc&per_page=10&page=1&sparkline=false&price_change_percentage=24h";

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) {
            log.info("[Crypto] Feed disabled via config");
            return;
        }
        log.info("[Crypto] Starting CoinGecko poll every {}s", pollSeconds);
        scheduler.scheduleAtFixedRate(this::poll, 5, pollSeconds, TimeUnit.SECONDS);
    }

    private void poll() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(COINGECKO_URL))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                log.warn("[Crypto] CoinGecko rate-limited, backing off");
                return;
            }
            if (response.statusCode() != 200) {
                log.warn("[Crypto] CoinGecko returned {}", response.statusCode());
                return;
            }

            JsonNode arr = objectMapper.readTree(response.body());
            if (!arr.isArray()) return;

            int count = 0;
            for (JsonNode node : arr) {
                String id     = node.path("id").asText();
                String symbol = COINGECKO_IDS.get(id);
                if (symbol == null) continue;

                double price         = node.path("current_price").asDouble();
                double high          = node.path("high_24h").asDouble();
                double low           = node.path("low_24h").asDouble();
                double volume        = node.path("total_volume").asDouble();
                double changePercent = node.path("price_change_percentage_24h").asDouble();
                double change        = node.path("price_change_24h").asDouble();

                if (price <= 0) continue;

                eventPublisher.publishEvent(new QuoteUpdatedEvent(
                        symbol, price, change, changePercent,
                        high, low, volume, "coingecko", Instant.now()
                ));
                count++;
            }

            log.debug("[Crypto] Polled {} symbols from CoinGecko", count);

        } catch (Exception e) {
            log.warn("[Crypto] Poll failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
