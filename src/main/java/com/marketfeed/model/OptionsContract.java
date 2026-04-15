package com.marketfeed.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OptionsContract {
    private String  contractSymbol;
    private double  strike;
    private double  lastPrice;
    private double  bid;
    private double  ask;
    private double  change;
    private double  changePercent;
    private long    volume;
    private long    openInterest;
    private double  impliedVolatility;   // decimal, e.g. 0.30 = 30%
    private boolean inTheMoney;
    private long    expiration;          // epoch seconds
    private long    lastTradeDate;
    private String  contractSize;

    // Greeks (available via Tradier)
    private double  delta;
    private double  gamma;
    private double  theta;
    private double  vega;
}
