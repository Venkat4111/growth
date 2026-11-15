package com.invest.growth.web;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.invest.growth.advisor.AdvisoryHistoryRepository;
import com.invest.growth.advisor.StoredAdvisory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/advisory")
public class AdvisoryHistoryController {

    private static final Logger log = LoggerFactory.getLogger(AdvisoryHistoryController.class);
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.of("Asia/Kolkata"));

    public record HistoryRow(StoredAdvisory stored, String generatedAtText) {
    }

    private final AdvisoryHistoryRepository repository;

    public AdvisoryHistoryController(AdvisoryHistoryRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/history")
    public String history(@RequestParam("ticker") String ticker,
                          @RequestParam(value = "limit", defaultValue = "20") int limit, Model model) {
        List<HistoryRow> rows = List.of();
        String error = null;
        try {
            rows = repository.history(ticker, Math.max(1, Math.min(limit, 100))).stream()
                    .map(s -> new HistoryRow(s, TS.format(s.generatedAt())))
                    .toList();
        } catch (Exception e) {
            log.warn("History lookup failed for {}: {}", ticker, e.toString());
            error = "History is unavailable right now.";
        }
        model.addAttribute("ticker", ticker);
        model.addAttribute("history", rows);
        model.addAttribute("error", error);
        return "fragments/history :: historyTable";
    }
}
