package com.invest.growth.portfolio;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(KiteController.class)
class KiteControllerLogoutTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    KiteAuthService auth;
    @MockitoBean
    PortfolioService portfolio;

    @Test
    void logoutClearsSessionAndRedirectsHome() throws Exception {
        mvc.perform(post("/kite/logout"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/"));
        verify(auth).logout();
    }
}
