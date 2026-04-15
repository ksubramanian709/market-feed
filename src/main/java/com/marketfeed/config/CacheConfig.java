package com.marketfeed.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();

        // Different TTLs per cache name — this is a real pattern in data platforms
        manager.registerCustomCache("quotes",
                Caffeine.newBuilder().expireAfterWrite(60, TimeUnit.SECONDS).maximumSize(500).build());

        manager.registerCustomCache("history",
                Caffeine.newBuilder().expireAfterWrite(6, TimeUnit.HOURS).maximumSize(200).build());

        manager.registerCustomCache("commodities",
                Caffeine.newBuilder().expireAfterWrite(2, TimeUnit.MINUTES).maximumSize(50).build());

        // FRED data — changes infrequently (monthly/quarterly releases)
        manager.registerCustomCache("economic",
                Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.HOURS).maximumSize(50).build());

        // USDA WASDE — monthly report, safe to cache 12 hours
        manager.registerCustomCache("wasde",
                Caffeine.newBuilder().expireAfterWrite(12, TimeUnit.HOURS).maximumSize(10).build());

        // News — Alpha Vantage, refreshed every 15 minutes
        manager.registerCustomCache("news",
                Caffeine.newBuilder().expireAfterWrite(15, TimeUnit.MINUTES).maximumSize(100).build());

        // Markets overview strip — same TTL as individual quotes
        manager.registerCustomCache("markets",
                Caffeine.newBuilder().expireAfterWrite(60, TimeUnit.SECONDS).maximumSize(5).build());

        // Alpha Vantage OVERVIEW — changes infrequently, cache 1 hour
        manager.registerCustomCache("fundamentals",
                Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.HOURS).maximumSize(200).build());

        return manager;
    }
}
