package com.invest.growth.web;

import com.invest.growth.ingestion.IngestionControl;
import com.invest.growth.ingestion.IngestionScheduler;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IngestionController.class)
@Import(IngestionControl.class)
@org.springframework.boot.context.properties.EnableConfigurationProperties(com.invest.growth.ingestion.IngestionProperties.class)
class IngestionControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    IngestionControl control;

    @MockitoBean
    IngestionScheduler scheduler;

    @Test
    void stopThenStartTogglesJob() throws Exception {
        mvc.perform(post("/ingestion/stop"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("INGESTION STOPPED")));
        assertFalse(control.isEnabled());
        mvc.perform(post("/ingestion/start"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("INGESTION ACTIVE")));
        assertTrue(control.isEnabled());
    }
}
