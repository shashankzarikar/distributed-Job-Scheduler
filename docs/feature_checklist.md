# Feature & Reliability Checklist

A concise, honest snapshot of what's implemented, what's been verified (and how), and what's explicitly out of scope. This exists so a reader can quickly confirm the system's coverage without having to read every other document or the source code first.

---

## Core functionality

| Area | Status | Notes |
|---|---|---|
| Multi-project, multi-queue management | ✅ Done | Per-project queues, each independently configurable |
| Five job types (immediate, delayed, scheduled, cron, batch) | ✅ Done | All five implemented; batch includes parent/child aggregation |
| Atomic, exactly-once job claiming | ✅ Done, load-tested | `SELECT ... FOR UPDATE SKIP LOCKED`, verified under real concurrent thread contention — see [Reliability & concurrency](#reliability--concurrency) below |
| Full job lifecycle (`QUEUED → CLAIMED → RUNNING → COMPLETED`) | ✅ Done | State machine enforced at the service layer |
| Configurable retry strategies (fixed / linear / exponential) | ✅ Done | Centralized in one class (`JobOutcomeHandler`) |
| Dead Letter Queue, including manual retry | ✅ Done | List + retry endpoints, automated test coverage, live-verified |
| Execution logs, retry history, metrics | ✅ Done | Per-attempt `JobExecution` rows, structured `job_logs` entries |
| Web dashboard (queues, jobs, workers, DLQ) | ✅ Done | Live-verified end-to-end via the real UI, not just built and assumed correct |

---

## Bonus / optional features

Three optional features were chosen for full, correct implementation rather than a larger number implemented shallowly:

| Feature | Status |
|---|---|
| WebSocket live updates | ✅ Done — every event type individually watched firing live at least once, not just reasoned about from code |
| Distributed locking (via `SKIP LOCKED`) | ✅ Done — same mechanism used by both the Worker Engine's claiming and the Scheduler's promotion |
| Role-based access control (Owner / Member / Viewer) | ✅ Done — enforced consistently via two shared service-layer helpers, reused by every service |

**Deliberately not implemented:** rate limiting, workflow dependencies between jobs, queue sharding, event-driven execution, AI-generated failure summaries. Each was a conscious scope decision to protect the depth and correctness of the core system, not an oversight.

---

## Reliability & concurrency

This is the area with the most concrete, verifiable evidence, since it was tested adversarially rather than just reasoned about:

- **Atomic claiming under real contention.** Multiple concurrent worker threads racing to claim from the same queue, verified to never produce a duplicate claim.
- **Reaper recovery from an actual killed process**, not a simulated one — a worker process was force-killed mid-execution of a job, with the heartbeat timeout temporarily shortened to make the test practical to run. The stale job and dead worker were both correctly detected and recovered on the next poll cycle.
- **A real, previously-latent bug was found and fixed as a direct result of that test** — a duplicate Dead Letter Queue insert that could permanently stick a job in `RUNNING` and crash the Reaper on every poll indefinitely. Full incident writeup in `docs/design_decisions.md` (section 15).
- **Batch aggregation under concurrent child completion**, confirming a batch parent resolves to the correct final status exactly once, with no duplicate or missed update.
- **Concurrency-limit enforcement**, confirming a queue's configured concurrency limit is never exceeded even under load.
- **Transaction-boundary discipline** applied consistently: every fetch-then-write sequence that depends on a row lock (claiming, promotion) happens inside one transaction, not split across two — a subtle but critical correctness detail, documented explicitly in `docs/design_decisions.md`. 

---

## Database design

- 13 tables, normalized, with explicit foreign keys, cascade rules, and indexes placed specifically to support the hot-path queries (job claiming, scheduled-job promotion).
- Full DDL is hand-written (not framework auto-generated), with the application validating its entity mappings against the real schema at boot rather than letting the framework silently create or alter tables.
- Full schema documentation, including an ER diagram, is in the main `README.md`.

---

## API design

- RESTful, resource-oriented endpoints across Auth, Projects, Queues, Jobs, Dead Letter Queue, and Workers.
- Consistent structured error responses with meaningful HTTP status codes across the board.
- Pagination and filtering on list endpoints.
- Full request/response reference with real examples: `docs/api_reference.md`. 

---

## Testing

| Script | Covers |
|---|---|
| `test/test_worker_engine.sh` | Immediate job success, guaranteed-failure → retry → DLQ, batch aggregation, concurrency-limit enforcement |
| `test/test_dlq.sh` | Full DLQ list + manual retry flow, including two negative-path checks (retrying a non-dead-lettered job, retrying a nonexistent job) |
| `test/test_scheduler.sh` | Delayed/scheduled job promotion timing, cron recurrence inserting a fresh row rather than reusing the original |
| `test/websocket_test.md` | A manual live-verification checklist for every WebSocket event type — deliberately manual, since a push-based real-time feed is better confirmed by direct observation than by a scripted assertion alone |

**Explicitly out of scope for automated scripts:** a JUnit-based N-threads-racing-to-claim-jobs test (the equivalent scenario was exercised manually instead), and any assertion requiring direct database CLI access in restricted environments.

---

## Documentation

| Document | Purpose |
|---|---|
| `README.md` | Entry point — what the system does, tech stack, schema, running instructions, testing instructions |
| `docs/project_overview.md` | Deep explanation of the system's purpose and end-to-end job flow |
| `docs/design_decisions.md` | Every significant decision, alternatives considered, and real incidents found during development |
| `docs/architecture.md` | Component diagram and layer-by-layer structural breakdown |
| `docs/api_reference.md` | Full endpoint and WebSocket event reference |
| `docs/feature_checklist.md` | This document |

---

## Known, explicitly documented limitations

Listed honestly rather than discovered by a reader — see the README's "Known Limitations" section for full detail:

- One unmapped exception type (`MethodArgumentTypeMismatchException`) still falls through to a generic 500, though it is now logged clearly rather than silently swallowed.
- The WebSocket handshake endpoint is not JWT-authenticated — an accepted simplification for this project's current single-tenant scope.
- Batch job responses don't yet expose live mid-batch child-progress counters, only the final resolved status.
- Only single-worker-process concurrency has been tested; multiple concurrent worker processes are architecturally supported (the claiming mechanism is process-agnostic) but not load-tested.
- Custom per-job retry limits aren't supported for delayed/scheduled/cron jobs specifically, due to a schema gap in the staging table — immediate and batch jobs do support this.
