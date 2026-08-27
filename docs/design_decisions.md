# Design Decisions

This document records the significant engineering decisions made while building the Distributed Job Scheduler — what was chosen, what alternatives were considered, and why. It also documents real bugs found during development, how they were diagnosed, and how they were fixed. The goal is to make the *reasoning* behind the system visible, not just the final code.

---

## 1. Database choice: MySQL over PostgreSQL

A relational schema with primary keys, foreign keys, indexes, normalization, and cascading behavior was required — no specific engine was mandated. MySQL 8.0+ satisfies all of that, including `SELECT ... FOR UPDATE SKIP LOCKED` (available since 8.0) for atomic job claiming, and native JSON column support for flexible job payloads. MySQL was chosen over PostgreSQL primarily for existing familiarity, which reduced implementation risk without sacrificing any required capability.

---

## 2. Atomic job claiming via `SELECT ... FOR UPDATE SKIP LOCKED`

**The core reliability mechanism of the entire system.** When a worker thread polls a queue, it runs a query that locks the next eligible row for update, while skipping any row already locked by another concurrent poll. The claim — row lock, plus the status update to `CLAIMED` and setting `claimed_by_worker_id`/`claimed_at` — happens inside a single transaction, so the lock is still held when the write occurs.

This avoids needing an external message broker (Redis, RabbitMQ, Kafka) while still guaranteeing exactly-once claiming, and mirrors the approach used by production job-queue libraries such as Oban (Elixir) and GoodJob (Ruby) rather than a purely academic solution.

**Why the transaction boundary matters more than the SQL syntax:** `SKIP LOCKED` only prevents two transactions from locking the *same row*. If the fetch and the status-update write were split across two separate transactions, the lock from the fetch would already be released by the time the write happens — reopening the exact race the locking was meant to prevent. The entire claim (`findClaimableJobs` + status update) is therefore one `@Transactional` method, not two steps that happen to run near each other. The same discipline is applied to the Scheduler's promotion poll (see decision 4).

---

## 3. Two-row staging model for delayed / scheduled / cron jobs

**Decision:** delayed, scheduled, and cron job creation endpoints write only into a `scheduled_jobs` staging table, with `promoted = false`. No row exists in the live `jobs` table until a separate Scheduler component promotes it.

**Why not just add a future `run_after` timestamp directly to `jobs`?** Two reasons:
- It would mean every worker poll scans a table potentially full of jobs that aren't relevant for hours, days, or (for recurring cron jobs) ever again in their current row — the hot-path claiming query stays leaner by keeping `jobs` restricted to genuinely live/claimable work.
- For recurring cron jobs specifically, mutating one row in place across many future occurrences would erase history. Instead, each promotion computes the next run time and inserts a **fresh** `scheduled_jobs` row, leaving a clean, auditable trail of every past and future occurrence.

**Consequence accepted:** `GET /queues/{id}/jobs` won't show a delayed/scheduled/cron job until it's actually been promoted — this is expected behavior, not a bug, and is documented as such.

---

## 4. Scheduler transaction boundary: fetch and promote share one transaction

The entire promotion-poll method is a single `@Transactional` unit, for the exact same reason as decision 2 — the `SKIP LOCKED` row locks from the fetch are only meaningful if they're still held when the promote-write happens. This same reasoning was independently re-applied for the Worker Engine's job claiming, confirming it as a repeated, deliberate pattern across two components rather than a one-off choice.

---

## 5. Batch job orchestration via a self-referential foreign key

`jobs.parent_job_id` references `jobs.id`. A batch submission creates one parent `Job` (type `BATCH`) plus N child jobs pointing back to it via `parent_job_id`, all within one transactional method. Children execute completely independently through the normal claim/execute/outcome flow.

Parent progress is tracked via denormalized counters on the parent row (`total_children`, `completed_children`, `failed_children`), updated transactionally as each child resolves. The parent's final status is derived once every child has resolved: `COMPLETED` if all children succeeded, `PARTIALLY_FAILED` if some hit the Dead Letter Queue. This derivation, along with the dedicated `BATCH_RESOLVED` WebSocket event, lives in one class (`JobOutcomeHandler`) — see decision 8.

**Known limitation, deliberately accepted:** neither the job API response nor the `BATCH_RESOLVED` event currently exposes live mid-batch progress (e.g. "2 of 3 children done") — only the final derived status once everything resolves. This was left as an open item rather than adding scope under time pressure.

---

## 6. Cron recurrence always inserts a fresh row, never reuses the old one

Directly related to decision 3: every time a cron-type `scheduled_jobs` row is promoted, the next run time is computed and a **new** row is inserted, rather than updating the original row's `next_run_time` in place. This keeps a complete, queryable history of every past occurrence, rather than one row silently representing "whatever the next run currently is."

---

## 7. Heartbeat timeout handling: a dedicated Reaper, separate from the Scheduler

A second, independently-scheduled background task (the **Reaper**) queries for jobs where `status = RUNNING` and `last_heartbeat_at` is older than a configured timeout. For each match, it marks the owning worker `UNRESPONSIVE` and transactionally resets the job — back to `QUEUED` if retries remain, or to `DEAD_LETTER` if the retry budget is exhausted.

**Why a separate task rather than folding this into the Scheduler's poll?** The two are solving genuinely different problems — "is this scheduled job due yet?" versus "has this worker gone silent?" — on genuinely different natural cadences. Giving the Reaper its own poll interval, deliberately *not* reused from the heartbeat-timeout window itself, means detection frequency and staleness threshold can be tuned independently rather than being artificially coupled.

This demonstrates handling **worker failure** as a distinct failure mode from **job failure** — a crashed process is a different problem than a job that threw an exception, and the system explicitly accounts for both.

---

## 8. Unified failure handling: one class owns retry-vs-DLQ and batch aggregation

`JobOutcomeHandler` is the single source of truth for what happens after any job execution outcome — success, failure-with-retries-remaining, failure-with-attempts-exhausted, or a Reaper-detected timeout. It is also the only class that ever creates a `DeadLetterQueue` row, and the only class that decides when a batch parent has fully resolved.

**Why this centralization mattered in practice, not just in theory:** during development, a duplicate-insert bug was found in the DLQ-writing path (see the incident writeup in section 15). Because there was only *one* method in the entire codebase that ever created a `DeadLetterQueue` row, the fix required editing exactly one method in exactly one file — not hunting across multiple independently-implemented failure-handling code paths that could have drifted out of sync with each other.

---

## 9. Per-project RBAC implemented at the service layer, not via Spring Security's global roles

**Decision:** `CustomUserDetailsService` deliberately returns no global authorities. Instead, role-based access control lives entirely in a `project_members` table (`role` enum: `OWNER` / `MEMBER` / `VIEWER`, unique per project+user), enforced via two reusable helper methods on the project service:
- `requireRole(user, projectId, minimumRole)` — throws if the user's role rank is below what's required
- `requireMembership(user, projectId)` — throws if the user isn't a member of the project at all

Every other service (queues, jobs, DLQ) reuses these exact two helpers rather than reimplementing authorization logic per-service.

**Why not Spring Security's built-in role system?** Global roles (`@PreAuthorize("hasRole('ADMIN')")`) model a role that applies across the *entire application*. This system needed a role that's scoped *per project* — the same user can be an Owner on one project and have no access at all to another. Modeling that cleanly meant keeping authorization as an explicit, testable service-layer concern rather than trying to force a per-resource permission model into a global-role framework it wasn't designed for.

The frontend introduces no separate RBAC logic — it simply hides or shows UI controls based on a `yourRole` field already returned by existing endpoints. The real enforcement stays entirely server-side; the UI difference is a convenience, not a security boundary.

---

## 10. Worker Engine instance model: one process, multiple threads

**Decision, made before implementation began:** demonstrate claiming-safety via one worker process running a configurable-size thread pool (`ExecutorService`, default 5 threads), rather than multiple separate worker processes.

**Reasoning:** the `SKIP LOCKED` claiming guarantee is process-agnostic — it would work identically across multiple processes, since the lock lives in the database, not in application memory. Proving the same correctness property with one process and a thread pool is simpler to build, run, and verify, while still genuinely exercising real concurrent contention (multiple threads racing to claim from the same queue at the same time). Testing with actually-separate processes was considered out of scope given the added operational complexity for no additional correctness signal at this project's current scale.

This decision also simplifies the WebSocket worker-status design: with only ever one active `Worker` row broadcasting at a time, a dashboard subscribing to `/topic/workers` needs no worker-ID filtering logic to make sense of the stream.

---

## 11. WebSocket topic design: per-queue job topics, one global worker topic

**Decision:** job-status events broadcast to `/topic/queues/{queueId}/jobs` (scoped per queue), while worker-status events broadcast to a single global `/topic/workers`.

**Reasoning:** job events are naturally queue-scoped — a dashboard viewing one queue's job explorer only cares about that queue's jobs, and per-queue topics let Spring's simple broker route messages by exact destination string with no manual filtering needed client-side. Worker status, by contrast, is a system-wide operational concern rather than something scoped to any one queue, so a single global topic is the more natural fit.

**What triggers a broadcast, deliberately:** job status transitions (claimed, running, completed, retry-scheduled, dead-lettered, batch-resolved) and worker status transitions (active, unresponsive, shutdown). **Deliberately excluded:** per-job heartbeat ticks and aggregate queue-level statistics — both would fire far more frequently than an actual state change, and would risk flooding connected clients with low-value updates. Aggregate stats (throughput, status breakdown) are instead served via a periodically-polled REST endpoint, which also happens to double as the data source for the dashboard's Chart.js graphs.

---

## 12. Why broadcasting from inside `@Transactional` methods is safe

The WebSocket broadcast call itself is a fire-and-forget, in-memory operation with no transactional database resource to enlist in — calling it from inside a `@Transactional` method doesn't put it at risk of being rolled back, and it doesn't hold up the transaction.

This distinction mattered directly during the incident described in section 15: the broadcast call was never the source of that bug. The actual problem was a **database write** (a duplicate insert) happening earlier in the same transactional method — a genuinely transactional operation that, when it failed, correctly rolled back the whole method, including a status update that had already executed. This is a useful illustration that "the broadcast is safe" is a narrow, specific claim about one call — not a blanket statement that every operation inside the same method carries no risk.

---

## 13. Avoiding `@Transactional` self-invocation by design

Spring's `@Transactional` annotation works via a proxy — calling an `@Transactional` method from *within the same class* bypasses that proxy entirely and silently runs without any transaction at all. This is a well-documented but easy-to-trip pitfall.

**Decision:** transactional lifecycle operations (claim, mark-running, heartbeat update) live in a dedicated `JobLifecycleService` bean, called *externally* from the Worker Engine, rather than being `@Transactional` methods invoked from within the engine's own class. This was designed around proactively, before any concurrency test could have caught it as a runtime surprise — predicting the pitfall from the framework's known behavior, rather than discovering it via a failing test.

---

## 14. Dedicated database user and externalized secrets

The application connects to MySQL as a dedicated user scoped only to its own database — not as `root` — a defensible security practice regardless of project scale. No secrets are hardcoded: `application.properties` reads all sensitive values via `${VAR_NAME:default}` placeholders, with real values loaded from a git-ignored `.env` file at boot.

---

## 15. Incident: a duplicate `DeadLetterQueue` insert could permanently stick a job in `RUNNING` and crash the Reaper indefinitely

This is the most significant reliability bug found during development, and worth documenting in full because of what it reveals about failure modes in a stateful, retry-driven system.

**Root cause.** `dead_letter_queue.job_id` carries a unique constraint — by schema design, a job may only ever have one DLQ row, ever. The failure-handling code that creates that row, however, unconditionally called "create a new row" with no check for whether one already existed. A job that had been dead-lettered once, manually retried by an operator, and then failed again — exhausting its attempts a second time — hit exactly this case: an attempted second insert against a unique constraint that already had a value.

**Why this was significantly worse than "one failed insert."** The method responsible for this was `@Transactional`. When the duplicate-key exception was thrown partway through it, the *entire transaction rolled back* — including a `job.setStatus(DEAD_LETTER)` write that had already executed earlier in the same method. The job was left stuck in `RUNNING` status permanently.

Because the job remained `RUNNING` and was still past its heartbeat timeout, the **Reaper's very next poll rediscovered it as stale and repeated the identical failing sequence** — an infinite failure loop, logging a full stack trace on every poll interval indefinitely, until the process was restarted with a fix.

**Fix.** The failure-handling method now looks up any existing DLQ row for the job first, and updates it if found rather than always inserting a new one:

```java
DeadLetterQueue dlq = deadLetterQueueRepository.findByJobId(job.getId())
        .orElseGet(() -> {
            DeadLetterQueue fresh = new DeadLetterQueue();
            fresh.setJob(job);
            return fresh;
        });
dlq.setReason(reason + " (attempts exhausted: " + newAttemptCount + "/" + job.getMaxAttempts() + ")");
dlq.setMovedAt(LocalDateTime.now());
dlq.setRetriedManually(false); // fresh failure — reset so the DLQ view reflects "not yet retried since this dead-letter"
deadLetterQueueRepository.save(dlq);
```

`movedAt` is now set explicitly in code rather than relying on `@PrePersist` (which only fires on insert — this path may now be an *update*).

**Verification.** Confirmed fixed via a clean restart producing zero Reaper errors, where the identical restart, prior to the fix, had reliably reproduced the failure on every single boot (since the stuck job was still sitting in `RUNNING` in the database).

**Why this was caught at all.** This bug had been explicitly flagged as a known, unfixed limitation in a code comment before it was ever triggered — and it surfaced live during an unrelated exercise: deliberately force-killing a worker process mid-job to verify the Reaper's recovery behavior. This is a direct example of why "known limitation, not yet fixed" comments are worth taking seriously as live risk rather than filed-away documentation — and why deliberately exercising failure paths (killing processes, forcing errors) rather than only testing happy paths surfaces exactly this class of bug.

---

## 16. Incident: a global exception handler was silently swallowing unexpected exceptions

A `@RestControllerAdvice`-based global exception handler mapped several expected exception types to appropriate HTTP status codes (`IllegalArgumentException` → 400, `SecurityException` → 403, etc.), with a catch-all `Exception` handler returning a generic 500 for anything else.

**The problem:** the catch-all had no logging at all. Any exception type that wasn't explicitly mapped returned a clean `{"error": "An unexpected error occurred", "status": 500}` response with **zero console output** — making it look, from the server's own logs, like nothing had gone wrong at all.

This surfaced concretely twice:
- A malformed request header (a typo in `Content-Type`) triggered `HttpMediaTypeNotSupportedException`, an exception type with no specific handler — it fell into the silent catch-all, costing real debugging time before the actual cause was found via a different tool.
- Requesting a static frontend file that didn't exist yet (during frontend development) returned `500` instead of the expected `404`, because `NoResourceFoundException` also had no specific handler.

**Fix.** The catch-all handler now logs the full exception (`log.error(...)`) before returning its response — so any *future* unmapped exception type is at minimum immediately visible in the console, even if it isn't yet given its own specific status code. Two of the newly-visible exception types were then given proper handlers (`NoResourceFoundException` → 404, `HttpMessageNotReadableException` → 400).

**General lesson:** a broad catch-all exception handler is exactly the place logging matters most — not less. The entire purpose of a catch-all is to handle the *unexpected* cases, and those are precisely the ones where visibility into what actually happened is most valuable. A catch-all that silently returns a clean error response is actively worse than one that fails loudly, because it hides the fact that something needs attention at all.

---

## 17. Incident: a silent timezone mismatch corrupted delay/schedule/cron timing

The MySQL JDBC connection string specified `serverTimezone=UTC`, while the JVM itself ran in a different timezone (IST, UTC+5:30). This silently shifted every stored timestamp by 5.5 hours relative to the application's actual clock — with no error thrown anywhere. The effect was subtle and dangerous: delayed jobs fired at the wrong time, scheduled jobs appeared to be "not due yet" when they should have been claimable, and cron next-run calculations were off by a fixed offset.

**Fix.** Changed the connection string to `serverTimezone=Asia/Kolkata`, matching the JVM's actual clock.

**Why this class of bug matters more than a loud failure.** A bug that throws an exception announces itself immediately. A bug that silently shifts data by a fixed, plausible-looking offset can pass casual testing (numbers "look about right") while still being systematically wrong — and the only way to catch it is direct verification against the underlying data (querying the database directly, not just trusting API responses), which became a standing verification habit throughout the rest of development as a direct result of this incident.

---

## 18. Incident: an environment-variable loading library appeared broken for three separate development sessions

**Symptom, observed repeatedly:** a `.env`-loading library was added to the project so secrets wouldn't need to be hardcoded or manually set in an IDE's run configuration. It loaded onto the classpath without any error, but values from `.env` never actually reached the application's configuration — every property fell back to its hardcoded default, with no indication anything was wrong.

**The trap:** because the real `.env` values happened to match the application's own defaults, "the app boots successfully" was not actually proof that `.env` was being read — it was equally consistent with `.env` being silently ignored entirely and every default simply applying anyway. This went unnoticed across multiple separate development sessions, with the working theory recorded each time being a vague "probably a framework version incompatibility."

**Actual root cause, found via direct investigation of the library itself (rather than continuing to trust the carried-forward theory):** the library had been restructured into separate modules per major framework version — a generic module with no framework-specific wiring, plus dedicated modules for each specific version. The project had been depending on the generic module, which never had the newer framework's auto-integration wiring at all, *regardless of version compatibility in the abstract*. This fully explained the exact symptom: it loaded without error, and simply never activated.

**Fix.** Swapped to the version-specific module.

**Verified genuinely working, not a false positive.** Since real values matched defaults, a boot-success check alone would have proven nothing. The definitive test: set one specific value in `.env` **deliberately different from its code default**, with no other configuration source active, and confirm the running application reflects the `.env` value specifically — not the default. This is the only test that actually distinguishes "working" from "silently ignored but coincidentally fine."

**General lesson.** A "known limitation" that has been carried forward across multiple sessions without resolution is worth periodically re-investigating from first principles, rather than continuing to treat an unverified original theory as settled fact. Direct research into a dependency's actual current structure is more reliable than trusting an assumption inherited from an earlier, unverified diagnosis — however plausible that assumption sounds.

---

## 19. Frontend architecture: static files served by the same backend process

**Decision:** the dashboard (HTML/CSS/vanilla JS) lives under the backend's own static resources directory, served directly by its embedded web server. No separate frontend build tool, no separate dev server, no separate deployment artifact.

**Alternatives considered and rejected:**
- **A separate frontend dev server** (e.g. Node/Vite) — would require CORS configuration between two origins, two separate processes to run locally, and a separate build/deploy step, for no functional gain at this project's scale.
- **Server-rendered templates** — the backend is already API-first; every piece of data the frontend needs already has a clean REST or WebSocket path. Introducing server-rendered views would mean two different ways of producing UI in the same application, adding conceptual overhead without adding capability.

**Chosen: static resources served by the same process.** Zero extra infrastructure, same origin as the API and WebSocket endpoint (no CORS needed at all), and exactly one process to run or deploy.

A required consequence of this choice: the security configuration needed explicit `permitAll()` rules for the static file paths themselves (`/`, `/*.html`, `/css/**`, `/js/**`), since the browser requests these *before* a JWT exists — e.g. loading the login page in the first place.

---

## 20. Frontend authentication: JWT in `localStorage`, no refresh-token flow

A shared fetch wrapper injects `Authorization: Bearer <token>` on every API call, parses responses, and redirects to the login page on any `401`. The backend issues a single JWT with a fixed expiry at login time — no refresh-token rotation was implemented.

**Reasoning:** building a refresh-token flow solves a problem this project doesn't currently have at its scale — a short evaluation or demo session comfortably fits inside a single token's lifetime. Adding refresh-token complexity here would be solving for a production multi-day-session scenario the project isn't actually operating in, at the cost of meaningfully more moving parts. This is documented as a known, deliberate simplification (see the README's Known Limitations section) rather than an oversight.

---

## 21. Job creation form: iteratively simplified, not built to maximum flexibility

The dashboard's job-creation form went through several iterations: starting from a dynamic list of arbitrary key/value field rows (to build a JSON payload without requiring the user to type raw JSON), and ending at exactly two fixed fields — a task name and a single generic value.

**An "advanced raw JSON" option was explicitly considered and rejected.** The case for keeping it: nested objects/arrays aren't expressible via flat key/value rows. The case against, which won: this is a single-operator demo interface, not a multi-user product, and the simpler two-field form already covered every real test scenario actually exercised. Cutting the more flexible option kept both the markup and the JavaScript meaningfully simpler — a deliberate choice for simplicity over "more flexibility is always better," made explicitly rather than by default.

---

## 22. Queue-level charts computed entirely client-side, no new backend aggregation

The dashboard's two Chart.js visualizations (status breakdown, throughput) are both derived entirely from data the page was already polling for its stat-box row — no new backend endpoint or aggregation logic was introduced to support them.

Throughput specifically is computed as a client-side delta between consecutive polls (`completedCount(this poll) − completedCount(previous poll)`, clamped to a minimum of zero), with the very first poll after page load deliberately skipped rather than shown as a misleading spike representing every job the queue has ever completed. This is a real correctness detail in its own right, not just a null-check.

---

## 23. Dead Letter Queue view: filtered to currently-dead-lettered jobs, not "ever dead-lettered"

The DLQ list endpoint deliberately filters to jobs whose *current* status is still `DEAD_LETTER` — a job that was manually retried and has since moved to `QUEUED` (or been dead-lettered a second time) shouldn't clutter the active DLQ view with a stale, misleading duplicate entry from its first failure.

The manual retry action itself resets `attempt_count` to zero (a full fresh attempt budget, treated as a deliberate human decision rather than "undo the last failure") and reuses the exact same `RETRY_SCHEDULED` WebSocket event type the automatic retry path already broadcasts — meaning the live dashboard required zero new frontend code to reflect a manual retry in real time.

---

## Summary of what these decisions collectively demonstrate

- **Correctness under concurrency** is treated as a first-class design concern, not an afterthought — atomic claiming, transaction-boundary discipline, and self-invocation avoidance were all designed in deliberately rather than discovered as bugs.
- **A single source of truth per concern** (one class for failure handling, two reusable RBAC helpers, one shared fetch wrapper) consistently made real bugs cheaper to fix, because there was exactly one place to look.
- **Real bugs were found through deliberate, adversarial testing** — killing processes mid-job, testing with a value different from the default, testing negative/error paths — not just by reasoning that the code "looked correct."
- **Known limitations are documented explicitly rather than hidden**, and at least one of those documented limitations (the duplicate DLQ insert) turned out to be a live, previously-latent bug — reinforcing that a "known limitation" comment is a real risk to track, not just a caveat to file away.
