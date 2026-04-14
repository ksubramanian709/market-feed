package com.marketfeed.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class Quote {
    private String symbol;
    private String name;
    private double price;
    private double change;
    private double changePercent;
    private double open;
    private double high;
    private double low;
    private long volume;
    private String currency;
    private AssetType assetType;
    private Instant timestamp;

    // Extended stats — populated when available from Yahoo Finance
    private long   marketCap;
    private double fiftyTwoWeekHigh;
    private double fiftyTwoWeekLow;

    public enum AssetType {
        EQUITY, FUTURE, FOREX, INDEX, COMMODITY, CRYPTO
    }
}
