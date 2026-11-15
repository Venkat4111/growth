package com.invest.growth.rag;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

@Service
public class RagRetriever {

    private static final int CANDIDATE_MULTIPLIER = 3;
    private static final String QUERY_TEMPLATE =
            "%s latest management commentary guidance debt margins outlook risks news";

    private final VectorStore vectorStore;
    private final RagProperties props;

    public RagRetriever(VectorStore vectorStore, RagProperties props) {
        this.vectorStore = vectorStore;
        this.props = props;
    }

    /** Top-K chunks for a ticker: pulls relevant candidates, then keeps the most recent K. */
    public List<RetrievedChunk> retrieve(String ticker) {
        var filter = new FilterExpressionBuilder().eq("ticker", ticker).build();
        SearchRequest request = SearchRequest.builder()
                .query(QUERY_TEMPLATE.formatted(ticker))
                .topK(props.topK() * CANDIDATE_MULTIPLIER)
                .similarityThreshold(props.similarityThreshold())
                .filterExpression(filter)
                .build();
        List<Document> docs = vectorStore.similaritySearch(request);
        return docs == null ? List.of() : docs.stream()
                .map(RagRetriever::toChunk)
                .sorted(Comparator.comparing(RetrievedChunk::publishedAt).reversed())
                .limit(props.topK())
                .toList();
    }

    private static RetrievedChunk toChunk(Document d) {
        var m = d.getMetadata();
        return new RetrievedChunk(
                str(m.get("ticker")), str(m.get("source")), str(m.get("docType")),
                parse(str(m.get("publishedAt"))), d.getText());
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static Instant parse(String s) {
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }
}
