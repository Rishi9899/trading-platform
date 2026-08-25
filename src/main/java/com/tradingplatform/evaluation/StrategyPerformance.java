package com.tradingplatform.evaluation;

import java.math.BigDecimal;

public class StrategyPerformance {

    private final Long strategyInstanceId;

    private int totalTrades;
    private int winningTrades;
    private int losingTrades;

    private BigDecimal grossProfit = BigDecimal.ZERO;
    private BigDecimal grossLoss = BigDecimal.ZERO;
    private BigDecimal netProfit = BigDecimal.ZERO;

    private BigDecimal maxDrawdown = BigDecimal.ZERO;

    public StrategyPerformance(Long strategyInstanceId) {
        this.strategyInstanceId = strategyInstanceId;
    }

    public void recordTrade(BigDecimal pnl) {

        totalTrades++;

        if (pnl.compareTo(BigDecimal.ZERO) > 0) {
            winningTrades++;
            grossProfit = grossProfit.add(pnl);
        } else if (pnl.compareTo(BigDecimal.ZERO) < 0) {
            losingTrades++;
            grossLoss = grossLoss.add(pnl.abs());
        }

        netProfit = netProfit.add(pnl);
    }

    public Long getStrategyInstanceId() {
        return strategyInstanceId;
    }

    public int getTotalTrades() {
        return totalTrades;
    }

    public int getWinningTrades() {
        return winningTrades;
    }

    public int getLosingTrades() {
        return losingTrades;
    }

    public BigDecimal getGrossProfit() {
        return grossProfit;
    }

    public BigDecimal getGrossLoss() {
        return grossLoss;
    }

    public BigDecimal getNetProfit() {
        return netProfit;
    }

    public double getWinRate() {

        if (totalTrades == 0) {
            return 0.0;
        }

        return (winningTrades * 100.0) / totalTrades;
    }

    public BigDecimal getProfitFactor() {

        if (grossLoss.compareTo(BigDecimal.ZERO) == 0) {
            return grossProfit;
        }

        return grossProfit.divide(
                grossLoss,
                4,
                java.math.RoundingMode.HALF_UP
        );
    }
}