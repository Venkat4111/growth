package com.invest.growth.ingestion;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

/** In-memory record of indexed content hashes; lives and resets together with the in-memory vector store. */
@Repository
public class IngestedHashRepository {

    private final Set<String> hashes = ConcurrentHashMap.newKeySet();

    public boolean exists(String hash) {
        return hashes.contains(hash);
    }

    public void save(String hash, String ticker, Source source) {
        hashes.add(hash);
    }
}
