package com.invest.growth.web;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.invest.growth.advisor.AdvisoryOutcome;
import com.invest.growth.advisor.StockAdvisorService;
import com.invest.growth.portfolio.KiteAuthException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss 'IST'");

    private final StockAdvisorService advisor;
    private final IngestionController ingestionController;

    public DashboardController(StockAdvisorService advisor, IngestionController ingestionController) {
        this.advisor = advisor;
        this.ingestionController = ingestionController;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        ingestionController.populate(model);
        return "dashboard";
    }

    /** HTMX target: loaded on page load and re-polled every 300s. */
    @GetMapping("/dashboard/table")
    public String table(@RequestParam(value = "sort", required = false) String sort, Model model) {
        SortOption sortOption = SortOption.parse(sort);
        List<DashboardRow> rows = List.of();
        boolean loginRequired = false;
        String error = null;
        try {
            List<AdvisoryOutcome> outcomes = advisor.adviseAll();
            rows = outcomes.stream().map(DashboardRow::from)
                    .sorted(java.util.Comparator.comparing(DashboardRow::holding, sortOption.comparator()))
                    .toList();
        } catch (KiteAuthException e) {
            loginRequired = true;
            log.info("Dashboard requires Kite login: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Dashboard refresh failed", e);
            error = "Could not refresh the dashboard. It will retry automatically.";
        }
        model.addAttribute("rows", rows);
        model.addAttribute("sort", sortOption.name());
        model.addAttribute("sortOptions", SortOption.values());
        model.addAttribute("lossCount", rows.stream().filter(r -> r.holding().unrealizedPnl().signum() < 0).count());
        model.addAttribute("loginRequired", loginRequired);
        model.addAttribute("error", error);
        model.addAttribute("updatedAt", ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).format(TS));
        return "fragments/portfolio :: portfolioTable";
    }
}
