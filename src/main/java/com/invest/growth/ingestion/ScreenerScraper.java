package com.invest.growth.ingestion;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ScreenerScraper {

    private static final Logger log = LoggerFactory.getLogger(ScreenerScraper.class);
    // Price-driven ratios are excluded so the fundamentals block only changes when fundamentals do
    private static final Set<String> STABLE_RATIOS = Set.of("ROCE", "ROE", "Book Value", "Face Value");
    private static final int QUARTER_COLUMNS = 5;
    private static final int MAX_ANNOUNCEMENTS = 10;
    private static final int MAX_CONCALLS = 4;

    private final IngestionProperties props;
    private final CompanyNameRegistry names;

    public ScreenerScraper(IngestionProperties props, CompanyNameRegistry names) {
        this.props = props;
        this.names = names;
    }

    public List<IngestItem> fetch(String ticker) {
        List<IngestItem> items = new ArrayList<>();
        try {
            String url = props.screenerBaseUrl() + "/" + ticker + "/";
            var conn = Jsoup.connect(url)
                    .userAgent(props.userAgent())
                    .timeout(props.httpTimeoutMs())
                    .followRedirects(true);
            Document doc = conn.get();
            Element h1 = doc.selectFirst("h1");
            if (h1 != null) {
                names.put(ticker, h1.text());
            }
            Instant now = Instant.now();
            fundamentals(ticker, doc, now).ifPresent(items::add);
            items.addAll(announcements(ticker, doc));
            items.addAll(concalls(ticker, doc, now));
        } catch (Exception e) {
            log.warn("Screener fetch failed for {}: {}", ticker, e.toString());
        }
        return items;
    }

    private java.util.Optional<IngestItem> fundamentals(String ticker, Document doc, Instant now) {
        StringBuilder sb = new StringBuilder("Fundamentals for " + ticker + " (Screener.in)\n");
        for (Element li : doc.select("#top-ratios li")) {
            String name = textOf(li.selectFirst(".name"));
            if (STABLE_RATIOS.contains(name)) {
                sb.append(name).append(": ").append(textOf(li.selectFirst(".value"))).append('\n');
            }
        }
        Element quarters = doc.selectFirst("section#quarters table");
        if (quarters != null) {
            sb.append("Quarterly results (latest ").append(QUARTER_COLUMNS).append(" quarters):\n");
            for (Element row : quarters.select("tr")) {
                Elements cells = row.select("th, td");
                if (cells.isEmpty()) {
                    continue;
                }
                sb.append(cells.get(0).text());
                int from = Math.max(1, cells.size() - QUARTER_COLUMNS);
                for (int i = from; i < cells.size(); i++) {
                    sb.append(" | ").append(cells.get(i).text());
                }
                sb.append('\n');
            }
        }
        for (Element li : doc.select("section#analysis .pros li")) {
            sb.append("Pro: ").append(li.text()).append('\n');
        }
        for (Element li : doc.select("section#analysis .cons li")) {
            sb.append("Con: ").append(li.text()).append('\n');
        }
        String text = sb.toString();
        if (text.lines().count() <= 1) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new IngestItem(ticker, Source.SCREENER, DocType.FUNDAMENTALS, text, text, now));
    }

    private List<IngestItem> announcements(String ticker, Document doc) {
        List<IngestItem> out = new ArrayList<>();
        Elements links = doc.select("#company-announcements-tab ul.list-links li a");
        for (Element a : links.stream().limit(MAX_ANNOUNCEMENTS).toList()) {
            String href = a.absUrl("href");
            if (href.isBlank()) {
                continue;
            }
            Element time = a.selectFirst("time[datetime]");
            Instant published = parse(time == null ? null : time.attr("datetime"));
            String title = a.ownText().strip();
            String detail = a.selectFirst("div") == null ? "" : a.selectFirst("div").text();
            String text = "Corporate announcement for " + ticker + ": " + title + "\n" + detail + "\nLink: " + href;
            out.add(new IngestItem(ticker, Source.SCREENER, DocType.NEWS, href, text, published));
        }
        return out;
    }

    private List<IngestItem> concalls(String ticker, Document doc, Instant now) {
        List<IngestItem> out = new ArrayList<>();
        Elements rows = doc.select("li:has(a.button-chip, button.button-chip)");
        int count = 0;
        for (Element li : rows) {
            Element period = li.selectFirst("div.ink-600");
            if (period == null || count >= MAX_CONCALLS) {
                continue;
            }
            count++;
            StringBuilder sb = new StringBuilder("Management concall for ").append(ticker)
                    .append(" (").append(period.text()).append(")\n");
            StringBuilder key = new StringBuilder(ticker).append(period.text());
            for (Element a : li.select("a.button-chip")) {
                sb.append(a.text()).append(": ").append(a.absUrl("href")).append('\n');
                key.append(a.absUrl("href"));
            }
            Element summary = li.selectFirst("button[data-url^=/concalls/summary/]");
            if (summary != null && !props.screenerSessionCookie().isBlank()) {
                fetchSummary(summary.attr("data-url")).ifPresent(s -> sb.append("AI Summary:\n").append(s));
            }
            out.add(new IngestItem(ticker, Source.SCREENER, DocType.MANAGEMENT_COMMENTARY,
                    key.toString(), sb.toString(), now));
        }
        return out;
    }

    // Concall summaries require a logged-in Screener session
    private java.util.Optional<String> fetchSummary(String path) {
        try {
            String base = props.screenerBaseUrl().replaceAll("/company/?$", "");
            Document d = Jsoup.connect(base + path)
                    .userAgent(props.userAgent())
                    .timeout(props.httpTimeoutMs())
                    .cookie("sessionid", props.screenerSessionCookie())
                    .get();
            String text = d.body().text();
            return text.isBlank() || d.title().contains("Register")
                    ? java.util.Optional.empty() : java.util.Optional.of(text);
        } catch (Exception e) {
            log.warn("Concall summary fetch failed {}: {}", path, e.toString());
            return java.util.Optional.empty();
        }
    }

    private static String textOf(Element e) {
        return e == null ? "" : e.text().strip();
    }

    private static Instant parse(String iso) {
        try {
            return iso == null || iso.isBlank() ? Instant.now() : OffsetDateTime.parse(iso).toInstant();
        } catch (Exception e) {
            return Instant.now();
        }
    }
}
