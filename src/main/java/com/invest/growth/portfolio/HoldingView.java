package com.invest.growth.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record HoldingView(
        String tradingSymbol,
        String exchange,
        int quantity,
        BigDecimal averagePrice,
        BigDecimal lastPrice,
        BigDecimal investedValue,
        BigDecimal currentValue,
        BigDecimal unrealizedPnl,
        BigDecimal totalChangePercent,
        BigDecimal dayChangePercent,
        BigDecimal dayChangeAmount,
        int mtfQuantity) {

    public HoldingView withMtfQuantity(int mtfQty) {
        return new HoldingView(tradingSymbol, exchange, quantity, averagePrice, lastPrice, investedValue, currentValue,
                unrealizedPnl, totalChangePercent, dayChangePercent, dayChangeAmount, mtfQty);
    }


    public static HoldingView of(String symbol, String exchange, int qty,
                                 double avg, double ltp, double dayChangePct, double dayChangePerShare) {
        BigDecimal avgBd = money(avg);
        BigDecimal ltpBd = money(ltp);
        BigDecimal qtyBd = BigDecimal.valueOf(qty);
        BigDecimal invested = avgBd.multiply(qtyBd).setScale(2, RoundingMode.HALF_UP);
        BigDecimal current = ltpBd.multiply(qtyBd).setScale(2, RoundingMode.HALF_UP);
        BigDecimal pnl = current.subtract(invested);
        BigDecimal pct = invested.signum() == 0
                ? BigDecimal.ZERO
                : pnl.multiply(BigDecimal.valueOf(100)).divide(invested, 2, RoundingMode.HALF_UP);
        return new HoldingView(symbol, exchange, qty, avgBd, ltpBd, invested, current, pnl, pct, money(dayChangePct),
                BigDecimal.valueOf(dayChangePerShare).multiply(qtyBd).setScale(2, RoundingMode.HALF_UP), 0);
    }

    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
