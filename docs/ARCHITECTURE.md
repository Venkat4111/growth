# Growth: AI-Powered Indian Stock Portfolio & Risk Advisor

A Spring Boot web application that reads a user's Zerodha Kite holdings, continuously ingests public
information (Screener.in, Economic Times), and uses a RAG pipeline with an LLM to produce a per-stock
risk zone and BUY / HOLD / SELL suggestion. Results are shown on a server-rendered dashboard.

> AI-generated research summary based on public filings and news. Not SEBI-registered investment advice.

---

## 1. Technology stack

| Area | Choice |
|---|---|
| Language | Java 24 (the project spec said 25; the installed JDK and `java.version` in `pom.xml` are 24) |
| Framework | Spring Boot 4.1.2 (Spring Framework 7, Web MVC, Scheduling, Jackson 3 `tools.jackson.*`) |
| Virtual threads | `spring.threads.virtual.enabled=true`, plus virtual-thread executors in ingestion and advisor |
| Frontend | Thymeleaf (server-side rendered), Bootstrap 5, HTMX |
| Broker API | Zerodha Kite Connect Java SDK 4.0.1 |
| AI / RAG | Spring AI 2.0.2: `ChatClient`, `VectorStore`, `BeanOutputConverter`, OpenAI chat + embeddings |
| Messaging | RabbitMQ 4.x via Spring AMQP (`spring-boot-starter-amqp`) |
| Database | PostgreSQL 17 (advisory history only) |
| Vector store | `SimpleVectorStore`, in memory, rebuilt after restart |
| Parsing | jsoup 1.24.1 (Screener HTML), Rome 2.1.0 (ET RSS) |
| Build | Maven wrapper (`./mvnw`) |

Base package: `com.invest.growth`. The component-scan root is this package, so all features live under it.

---

## 2. High-level architecture

```
                         ┌──────────────────────────────┐
   Browser (HTMX) ◄─────►│  web: Dashboard / Ingestion  │
                         │  / AdvisoryHistory controllers│
                         └───────┬───────────┬──────────┘
                                 │           │
                  reads cache    │           │ advisories
                                 ▼           ▼
                    ┌────────────────┐   ┌──────────────────┐        ┌──────────────┐
                    │PortfolioService│   │StockAdvisorService│──────►│ OpenAI chat  │
                    │ (snapshot cache)│  │ (RAG + LLM)       │        └──────────────┘
                    └───────▲────────┘   └───┬─────────┬────┘
                            │                │         │
        HoldingsUpdateListener              │         └──► PostgreSQL (advisory_history)
                            │                ▼
                  ┌─────────┴────────┐   ┌──────────────┐   ┌──────────────┐
   RabbitMQ       │ portfolio.updates│   │ RagRetriever │◄──│ SimpleVector │◄── DocumentIndexer
   (fanout)       └─────────▲────────┘   └──────────────┘   │ Store        │        ▲
                            │                               └──────────────┘        │
                  KiteRefreshWorker ──► Kite Connect API              IngestionScheduler (5 min)
                            ▲                                          ├─ ScreenerScraper
        portfolio.refresh.requests (max 1)                             └─ EconomicTimesParser
                            ▲
              PortfolioRefreshScheduler (2 s)
```

Two independent background pipelines feed the dashboard:

1. **Price pipeline (2 s):** Kite LTP and day change, routed through RabbitMQ (section 5).
2. **Knowledge pipeline (5 min):** Screener and ET data, deduplicated and embedded into the vector store (section 6).

The advisor combines the latest cached prices with retrieved knowledge to ask the LLM for a verdict (section 7).

---

## 3. Package layout

```
com.invest.growth
├── GrowthApplication            @SpringBootApplication, @ConfigurationPropertiesScan, @EnableScheduling
├── portfolio/                   Module A: Kite login, holdings, price refresh
├── messaging/                   RabbitMQ topology, worker, listener, message records
├── ingestion/                   Module B: scrapers, dedup, chunking, indexing, run control
├── rag/                         Retrieval + vector store configuration
├── advisor/                     Module C: LLM advisory, history persistence
└── web/                         Module D: MVC controllers and view models
```

### 3.1 `portfolio`
| Class | Responsibility |
|---|---|
| `KiteProperties` | `kite.api-key`, `kite.api-secret`, `kite.redirect-url` (record) |
| `KiteSessionStore` | Holds the daily access token in memory. Treated as expired at the next 06:00 IST |
| `KiteAuthService` | Builds the login URL, exchanges `request_token` for an access token, and `logout()` (best-effort Kite session invalidation, then clears the token and cached snapshot) |
| `KiteController` | `GET /kite/login`, `GET /kite/callback`, `GET /kite/holdings/raw` (raw Kite JSON, for diagnosing), `POST /kite/logout` |
| `PortfolioService` | Fetches holdings from Kite, caches the latest snapshot, exposes `getHoldings()` / `getTickers()` |
| `PortfolioRefreshScheduler` | 2 s tick that enqueues a refresh request (or refreshes directly if the broker is down) |
| `HoldingView` | Record: symbol, exchange, quantity, average price, LTP, invested, current value, P&L, percentages, day change, plus MTF fields (`mtfQuantity`, `mtfCost`, `mtfOwnMoney`) and derived `mtfBorrowed()`, `mtfPnl()`, `normalPnl()`, `ownMoney()` |
| `KiteAuthException` | Raised when not logged in or Kite fails |

### 3.2 `messaging`
| Class | Responsibility |
|---|---|
| `MessagingConfig` | Declares queues, fanout exchange, binding and the JSON `MessageConverter` |
| `RefreshRequest` | Record message: `requestedAt` |
| `HoldingsUpdate` | Record message: `fetchedAt`, `List<HoldingView>` |
| `KiteRefreshWorker` | Single consumer that calls Kite and broadcasts the result |
| `HoldingsUpdateListener` | Receives broadcasts and stores them in `PortfolioService` |

### 3.3 `ingestion`
| Class | Responsibility |
|---|---|
| `IngestionProperties` | Interval, timeouts, user agent, Screener URL and cookie, concurrency, chunk size, RSS feeds |
| `IngestionScheduler` | 5-minute cycle; guarded against overlap; reports to `IngestionControl` |
| `IngestionControl` | Runtime enabled/running flags, last run, last outcome, progress, next-run estimate |
| `ScreenerScraper` | Fundamentals, quarterly trend, pros/cons, announcements, concall entries |
| `EconomicTimesParser` | Polls ET Markets RSS and filters by ticker or company name |
| `CompanyNameRegistry` | Maps tickers to Screener company names for news matching |
| `DocumentIndexer` | Dedup by hash, chunk, tag metadata, write to the vector store |
| `IngestedHashRepository` | In-memory set of already indexed SHA-256 hashes |
| `HashUtil` | SHA-256 helper |
| `IngestItem`, `Source`, `DocType` | Record and enums describing one piece of ingested content |

### 3.4 `rag`
| Class | Responsibility |
|---|---|
| `RagProperties` | `rag.top-k`, `rag.similarity-threshold` |
| `RagRetriever` | Similarity search filtered by `ticker`, ordered by recency |
| `RetrievedChunk` | Record: text and metadata of one retrieved chunk |
| `VectorStoreConfig` | Creates the `SimpleVectorStore` bean |

### 3.5 `advisor`
| Class | Responsibility |
|---|---|
| `StockAdvisorService` | Builds the prompt, calls the LLM, caches, reuses, persists and falls back |
| `StockAdvisoryResponse` | Record the LLM output is parsed into |
| `RiskZone`, `TradeAction` | Enums: `LOW/MODERATE/HIGH/CRITICAL`, `BUY/HOLD/SELL` |
| `AdvisoryOutcome` | Sealed interface for success / unavailable results |
| `AdvisorProperties` | Cache TTL and concurrency |
| `AdvisoryHistoryRepository` | `JdbcClient` persistence: `save`, `latest`, `history` |
| `StoredAdvisory` | Record for one stored analysis row |

### 3.6 `web`
| Class | Responsibility |
|---|---|
| `DashboardController` | `GET /` (shell) and `GET /dashboard/table` (HTMX fragment, sorting) |
| `DashboardRow` | View model for one table row |
| `SortOption` | Enum of the sort choices |
| `LivePriceController` | `GET /portfolio/live`: JSON of the cached snapshot (prices, summary, own-money and MTF split); 401 when logged out |
| `IngestionController` | `GET /ingestion/status`, `POST /ingestion/start|stop|run` (start also runs a cycle immediately) |
| `AdvisoryHistoryController` | `GET /advisory/history?ticker=&limit=` |

---

## 4. Module A: Kite integration

### 4.1 Login flow
1. The user opens `/kite/login`, which redirects to Kite's login page.
2. After login, Kite redirects to `kite.redirect-url` (`/kite/callback?request_token=...`).
3. `KiteAuthService` exchanges the request token (using the API secret) for an access token.
4. `KiteSessionStore` keeps the token in memory until the next 06:00 IST. **A new login is needed every day.**

Notes:
- `KiteConnect` is stateful, so a new instance is created per call.
- The Kite SDK's `KiteException` extends `Throwable`, so the code catches `Throwable`.
- The callback has no `state` check.
- Kite property names must be kebab-case (`kite.api-key`). `kite.api.key` does not bind and caused an earlier NPE.

### 4.2 Holdings data
`kite.getHoldings()` returns every holding with `tradingSymbol`, `quantity`, `averagePrice`, `lastPrice`,
`dayChange` and `dayChangePercentage`. One call covers the whole portfolio. `HoldingView` derives:
- invested value = average price x quantity
- current value = LTP x quantity
- unrealized P&L = current minus invested
- total change % = P&L / invested
- day change amount = per-share `dayChange` x quantity

### 4.3 MTF holdings
Kite reports MTF separately (confirmed from real `/kite/holdings/raw` output):
- `quantity` is the normal (CNC) quantity only; `mtf.quantity`, `mtf.averagePrice`, `mtf.initialMargin` describe the MTF part.
- The top-level `averagePrice` and `pnl` already cover normal plus MTF.
- `PortfolioService.merge()` therefore uses total quantity = `quantity` + `mtf.quantity`, the top-level average
  (falling back to `mtf.averagePrice` when it is 0), and merges rows by symbol. An entry with product `MTF` counts as fully MTF.
- A stock held only through MTF (quantity 0) is still listed. The table shows an "MTF n" badge.

### 4.4 Own money vs borrowed money
- MTF cost = `mtf.averagePrice` x `mtf.quantity`.
- Own money in MTF = `mtf.initialMargin` (assumption: Kite reports the client-paid margin here; verify against the Kite app).
- Borrowed = MTF cost minus own MTF money. MTF P&L = LTP x MTF quantity minus MTF cost; the borrowed amount is fixed, so the whole loss is borne by the client. Broker interest is not included.
- Your own money invested = normal cost + MTF margin; Non-MTF P&L = total P&L minus MTF P&L.

### 4.5 Logout
`POST /kite/logout` (a POST avoids accidental or prefetch logouts) calls `KiteAuthService.logout()`, which asks Kite
to end the session (failures are logged and ignored), clears `KiteSessionStore` and the cached snapshot, then
redirects to `/`. The navbar shows "Kite connected" plus Logout when logged in, otherwise the Kite Login button.

---

## 5. 2-second price refresh through RabbitMQ

### 5.1 Why a broker
Calling Kite every 2 seconds directly from the scheduler and from every dashboard request can overwhelm the
application when Kite is slow or when several browser tabs are open. The refresh is therefore decoupled:

- Nothing on the request path calls Kite. Controllers, the advisor and ingestion read a cached snapshot.
- Only one worker ever calls Kite, so calls never overlap.
- Stale refresh requests are discarded instead of queueing up.
- Results are broadcast, so any number of app instances stay in sync with one Kite call per tick.

### 5.2 Topology

| Element | Name | Properties |
|---|---|---|
| Queue | `portfolio.refresh.requests` | durable, `x-max-length=1`, `x-overflow=drop-head` |
| Exchange | `portfolio.updates` | fanout, non-durable |
| Queue | `portfolio.updates.<uuid>` | durable, auto-delete, one per app instance, bound to the exchange |
| Converter | `JacksonJsonMessageConverter` | Jackson 3 JSON for the record messages |

RabbitMQ 4 rejects transient non-exclusive queues, which is why the queues are declared durable. The
per-instance queue is auto-delete so it disappears when the instance stops.

### 5.3 Sequence

```
PortfolioRefreshScheduler  every portfolio.refresh-rate-ms (default 2000, fixedDelay)
   │  if no Kite token → skip
   │  publish RefreshRequest ──► portfolio.refresh.requests   (keeps only the newest)
   ▼
KiteRefreshWorker (concurrency = 1)
   │  PortfolioService.fetchFromKite()          ← the only Kite call
   │  publish HoldingsUpdate ──► portfolio.updates (fanout)
   ▼
HoldingsUpdateListener (every instance)
   │  PortfolioService.accept(fetchedAt, holdings)   ← ignores older snapshots
   ▼
Dashboard / Advisor / Ingestion read PortfolioService.getHoldings()
```

### 5.4 Cache behaviour (`PortfolioService`)
- `getHoldings()` returns the cached snapshot when a token exists and the snapshot is under 60 seconds old.
- Otherwise it falls back to a direct live call via `refresh()`.
- `accept()` only replaces the snapshot if the incoming `fetchedAt` is not older than the current one.

### 5.5 Failure handling
| Situation | Behaviour |
|---|---|
| Not logged in to Kite | Scheduler skips; worker returns without calling Kite |
| Kite call fails or is slow | Worker logs the first failure only and drops the message (no redelivery); next tick asks again |
| RabbitMQ unreachable | Scheduler logs once and refreshes directly from Kite; logs again on recovery |
| Slow Kite response | Extra requests are dropped by the max-length-1 queue, so nothing accumulates |

### 5.6 Configuration
| Property | Meaning |
|---|---|
| `portfolio.refresh-rate-ms` | Interval between ticks (default 2000) |
| `portfolio.messaging.prefix` | Prefix of queue and exchange names (default `portfolio`; tests use `portfolio-test` so they never consume real requests from a shared broker) |
| `spring.rabbitmq.host/port/username/password` | Broker connection (local default `localhost:5672`, `guest`/`guest`) |

`guest` works only from localhost; use real credentials elsewhere.

---

## 6. Module B: knowledge ingestion (every 5 minutes)

### 6.1 Cycle
`IngestionScheduler` runs every `ingestion.fixed-rate-ms` (300000). Each cycle:
1. Checks the runtime `enabled` flag (the dashboard Stop button) and a no-overlap guard.
2. Gets the tickers from `PortfolioService`.
3. For each ticker on virtual threads (bounded by `ingestion.max-concurrency`), runs `ScreenerScraper`.
4. Polls the ET RSS feeds once and keeps articles that match a ticker or company name (word-boundary regex).
5. Sends every item through `DocumentIndexer`.
6. Reports added count and outcome to `IngestionControl`.

A failure for one stock or feed is logged and never stops the cycle.

### 6.2 Sources
**Screener.in** (`/company/{SYMBOL}/`, jsoup):
- fundamentals from `#top-ratios` (price-driven ratios are excluded so the hash is stable)
- quarterly results table, pros and cons
- corporate announcements with dates
- concall entries (links). AI call summaries require a Screener login; an optional `SCREENER_SESSION_ID` cookie is supported, otherwise concalls are indexed as link entries

**Economic Times** (Rome): the markets RSS feeds from `ingestion.et-rss-feeds`.

All HTTP calls use timeouts (`ingestion.http-timeout-ms`) and a User-Agent header.

### 6.3 Deduplication, chunking and metadata (`DocumentIndexer`)
- Hash = SHA-256 of `ticker|dedupKey`; already-seen hashes are skipped.
- `chunk()` splits text into pieces of about `ingestion.chunk-size` characters (1500).
- Each chunk becomes a Spring AI `Document` with metadata:
  `ticker`, `source` (`SCREENER` | `ECONOMIC_TIMES`), `docType` (`MANAGEMENT_COMMENTARY` | `FUNDAMENTALS` | `NEWS`), `publishedAt` (ISO).
- `vectorStore.add(docs)` embeds the chunks with the OpenAI embedding model.

### 6.4 Run control (dashboard)
Ingestion is **off by default**. Nothing is collected until Start is clicked, which enables the schedule and runs a
cycle immediately on a virtual thread. `IngestionControl` powers the status banner and buttons:
- **RUNNING** (blue, spinner, "Processed X of Y stocks", progress bar)
- **ACTIVE** (green, next scheduled run time)
- **STOPPED** (red, "Ingestion is off. Click Start to begin collecting data.")
- Stop / Start / Run now buttons; last run time, items added and outcome.

The banner polls `/ingestion/status` every 5 seconds.

---

## 7. Module C: RAG and the LLM decision engine

### 7.1 Where the LLM is used
| Use | Model (default) | Class |
|---|---|---|
| Embeddings for indexing and queries | `text-embedding-3-small` | `DocumentIndexer`, `RagRetriever` via the `VectorStore` |
| Analysis: risk zone, action, time limit, summary, rationale | `gpt-4o-mini` | `StockAdvisorService` through `ChatClient` |

The BUY / HOLD / SELL verdict is produced by the chat model. No rule-based code decides it. Everything else
(Kite data, scraping, hashing, P&L, sorting) is ordinary code. The library is Spring AI, which calls OpenAI
through the OpenAI Java SDK. Spring AI is provider-agnostic, so the chat model can be switched (for example to
Anthropic) with a different starter and properties; embeddings would then need a separate provider because
Anthropic offers no embedding model.

### 7.2 Flow per stock
1. `RagRetriever` retrieves the top-K (`rag.top-k`, 8) chunks filtered by `ticker`, preferring recent ones.
2. `StockAdvisorService` builds a prompt from those chunks plus the user's `averagePrice` and `lastPrice`.
3. `ChatClient` calls the model; `BeanOutputConverter` parses the JSON into `StockAdvisoryResponse`.

### 7.3 Output record
```java
record StockAdvisoryResponse(
    String ticker,
    RiskZone riskZone,          // LOW, MODERATE, HIGH, CRITICAL
    TradeAction action,         // BUY, HOLD, SELL
    String timeLimit,           // e.g. "1-3 Months"
    String managementSummary,
    List<String> rationalePoints)   // 2-3 points citing Screener/ET facts
```

### 7.4 Cost and cache control
- Successful results are cached for `advisor.cache-ttl-ms` (5 minutes). Failures are not cached.
- An **evidence hash** (source, docType, publishedAt, text of the retrieved chunks) is stored with each analysis.
  If it is unchanged, the previous analysis is reused and **no LLM call is made**.
- Calls run on virtual threads, bounded by `advisor.max-concurrency`.
- When there is no indexed evidence, or the LLM fails (for example 401 or 429), the last stored analysis is shown.

### 7.5 Persistence (PostgreSQL)
`schema.sql` creates `advisory_history`. A new row is written only when the evidence changes, so the table
holds a history of analyses over time. Database errors are logged and never break the dashboard.

| Item | Value |
|---|---|
| Local URL | `jdbc:postgresql://localhost:5432/growth` |
| Local user | `growth` |
| Password | supplied through configuration (see section 9) |

`spring.sql.init.mode=always` creates the table on startup.

---

## 8. Module D: dashboard

### 8.1 Routes
| Route | Purpose |
|---|---|
| `GET /` | Dashboard shell with ingestion banner; loads the table via HTMX |
| `GET /dashboard/table?sort=` | Table fragment (also the HTMX refresh target) |
| `GET /portfolio/live` | JSON polled every 2 s for in-place price updates |
| `POST /kite/logout` | Log out of Kite, redirect to `/` |
| `GET /ingestion/status` | Ingestion banner fragment |
| `POST /ingestion/start`, `/stop`, `/run` | Control the 5-minute pipeline |
| `GET /advisory/history?ticker=&limit=` | Past analyses fragment |
| `GET /kite/login`, `/kite/callback`, `/kite/holdings`, `/kite/holdings/raw` | Kite OAuth and JSON |
| `GET /actuator/health`, `/actuator/info` | Exposed actuator endpoints |

### 8.2 Table
Columns: stock symbol, avg buy price, current LTP with day and total % change, risk zone badge
(green LOW, yellow MODERATE, orange HIGH, red CRITICAL), AI suggestion, time limit, and an expandable
section with management commentary and rationale plus a "View analysis history" button.

Header: stock count (total / in loss / in profit) and a sort dropdown:
absolute amount loss, absolute percentage loss, day percentage loss, day amount loss.

### 8.3 Refresh behaviour
- Table fragment re-polls itself every 30 seconds and keeps the chosen sort.
- Prices refresh from Kite every 2 seconds (section 5). The browser polls `/portfolio/live` every 2 s and updates LTP,
  day and total lines in place by `data-*` attributes keyed on symbol, without re-rendering the table.
- Cosmetic feedback: green/red flash on changed values, a ▲/▼ tick delta, and a pulsing live indicator that turns red
  when data is stale or the connection is lost. Live values are re-applied silently after each 30 s table swap.
- Summary cards: Invested, Current value, Total P&L, Day P&L, then a money row: own money invested (and % on it),
  MTF margin / borrowed, MTF P&L (and % of MTF margin), Non-MTF P&L (section 4.4).
- Each table refresh collapses any open detail rows.
- A footer always shows the disclaimer.

---

## 9. Configuration and secrets

| File | Tracked in git | Content |
|---|---|---|
| `src/main/resources/application.properties` | yes | Only `${ENV_VAR}` placeholders, no values |
| `src/main/resources/application-local.properties` | **no** (git-ignored) | Real values, loaded by the `local` profile |

`spring.profiles.active=${SPRING_PROFILES_ACTIVE:local}` activates the local profile by default. For another
environment set `SPRING_PROFILES_ACTIVE` to something else and provide every variable. A missing variable stops
startup. An unresolved placeholder with a default is used as is; one without a default fails.

Environment variables (derived from the property names):

| Variable | Purpose |
|---|---|
| `SPRING_AI_OPENAI_API_KEY` | OpenAI key |
| `SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL`, `..._TEMPERATURE` | Chat model and temperature |
| `SPRING_AI_OPENAI_EMBEDDING_OPTIONS_MODEL` | Embedding model |
| `KITE_API_KEY`, `KITE_API_SECRET`, `KITE_REDIRECT_URL` | Kite app credentials and callback |
| `SERVER_PORT` | HTTP port (local 8111) |
| `SPRING_DATASOURCE_URL`, `_USERNAME`, `_PASSWORD` | PostgreSQL |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` | RabbitMQ |
| `PORTFOLIO_REFRESH_RATE_MS` | Kite refresh interval |
| `INGESTION_*` | Interval, timeouts, user agent, Screener URL and cookie, concurrency, chunk size, RSS feeds |
| `RAG_TOP_K`, `RAG_SIMILARITY_THRESHOLD` | Retrieval settings |
| `ADVISOR_CACHE_TTL_MS`, `ADVISOR_MAX_CONCURRENCY` | Advisor cache and concurrency |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE`, `SPRING_SQL_INIT_MODE`, `SPRING_THREADS_VIRTUAL_ENABLED`, `SPRING_APPLICATION_NAME` | Framework settings |

Never commit `application-local.properties`. If a key or secret was ever in a tracked file, rotate it.

---

## 10. Running locally

Prerequisites: JDK 24, PostgreSQL 17, RabbitMQ 4, a Kite Connect app, an OpenAI key with credits.

```bash
# PostgreSQL (Homebrew)
brew services start postgresql@17
#   role "growth", database "growth" (created once)

# RabbitMQ (Homebrew)
brew services start rabbitmq
#   AMQP on localhost:5672, guest/guest

# App (reads application-local.properties through the local profile)
./mvnw spring-boot:run
```

Then:
1. Open `http://localhost:8111/kite/login` and sign in to Zerodha (daily).
2. Open `http://localhost:8111/` for the dashboard.
3. Use the ingestion banner to Start, Stop or Run now.

The Kite app's redirect URL must be `http://localhost:8111/kite/callback`.

### IntelliJ notes
- Set the working directory to the project root so the local profile file is found.
- "package org.slf4j does not exist" or a Maven legend error is an IDE import problem: reload Maven and use Bundled Maven.

---

## 11. Testing

Run: `./mvnw test`

| Test | Covers |
|---|---|
| `GrowthApplicationTests` | Context loads (vector store and hash repository mocked, listeners off) |
| `web/DashboardControllerTest` | Rendering, sorting, count, login-required handling |
| `web/IngestionControllerTest` | Status banner and start/stop |
| `advisor/AdvisoryHistoryRepositoryTest` | Save and read history; skipped if PostgreSQL is unreachable |
| `web/LivePriceControllerTest` | Live JSON, 401 when logged out |
| `portfolio/PortfolioMtfTest` | MTF merge (ARE&M, BLS, BEL shapes) and own-money / borrowed split |
| `portfolio/KiteControllerLogoutTest` | `POST /kite/logout` clears the session and redirects |
| `messaging/HoldingsMessagingTest` | A broadcast `HoldingsUpdate` reaches the portfolio cache; skipped if RabbitMQ is unreachable |

Not covered by automated tests: a real Kite login, a real OpenAI call, the full browser UI, and a long soak of the
2-second pipeline.

---

## 12. Operational notes and known limitations

- **Daily Kite login.** Without a valid token the price pipeline and ingestion tickers are empty.
- **In-memory vector store.** Indexed chunks and dedup hashes reset on restart, so the next cycle re-embeds
  everything (an OpenAI cost). Persisting them in pgvector is a possible improvement.
- **OpenAI credits.** A 401 means the key is missing or the placeholder was not resolved; a 429 means no credits
  or rate limit. In both cases the dashboard falls back to the last stored analysis.
- **Kite rate limits and pricing.** Kite is a flat subscription with per-second request limits, not per-call
  billing. One holdings call every 2 seconds is well within limits. Confirm current terms with Zerodha.
- **Screener concall summaries** need a logged-in session cookie.
- **Table refresh collapses open rows.**
- **Message loss is acceptable.** A dropped refresh request or snapshot is replaced by the next tick.
- **Single Kite user.** The session store holds one token; this is a personal-use design.
- **Java version.** The project targets JDK 24 even though the specification mentions 25.
- **Instruction file drift.** `.github/copilot-instructions.md` still mentions pgvector and Java 25.

## 13. Possible future work

- Persist vectors and hashes in pgvector.
- Push price updates to browsers (SSE or WebSocket) instead of polling every 30 seconds.
- Retry and dead-letter handling if refresh failures should be visible.
- Alert on an expired Kite token.
- Switch the chat model to another provider and use a local embedding model to remove the OpenAI dependency.
- A CI-friendly test profile that does not require `application-local.properties`.
