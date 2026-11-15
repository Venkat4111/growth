package com.invest.growth.ingestion;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;

import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class EconomicTimesParser {

    private static final Logger log = LoggerFactory.getLogger(EconomicTimesParser.class);

    private final IngestionProperties props;
    private final CompanyNameRegistry names;
    private final HttpClient http;

    public EconomicTimesParser(IngestionProperties props, CompanyNameRegistry names) {
        this.props = props;
        this.names = names;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.httpTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Polls every configured feed once and returns entries matching any of the given tickers. */
    public List<IngestItem> fetch(Collection<String> tickers) {
        List<IngestItem> out = new ArrayList<>();
        for (String feedUrl : props.etRssFeeds()) {
            try {
                for (SyndEntry entry : readFeed(feedUrl).getEntries()) {
                    out.addAll(match(entry, tickers));
                }
            } catch (Exception e) {
                log.warn("ET feed failed {}: {}", feedUrl, e.toString());
            }
        }
        return out;
    }

    private SyndFeed readFeed(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(props.httpTimeoutMs()))
                .header("User-Agent", props.userAgent())
                .GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        try (InputStream in = resp.body(); XmlReader reader = new XmlReader(in)) {
            return new SyndFeedInput().build(reader);
        }
    }

    private List<IngestItem> match(SyndEntry entry, Collection<String> tickers) {
        String title = entry.getTitle() == null ? "" : entry.getTitle();
        String desc = entry.getDescription() == null ? "" : Jsoup.parse(entry.getDescription().getValue()).text();
        String haystack = title + " " + desc;
        String link = entry.getLink();
        if (link == null || link.isBlank()) {
            return List.of();
        }
        Instant published = entry.getPublishedDate() == null ? Instant.now() : entry.getPublishedDate().toInstant();
        List<IngestItem> hits = new ArrayList<>();
        for (String ticker : tickers) {
            if (mentions(haystack, ticker)) {
                String text = "ET Markets news (" + ticker + "): " + title + "\n" + desc + "\nLink: " + link;
                hits.add(new IngestItem(ticker, Source.ECONOMIC_TIMES, DocType.NEWS, link, text, published));
            }
        }
        return hits;
    }

    private boolean mentions(String haystack, String ticker) {
        if (word(ticker).matcher(haystack).find()) {
            return true;
        }
        String name = names.get(ticker);
        return name != null && word(name).matcher(haystack).find();
    }

    private static Pattern word(String s) {
        return Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(s) + "(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);
    }
}
