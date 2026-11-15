# Project Context: AI-Powered Indian Stock Portfolio & Risk Advisor

## 1. Role & Engineering Standards
You are an expert Senior Java & Spring Architect building an automated equity research and portfolio tracking web application for the Indian stock market (NSE/BSE).
- Strictly use **Java 25 (LTS)** features (Records, Pattern Matching, Sealed Interfaces, and Virtual Threads).
- Follow clean, modular package-by-feature architecture (`portfolio`, `ingestion`, `rag`, `advisor`, `web`).
- Never invent deprecated Spring Boot 2/3 or Spring AI 1.x APIs; strictly target **Spring Boot 4.1.x** and **Spring AI 2.0.x**.
- Write production-ready, non-blocking code using Java 25 Virtual Threads (`spring.threads.virtual.enabled=true`).

## 2. Technology Stack & Versions
- **Language:** Java 25 (`25.0.5`)
- **Framework:** Spring Boot `4.1.x` (Spring Framework 7, Spring Web MVC, Spring Scheduling)
- **Frontend:** Thymeleaf (Server-Side Rendered HTML5 + Bootstrap 5 / HTMX for 5-minute partial table refreshes)
- **Broker API:** Zerodha Kite Connect Java SDK (`com.zerodhatech.kiteconnect`)
- **AI & RAG:** Spring AI `2.0.x` (`ChatClient`, `VectorStore`, `QuestionAnswerAdvisor`, `BeanOutputConverter`)
- **Database:** PostgreSQL with `pgvector` extension (unified relational + vector storage)
- **Data Parsing:** `org.jsoup:jsoup` (Screener.in HTML parsing) and `com.rometools:rome` (Economic Times RSS parsing)

## 3. Core Functional Architecture

### Module A: Zerodha Kite Integration (`com.app.portfolio`)
- Implement Kite Connect OAuth login (`/kite/login` and `/kite/callback`) to store the daily `access_token`.
- Fetch user holdings (`kiteConnect.getHoldings()`) to extract:
    - `tradingSymbol` (NSE/BSE ticker)
    - `quantity`
    - `averagePrice` (User's average buy price)
    - `lastPrice` (Current / Last Traded Price - LTP)
- Calculate unrealized P&L and percentage change dynamically.

### Module B: 5-Minute Scheduled Feed Ingestion (`com.app.ingestion`)
- Run a `@Scheduled(fixedRate = 300000)` background pipeline every 5 minutes using Virtual Threads.
- **Screener.in Scraper:** Fetch company fundamentals, quarterly trends, corporate announcements, and **latest management commentary / concall highlights** for all stocks currently held in the user's Kite portfolio.
- **Economic Times RSS Parser:** Poll ET Markets RSS feeds every 5 minutes and filter news articles matching the user's portfolio company names or tickers.
- **Deduplication & Vectorization:** Compute a SHA-256 hash of every incoming announcement/article URL or text block before embedding. Skip already-indexed hashes so duplicate data is not inserted every 5 minutes.
- Store new document chunks into `pgvector` with strict metadata tags: `ticker`, `source` (`SCREENER` or `ECONOMIC_TIMES`), `docType` (`MANAGEMENT_COMMENTARY`, `FUNDAMENTALS`, `NEWS`), and `publishedAt` (ISO timestamp).

### Module C: RAG & LLM Decision Engine (`com.app.rag` & `com.app.advisor`)
- For each stock in the user's portfolio, retrieve the top-K most recent and relevant chunks from `pgvector` filtered by `ticker`.
- Prompt Spring AI `ChatClient` with:
    1. Retrieved RAG context (latest management commentary, guidelines, fundamentals, and ET news).
    2. User's position data (`averagePrice` vs. `lastPrice`).
- Use Spring AI's `BeanOutputConverter` mapped to a Java 25 `record StockAdvisoryResponse(...)` containing:
    - `String ticker`
    - `RiskZone riskZone` (Enum: `LOW`, `MODERATE`, `HIGH`, `CRITICAL`) — assessed strictly from management commentary, debt/margin guidance, and negative news catalysts.
    - `TradeAction action` (Enum: `BUY`, `HOLD`, `SELL`)
    - `String timeLimit` (Specific horizon, e.g., `"1-3 Months"`, `"Until Next Quarterly Results"`, `"Immediate / 1 Week"`)
    - `String managementSummary` (Concise summary of latest management guidelines)
    - `List<String> rationalePoints` (2–3 bullet points citing specific Screener/ET facts)

### Module D: Thymeleaf Frontend UI (`src/main/resources/templates/`)
- Build a clean, responsive dashboard (`dashboard.html`) displaying a portfolio table with columns:
    1. **Stock Symbol**
    2. **Avg Buy Price** (₹)
    3. **Current / LTP** (₹) & Day/Total % Change
    4. **Risk Zone** (Color-coded badge: Green = LOW, Yellow = MODERATE, Orange = HIGH, Red = CRITICAL)
    5. **AI Suggestion** (`BUY` / `HOLD` / `SELL`)
    6. **Time Limit**
    7. **Management Commentary & Rationale** (Expandable modal or accordion showing the RAG-retrieved evidence)
- Include an auto-refresh mechanism (HTMX `hx-trigger="every 300s"` or meta/JS refresh) to sync with the 5-minute backend cycle.
- Always render a visible footer disclaimer: *"AI-generated research summary based on public filings and news. Not SEBI-registered investment advice."*

## 4. Coding Conventions & Constraints
- Use Java 25 `record` types for all DTOs, API responses, and RAG metadata payloads.
- Externalize all API keys (`KITE_API_KEY`, `KITE_API_SECRET`, LLM API keys, DB credentials) via environment variables in `application.yml`.
- Include proper error handling, HTTP timeouts, and User-Agent headers in Jsoup/Rome scrapers so a single failed feed fetch never crashes the 5-minute scheduler.
