package com.invest.growth.web;

import java.util.Comparator;
import java.util.function.Function;

import com.invest.growth.portfolio.HoldingView;

import java.math.BigDecimal;

/** Loss-first orderings: the most negative value comes first, gains last. */
public enum SortOption {
    SYMBOL("Stock symbol (A-Z)", Comparator.comparing(h -> h.tradingSymbol())),
    LOSS_AMOUNT("Total loss (₹, highest first)", by(HoldingView::unrealizedPnl)),
    LOSS_PERCENT("Total loss (%, highest first)", by(HoldingView::totalChangePercent)),
    DAY_LOSS_PERCENT("Day loss (%, highest first)", by(HoldingView::dayChangePercent)),
    DAY_LOSS_AMOUNT("Day loss (₹, highest first)", by(HoldingView::dayChangeAmount));

    private final String label;
    private final Comparator<HoldingView> comparator;

    SortOption(String label, Comparator<HoldingView> comparator) {
        this.label = label;
        this.comparator = comparator;
    }

    private static Comparator<HoldingView> by(Function<HoldingView, BigDecimal> f) {
        return Comparator.comparing(f).thenComparing(HoldingView::tradingSymbol);
    }

    public String getLabel() {
        return label;
    }

    public Comparator<HoldingView> comparator() {
        return comparator;
    }

    public static SortOption parse(String value) {
        try {
            return value == null ? SYMBOL : valueOf(value);
        } catch (IllegalArgumentException e) {
            return SYMBOL;
        }
    }
}
