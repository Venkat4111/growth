package com.invest.growth.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.invest.growth.advisor.AdvisoryOutcome;
import com.invest.growth.advisor.RiskZone;
import com.invest.growth.advisor.StockAdvisorService;
import com.invest.growth.advisor.StockAdvisoryResponse;
import com.invest.growth.advisor.TradeAction;
import com.invest.growth.ingestion.IngestionControl;
import com.invest.growth.portfolio.HoldingView;
import com.invest.growth.portfolio.KiteAuthException;
import com.invest.growth.rag.RetrievedChunk;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({DashboardController.class, IngestionController.class})
class DashboardControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    StockAdvisorService advisor;

    @MockitoBean
    IngestionControl ingestionControl;

    @MockitoBean
    com.invest.growth.portfolio.KiteAuthService kiteAuth;

    @MockitoBean
    com.invest.growth.ingestion.IngestionScheduler scheduler;

    @Test
    void logoutButtonOnlyWhenLoggedIn() throws Exception {
        when(ingestionControl.status()).thenReturn(new IngestionControl.Status(true, false, null, 0, "Not run yet", 0, 0, null));
        when(kiteAuth.isAuthenticated()).thenReturn(true);
        mvc.perform(get("/")).andExpect(content().string(org.hamcrest.Matchers.containsString("action=\"/kite/logout\"")));
        when(kiteAuth.isAuthenticated()).thenReturn(false);
        mvc.perform(get("/")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/kite/logout"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Kite Login")));
    }

    @Test
    void pageHasDisclaimerAndHtmxLoader() throws Exception {
        when(ingestionControl.status()).thenReturn(new IngestionControl.Status(true, false, null, 0, "Not run yet", 0, 0, null));
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Not SEBI-registered investment advice.")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("hx-get=\"/dashboard/table\"")));
    }

    @Test
    void tableRendersRowWithBadgeAndPolling() throws Exception {
        var h = HoldingView.of("TCS", "NSE", 10, 3000, 3300, 1.2, 39.6);
        var adv = new StockAdvisoryResponse("TCS", RiskZone.HIGH, TradeAction.SELL, "1-3 Months",
                "Margins guided lower", List.of("Fact one", "Fact two"));
        var ev = new RetrievedChunk("TCS", "SCREENER", "NEWS", Instant.now(), "evidence text");
        when(advisor.adviseAll()).thenReturn(List.of(new AdvisoryOutcome.Success(h, adv, List.of(ev), Instant.now())));
        mvc.perform(get("/dashboard/table"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("TCS"),
                        org.hamcrest.Matchers.containsString("bg-orange"),
                        org.hamcrest.Matchers.containsString("SELL"),
                        org.hamcrest.Matchers.containsString("1-3 Months"),
                        org.hamcrest.Matchers.containsString("every 30s"),
                        org.hamcrest.Matchers.containsString("evidence text"))));
    }

    @Test
    void tableShowsLoginPromptWhenNotAuthenticated() throws Exception {
        when(advisor.adviseAll()).thenThrow(new KiteAuthException("no"));
        mvc.perform(get("/dashboard/table"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("not logged in")));
    }

    @Test
    void tableShowsCountAndSortsByLargestLossFirst() throws Exception {
        var gain = HoldingView.of("AAA", "NSE", 10, 100, 120, 1.0, 1.0);
        var loss = HoldingView.of("ZZZ", "NSE", 10, 100, 60, -2.0, -1.0);
        when(advisor.adviseAll()).thenReturn(List.of(
                new AdvisoryOutcome.Failure(gain, "n/a", Instant.now()),
                new AdvisoryOutcome.Failure(loss, "n/a", Instant.now())));
        String html = mvc.perform(get("/dashboard/table").param("sort", "LOSS_AMOUNT"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Stocks: 2")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("1 in loss")))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(html.indexOf("ZZZ") < html.indexOf("AAA"));
    }
}
