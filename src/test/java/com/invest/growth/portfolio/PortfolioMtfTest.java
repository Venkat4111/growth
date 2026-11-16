package com.invest.growth.portfolio;

import java.util.List;

import com.zerodhatech.models.Holding;
import com.zerodhatech.models.MTFHolding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioMtfTest {

    private static Holding holding(String symbol, int qty, double avg, double ltp, int mtfQty, double mtfAvg) {
        Holding h = new Holding();
        h.tradingSymbol = symbol;
        h.exchange = "NSE";
        h.product = "CNC";
        h.quantity = qty;
        h.averagePrice = avg;
        h.lastPrice = ltp;
        h.mtf = new MTFHolding();
        h.mtf.quantity = mtfQty;
        h.mtf.averagePrice = mtfAvg;
        return h;
    }

    @Test
    void mixedStockCombinesNormalAndMtfQuantity() {
        HoldingView v = PortfolioService.merge(List.of(holding("ARE&M", 111, 888.104966, 741.8, 40, 741.75))).getFirst();
        assertThat(v.quantity()).isEqualTo(151);
        assertThat(v.mtfQuantity()).isEqualTo(40);
        // matches the pnl Kite reports for this holding (-22092.05)
        assertThat(v.unrealizedPnl().doubleValue()).isBetween(-22093.0, -22091.0);
    }

    @Test
    void mtfOnlyStockIsListed() {
        var views = PortfolioService.merge(List.of(holding("BLS", 0, 240.0, 225.84, 200, 240.0)));
        assertThat(views).hasSize(1);
        assertThat(views.getFirst().quantity()).isEqualTo(200);
        assertThat(views.getFirst().mtfQuantity()).isEqualTo(200);
        assertThat(views.getFirst().unrealizedPnl().doubleValue()).isEqualTo(-2832.0);
    }

    @Test
    void normalStockHasNoMtfBadge() {
        var v = PortfolioService.merge(List.of(holding("BEL", 60, 189.3, 401.0, 0, 0))).getFirst();
        assertThat(v.quantity()).isEqualTo(60);
        assertThat(v.mtfQuantity()).isZero();
    }

    @Test
    void splitsOwnMoneyAndBorrowedForMtf() {
        Holding h = holding("BLS", 0, 100, 90, 200, 100);
        h.mtf.initialMargin = 8000.0;
        HoldingView v = PortfolioService.merge(List.of(h)).getFirst();
        org.junit.jupiter.api.Assertions.assertEquals(0, new java.math.BigDecimal("20000").compareTo(v.mtfCost()));
        org.junit.jupiter.api.Assertions.assertEquals(0, new java.math.BigDecimal("12000").compareTo(v.mtfBorrowed()));
        org.junit.jupiter.api.Assertions.assertEquals(0, new java.math.BigDecimal("-2000").compareTo(v.mtfPnl()));
        org.junit.jupiter.api.Assertions.assertEquals(0, new java.math.BigDecimal("8000").compareTo(v.ownMoney()));
    }
}
