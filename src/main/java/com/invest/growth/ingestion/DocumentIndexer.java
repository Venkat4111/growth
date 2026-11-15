package com.invest.growth.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/** Deduplicates items by SHA-256 hash and writes new ones to pgvector as tagged chunks. */
@Service
public class DocumentIndexer {

    private static final Logger log = LoggerFactory.getLogger(DocumentIndexer.class);

    private final VectorStore vectorStore;
    private final IngestedHashRepository hashes;
    private final IngestionProperties props;

    public DocumentIndexer(VectorStore vectorStore, IngestedHashRepository hashes, IngestionProperties props) {
        this.vectorStore = vectorStore;
        this.hashes = hashes;
        this.props = props;
    }

    /** Hash is scoped per ticker so one article can be indexed for several holdings. */
    public boolean index(IngestItem item) {
        String hash = HashUtil.sha256(item.ticker() + "|" + item.dedupKey());
        if (hashes.exists(hash)) {
            return false;
        }
        List<Document> docs = new ArrayList<>();
        List<String> chunks = chunk(item.text());
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> metadata = Map.of(
                    "ticker", item.ticker(),
                    "source", item.source().name(),
                    "docType", item.docType().name(),
                    "publishedAt", item.publishedAt().toString());
            String id = UUID.nameUUIDFromBytes((hash + "#" + i).getBytes()).toString();
            docs.add(new Document(id, chunks.get(i), metadata));
        }
        if (docs.isEmpty()) {
            return false;
        }
        vectorStore.add(docs);
        hashes.save(hash, item.ticker(), item.source());
        log.info("Indexed {} chunk(s) for {} [{}/{}]", docs.size(), item.ticker(), item.source(), item.docType());
        return true;
    }

    private List<String> chunk(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        int size = props.chunkSize();
        String t = text.strip();
        int start = 0;
        while (start < t.length()) {
            int end = Math.min(start + size, t.length());
            if (end < t.length()) {
                int nl = t.lastIndexOf('\n', end);
                if (nl > start + size / 2) {
                    end = nl;
                }
            }
            String piece = t.substring(start, end).strip();
            if (!piece.isEmpty()) {
                out.add(piece);
            }
            start = end;
        }
        return out;
    }
}
