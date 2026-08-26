# Project Overview — Distributed Job Scheduler

**A production-inspired distributed job scheduling platform, built solo to a real engineering standard rather than a minimal working demo.**

This document explains what the system is, why it's built the way it is, and how its major parts work together end to end. It's meant to be read by someone evaluating the project who wants real understanding — not just a feature list — without having to read the source code first.

---

## 1. What problem this solves

Modern applications constantly need to do work *outside* the request/response cycle: send an email after signup, generate a report on a schedule, retry a flaky payment webhook, process a batch of uploaded records. Doing this naively — spawning a thread inline, or trusting an in-memory queue — falls apart under real conditions: the process crashes mid-job, two workers grab the same task, a transient failure needs a retry policy instead of an immediate give-up, and nobody can see what's actually happening across hundreds of in-flight jobs.

This project is a smaller, self-built analogue of systems like **Sidekiq**, **Celery**, or **AWS SQS + Lambda** — a backend service that lets client applications submit background jobs, guarantees each job runs *exactly once* even with multiple concurrent workers, handles failures with configurable retry policies, and gives full visibility into the system through a live dashboard.

The project deliberately prioritizes **architecture, database design, backend engineering, and reliability/concurrency** over raw feature count — this is built as a "prove one system works correctly under real failure conditions" project, not a "ship as many features as possible" one. Every design decision below follows from that priority.

---

## 2. Core capabilities

- **Multi-project, multi-queue management.** A user can belong to multiple projects; each project owns multiple job queues, each independently configurable (priority, concurrency limit, retry policy, pause/resume).
- **Five job types.** Immediate (run now), delayed (run after N seconds), scheduled (run at a specific timestamp), recurring/cron (run on a cron expression, repeatedly), and batch (a parent job fanning out into N independent children).
- **Atomic, exactly-once job claiming.** A pool of concurrent worker threads polls queues and claims jobs such that no job is ever picked up by two workers simultaneously — the core reliability guarantee of the whole system.
- **Full job lifecycle with retries and a Dead Letter Queue.** Jobs move through `QUEUED → CLAIMED → RUNNING → COMPLETED`, with configurable fixed/linear/exponential backoff on failure, and a **Dead Letter Queue (DLQ)** for jobs that exhaust every retry attempt — including a manual "retry from DLQ" action for an operator.
- **Dead-worker detection (the Reaper).** A dedicated background task detects workers that stop sending heartbeats (crashed, killed, network-partitioned) and safely recovers their in-flight jobs back into the normal retry/DLQ flow — this is a distinct failure mode from a *job* failing, and the system handles both.
- **Role-based access control.** Three roles per project — Owner, Member, Viewer — enforced consistently at the service layer.
- **Live dashboard.** A browser-based UI showing projects, queues, a real-time job explorer, worker status, and the DLQ, all updated live via WebSocket without polling for state changes.

---

## 3. How a job actually moves through the system (end to end)

Understanding this one flow explains most of the architecture.

1. **Submission.** A client calls a REST endpoint (e.g. `POST /api/queues/{id}/jobs/immediate`) with a JSON payload. For immediate and batch jobs, a row is written directly into the `jobs` table with status `QUEUED`. For delayed/scheduled/cron jobs, a row is written instead into a separate `scheduled_jobs` staging table with a `next_run_time` and `promoted = false` — these jobs don't exist in `jobs` yet at all.

2. **Promotion (delayed/scheduled/cron only).** A dedicated **Scheduler** component polls `scheduled_jobs` on an interval, looking for rows where `next_run_time <= NOW()` and `promoted = false`. When it finds one, it inserts a fresh row into `jobs` with status `QUEUED` — using `SELECT ... FOR UPDATE SKIP LOCKED` so multiple scheduler polls (or future multi-instance deployments) can't double-promote the same row. For recurring cron jobs, after promoting, it computes the *next* run time and inserts a **brand-new** `scheduled_jobs` row rather than reusing the old one, keeping a clean, auditable promotion history.

3. **Claiming.** The **Worker Engine** — a pool of concurrent threads inside a single worker process — continuously polls active queues. Each poll runs `SELECT ... FOR UPDATE SKIP LOCKED` against `jobs`, locking the next eligible row (respecting priority, `run_after`, and the queue's concurrency limit) while *skipping* any row already locked by another concurrent poll. The claim (row lock → status update to `CLAIMED`, `claimed_by_worker_id`, `claimed_at`) happens inside one transaction, so the lock is still held when the write happens — this is what actually guarantees exactly-once claiming, not just the `SKIP LOCKED` syntax alone.

4. **Execution.** The claimed job transitions to `RUNNING`. The worker thread executes it, sending periodic heartbeats (`last_heartbeat_at`) for as long as it runs. Every attempt is recorded as a `JobExecution` row (worker, timestamps, outcome), and structured log lines are written to `job_logs`.

5. **Outcome — success.** On success, the job moves to `COMPLETED`. If it's a batch child, the parent's `completed_children` counter is updated transactionally, and once every child has resolved, the parent's final status (`COMPLETED` / `FAILED` / `PARTIALLY_FAILED`) is derived and it fires its own `BATCH_RESOLVED` event.

6. **Outcome — failure.** On failure, one class (`JobOutcomeHandler`) decides what happens next based on the job's retry policy (fixed / linear / exponential backoff) and `attempt_count` vs `max_attempts`:
   - If attempts remain: the job returns to `QUEUED` with a computed future `run_after`, and a `RETRY_SCHEDULED` event fires.
   - If attempts are exhausted: the job moves to `DEAD_LETTER`, a row is written to `dead_letter_queue` with a reason, and a `DEAD_LETTER` event fires.

7. **Outcome — worker died mid-job.** Separately, the **Reaper** — a second scheduled task, polling on its own interval — looks for jobs stuck in `RUNNING` whose `last_heartbeat_at` is older than a configured timeout. When it finds one, it marks the owning worker `UNRESPONSIVE` and routes the job through the **exact same** `JobOutcomeHandler` used for a normal execution failure — so a crashed worker and a job that simply threw an exception are handled by one unified piece of retry/DLQ logic, not two parallel implementations that could drift apart.

8. **Visibility.** Every state transition above (claim, run, complete, retry, dead-letter, batch resolution, worker status change) is broadcast over WebSocket to any dashboard subscribed to that queue's topic or the global worker topic — so an operator watching `queue.html` sees the job's row update live, with no page refresh and no polling for state changes specifically (aggregate stats like throughput are polled separately by design, to avoid flooding the socket with high-frequency non-state-change data).

9. **Manual recovery.** If a job ends up in the DLQ, an operator can click "Retry" in the dashboard, which resets it to `QUEUED` with a full fresh attempt budget (`attempt_count = 0`) — a deliberate human decision to give it another full run, not merely "undo the last failure."

---

## 4. Why the architecture is shaped this way

**Why no external message broker (Redis/RabbitMQ/Kafka)?** The atomic claiming guarantee comes entirely from MySQL's `SELECT ... FOR UPDATE SKIP LOCKED`, combined with correct transaction boundaries. This mirrors the approach used by real production job-queue libraries like Oban (Elixir) and GoodJob (Ruby) — using the relational database itself as the coordination point, rather than introducing a second stateful system whose failure modes would then also need to be reasoned about. For this project's scale, that's a meaningfully simpler and equally reliable choice.

**Why one worker process with a thread pool, rather than multiple worker processes?** The concurrency-safety guarantee (`SKIP LOCKED`) is process-agnostic — it would work identically with five separate worker processes. But demonstrating it with one process and a configurable `ExecutorService` thread pool proves the same correctness property with less operational complexity, and keeps the WebSocket worker-status story simple (one active worker broadcasting at a time, no cross-instance coordination to reason about at this project's current scope).

**Why a separate Scheduler and a separate Reaper, instead of one background task doing everything?** They solve genuinely different problems on genuinely different timescales — promoting due jobs is "is it time yet?", while reaping is "has this worker gone silent?" — and giving them independent poll intervals means each can be tuned without affecting the other (the Reaper, for instance, deliberately does not reuse the heartbeat-timeout window as its own poll interval, so detection isn't tied to querying frequency).

**Why route Reaper-recovered jobs through the same `JobOutcomeHandler` as normal failures?** Centralizing "what happens when a job fails, for any reason" in one class means there is exactly one place in the codebase that decides retry-vs-DLQ, and exactly one place that ever creates a `DeadLetterQueue` row. This paid off directly during development — a real bug (a duplicate DLQ insert crashing the Reaper indefinitely) was fully diagnosed and fixed by editing one method in one file, specifically *because* of this centralization, not by hunting across parallel failure-handling code paths.

**Why a two-row staging model for delayed/scheduled/cron jobs, instead of one `jobs` table with a future `run_after`?** It keeps the live `jobs` table — the one every worker poll scans — free of large volumes of not-yet-relevant future work, and it gives cron jobs a clean, auditable trail of "this recurrence was promoted, then this next one was scheduled," rather than one row being silently mutated in place across many future runs.

For the full reasoning behind these choices — including alternatives that were considered and rejected, and every real bug found during development — see `docs/design-decisions.md`. For the visual component breakdown, see `docs/architecture.md`.

---

## 5. Technology choices, briefly

| Layer | Choice | Why (short version) |
|---|---|---|
| Backend | Java 23, Spring Boot 4 | Deepest existing expertise; reduces implementation risk while keeping full control over concurrency primitives |
| Database | MySQL 8.0+ | Supports `SELECT ... FOR UPDATE SKIP LOCKED` and native JSON columns — meets every relational design requirement (PKs/FKs/indexes/normalization/cascades); chosen over Postgres purely for familiarity, not capability |
| Persistence | Spring Data JPA + Hibernate | Explicit, code-level entity relationships that double as living schema documentation |
| Security | Spring Security + JWT | Stateless auth; RBAC is layered on top at the service level rather than via Spring's global role system (see design-decisions.md, 3.6) |
| Realtime | Spring WebSocket (STOMP/SockJS) | Live dashboard updates without polling for state changes |
| Frontend | HTML/CSS/vanilla JS + Chart.js, served by the same Spring Boot app | No separate frontend server, no build step, no CORS — kept deliberately simple since the backend/reliability work is the core focus of this project |

Full reasoning for each of these — including options considered and rejected — is in `docs/design-decisions.md`.

---

## 6. What "done" looks like for this project

By design, this project favors a **small number of fully-correct, fully-verified features** over a shallow implementation of every optional item on the original feature wishlist. Concretely:

- All five job types work, including batch fan-out/aggregation.
- Atomic claiming has been tested under real concurrent load (not just reasoned about).
- Every retry strategy (fixed/linear/exponential) is implemented and unit-testable.
- The Reaper has been verified against a **real** killed process, not just a simulated one — and that test uncovered and led to fixing a genuine, previously-latent bug.
- Every WebSocket event type has been individually watched firing live in a browser, not just assumed correct because the code looks right.
- Three optional bonus features (WebSocket live updates, distributed locking via `SKIP LOCKED`, RBAC) are implemented in full, rather than a larger number implemented partially.

This reflects a deliberate philosophy: engineering quality and reliability over feature count.
