package com.tradingplatform.evaluation;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Tracks per-strategy-instance performance from a running equity curve
 * built out of closed-trade P&L (in whatever unit calculatePnl() returns -
 * currently raw price points, not currency, since TradeSimulator has no
 * quantity/lot-size input yet). recordTrade() is the only mutator; every
 * getter below is a pure derivation from the four running totals plus the
 * equity peak/trough tracking, so there's exactly one place state can
 * drift out of sync.
 */
public class StrategyPerformance {

    private static final int SCALE = 4;

    private final Long strategyInstanceId;

    private int totalTrades;
    private int winningTrades;
    private int losingTrades;

    private BigDecimal grossProfit = BigDecimal.ZERO;
    private BigDecimal grossLoss = BigDecimal.ZERO;
    private BigDecimal netProfit = BigDecimal.ZERO;

    // Equity curve tracking, updated trade-by-trade rather than recomputed
    // from history - O(1) per trade instead of replaying every trade to
    // find the running peak on every recordTrade() call.
    private BigDecimal equity = BigDecimal.ZERO;
    private BigDecimal equityPeak = BigDecimal.ZERO;
    private BigDecimal maxDrawdown = BigDecimal.ZERO; // always >= 0, in the same unit as netProfit

    private int currentLossStreak;
    private int maxLossStreak;
    private int currentWinStreak;
    private int maxWinStreak;

    public StrategyPerformance(Long strategyInstanceId) {
        this.strategyInstanceId = strategyInstanceId;
    }

    public void recordTrade(BigDecimal pnl) {

        totalTrades++;

        if (pnl.compareTo(BigDecimal.ZERO) > 0) {
            winningTrades++;
            grossProfit = grossProfit.add(pnl);
            currentWinStreak++;
            currentLossStreak = 0;
            maxWinStreak = Math.max(maxWinStreak, currentWinStreak);
        } else if (pnl.compareTo(BigDecimal.ZERO) < 0) {
            losingTrades++;
            grossLoss = grossLoss.add(pnl.abs());
            currentLossStreak++;
            currentWinStreak = 0;
            maxLossStreak = Math.max(maxLossStreak, currentLossStreak);
        }
        // A breakeven trade (pnl == 0) counts toward totalTrades but resets
        // neither streak - it's neither a win nor a loss, so it shouldn't
        // silently extend or silently break either streak's count.

        netProfit = netProfit.add(pnl);

        equity = equity.add(pnl);
        if (equity.compareTo(equityPeak) > 0) {
            equityPeak = equity;
        }
        BigDecimal drawdownFromPeak = equityPeak.subtract(equity);
        if (drawdownFromPeak.compareTo(maxDrawdown) > 0) {
            maxDrawdown = drawdownFromPeak;
        }
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
                SCALE,
                RoundingMode.HALF_UP
        );
    }

    /**
     * Peak-to-trough decline of the running equity curve, at its worst
     * point seen so far - not just the most recent dip. Always >= 0.
     * Same unit as netProfit (raw price points until TradeSimulator
     * carries a quantity/lot size).
     */
    public BigDecimal getMaxDrawdown() {
        return maxDrawdown;
    }

    /**
     * Average P&L per trade, wins and losses both included. Mathematically
     * identical to (winRate * avgWin) - (lossRate * avgLoss) - this is
     * just the more numerically direct way to compute the same number.
     * The single most honest one-number summary of "is this strategy
     * actually worth running": positive and comfortably clear of zero
     * after costs is the bar, not win rate or profit factor alone.
     */
    public BigDecimal getExpectancy() {
        if (totalTrades == 0) {
            return BigDecimal.ZERO;
        }
        return netProfit.divide(BigDecimal.valueOf(totalTrades), SCALE, RoundingMode.HALF_UP);
    }

    public BigDecimal getAverageWin() {
        if (winningTrades == 0) {
            return BigDecimal.ZERO;
        }
        return grossProfit.divide(BigDecimal.valueOf(winningTrades), SCALE, RoundingMode.HALF_UP);
    }

    public BigDecimal getAverageLoss() {
        if (losingTrades == 0) {
            return BigDecimal.ZERO;
        }
        return grossLoss.divide(BigDecimal.valueOf(losingTrades), SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Average win divided by average loss - independent of win rate, so a
     * strategy can have a below-50% win rate and still be worth running if
     * this is high enough. Returns null (not zero, not infinity) when
     * there's no loss data yet to divide by, since 0 or a fabricated large
     * number would both misrepresent "not enough data" as a real result.
     */
    public BigDecimal getRewardToRiskRatio() {
        BigDecimal avgLoss = getAverageLoss();
        if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return getAverageWin().divide(avgLoss, SCALE, RoundingMode.HALF_UP);
    }

    public int getCurrentLossStreak() {
        return currentLossStreak;
    }

    public int getMaxLossStreak() {
        return maxLossStreak;
    }

    public int getCurrentWinStreak() {
        return currentWinStreak;
    }

    public int getMaxWinStreak() {
        return maxWinStreak;
    }
}