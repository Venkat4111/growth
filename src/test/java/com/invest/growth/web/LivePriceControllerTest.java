package com.invest.growth.web;

import java.time.Instant;
import java.util.List;

import com.invest.growth.portfolio.HoldingView;
import com.invest.growth.portfolio.KiteAuthException;
import com.invest.growth.portfolio.PortfolioService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LivePriceController.class)
class LivePriceControllerTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    PortfolioService portfolio;

    @Test
    void returnsPricesAndSummary() throws Exception {
        when(portfolio.getHoldings()).thenReturn(List.of(HoldingView.of("ABC", "NSE", 10, 100, 110, 1.5, 1.65)));
        when(portfolio.lastRefreshed()).thenReturn(Instant.now());
        mvc.perform(get("/portfolio/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stocks[0].symbol").value("ABC"))
                .andExpect(jsonPath("$.stocks[0].ltp").value(110.0))
                .andExpect(jsonPath("$.summary.pnl").value(100.0));
    }

    @Test
    void unauthorizedWhenNotLoggedIn() throws Exception {
        when(portfolio.getHoldings()).thenThrow(new KiteAuthException("no"));
        mvc.perform(get("/portfolio/live")).andExpect(status().isUnauthorized());
    }
}
