package com.invest.growth.web;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.invest.growth.ingestion.IngestionControl;
import com.invest.growth.ingestion.IngestionScheduler;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/ingestion")
public class IngestionController {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("dd MMM, HH:mm:ss").withZone(ZoneId.of("Asia/Kolkata"));
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Asia/Kolkata"));
    static final String FRAGMENT = "fragments/ingestion :: ingestionControl";

    private final IngestionControl control;
    private final IngestionScheduler scheduler;

    public IngestionController(IngestionControl control, IngestionScheduler scheduler) {
        this.control = control;
        this.scheduler = scheduler;
    }

    @GetMapping("/status")
    public String status(Model model) {
        return render(model);
    }

    @PostMapping("/start")
    public String start(Model model) {
        control.setEnabled(true);
        return render(model);
    }

    @PostMapping("/stop")
    public String stop(Model model) {
        control.setEnabled(false);
        return render(model);
    }

    @PostMapping("/run")
    public String runNow(Model model) {
        Thread.startVirtualThread(scheduler::run);
        return render(model);
    }

    String render(Model model) {
        populate(model);
        return FRAGMENT;
    }

    void populate(Model model) {
        var s = control.status();
        model.addAttribute("ingestion", s);
        model.addAttribute("lastRunText", s.lastRun() == null ? "never" : TS.format(s.lastRun()));
        model.addAttribute("nextRunText", s.nextRun() == null ? null : TIME.format(s.nextRun()));
        long mins = s.nextRun() == null ? 0 : Math.max(0, Duration.between(Instant.now(), s.nextRun()).toMinutes());
        model.addAttribute("nextRunMinutes", mins);
        model.addAttribute("progressPercent", s.progressTotal() == 0 ? 0 : s.progressDone() * 100 / s.progressTotal());
    }
}
