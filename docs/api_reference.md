# API Reference

Base URL (local): `http://localhost:8080`

All endpoints except `/api/auth/**`, the WebSocket handshake (`/ws/**`), and static frontend files (`/`, `/*.html`, `/css/**`, `/js/**`) require:

```
Authorization: Bearer <jwt-token>
```

Error responses follow a consistent structure across the API:
```json
{ "error": "Human-readable message", "status": 400 }
```

---

## Table of contents

- [Auth](#auth)
- [Projects](#projects)
- [Queues](#queues)
- [Jobs](#jobs)
- [Dead Letter Queue](#dead-letter-queue)
- [Workers](#workers)
- [WebSocket events](#websocket-events)

---

## Auth

### `POST /api/auth/register`
Request:
```json
{
  "name": "Jane Doe",
  "email": "jane@example.com",
  "password": "password123"
}
```
Response `201 Created`:
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "userId": 1,
  "name": "Jane Doe",
  "email": "jane@example.com"
}
```

### `POST /api/auth/login`
Request:
```json
{ "email": "jane@example.com", "password": "password123" }
```
Response `200 OK`: same shape as register.

---

## Projects

### `POST /api/projects`
Creates a project and makes the caller its Owner.

Request:
```json
{ "name": "My Project" }
```
Response `201 Created`:
```json
{
  "id": 1,
  "name": "My Project",
  "organizationId": 1,
  "yourRole": "OWNER",
  "createdAt": "2026-08-22T10:00:00"
}
```

### `GET /api/projects`
Response `200 OK`: array of the same shape as above, for every project the caller is a member of.

### `POST /api/projects/{projectId}/members`
Requires **OWNER** role.

Request:
```json
{ "email": "teammate@example.com", "role": "MEMBER" }
```
Response `201 Created`:
```json
{
  "userId": 2,
  "name": "Teammate Name",
  "email": "teammate@example.com",
  "role": "MEMBER"
}
```

### `GET /api/projects/{projectId}/members`
Requires any membership. Response `200 OK`: array of member objects.

---

## Queues

### `POST /api/projects/{projectId}/queues`
Requires **MEMBER** role.

Request:
```json
{
  "name": "email-notifications",
  "priority": 1,
  "concurrencyLimit": 5,
  "retryPolicyId": null
}
```
Response `201 Created`:
```json
{
  "id": 1,
  "projectId": 1,
  "name": "email-notifications",
  "priority": 1,
  "concurrencyLimit": 5,
  "retryPolicyId": null,
  "status": "ACTIVE",
  "createdAt": "2026-08-22T10:05:00"
}
```

### `GET /api/projects/{projectId}/queues`
Requires any membership. Response `200 OK`: array of queues.

### `PATCH /api/queues/{queueId}/pause`
Requires **MEMBER** role. Sets queue status to `PAUSED` — a paused queue's jobs are not claimed by the Worker Engine.

### `PATCH /api/queues/{queueId}/resume`
Requires **MEMBER** role. Sets queue status back to `ACTIVE`.

### `GET /api/queues/{queueId}/stats`
Requires **VIEWER** role (or higher).

Response `200 OK`:
```json
{
  "queueId": 1,
  "queuedCount": 4,
  "runningCount": 2,
  "completedCount": 130,
  "failedCount": 0,
  "deadLetterCount": 3
}
```
This is the endpoint the dashboard polls every 5 seconds to feed both the stat-box row and the Chart.js graphs (status breakdown, throughput).

---

## Jobs

All job endpoints are under `/api/queues/{queueId}/jobs/...`. Creating a job requires **MEMBER** role; listing requires **VIEWER** role.

### `POST /api/queues/{queueId}/jobs/immediate`
Request (minimal):
```json
{ "payload": { "task": "send-welcome-email", "userId": 42 } }
```
Optional fields: `priority` (falls back to the queue's own priority if omitted), `maxAttempts` (defaults to 5), `idempotencyKey` (globally unique if provided).

Response `201 Created`:
```json
{
  "id": 835,
  "queueId": 1,
  "parentJobId": null,
  "type": "IMMEDIATE",
  "status": "QUEUED",
  "payload": { "task": "send-welcome-email", "userId": 42 },
  "priority": 1,
  "attemptCount": 0,
  "maxAttempts": 5,
  "runAfter": "2026-08-24T08:30:00",
  "idempotencyKey": null,
  "createdAt": "2026-08-24T08:30:00",
  "updatedAt": "2026-08-24T08:30:00"
}
```
The job then transitions `CLAIMED → RUNNING → COMPLETED` (or `DEAD_LETTER`) automatically, driven by the Worker Engine. Each transition fires a live WebSocket event on `/topic/queues/{queueId}/jobs` — see [WebSocket events](#websocket-events).

### `POST /api/queues/{queueId}/jobs/delayed`
Same payload shape as immediate, plus a required delay. Writes to the `scheduled_jobs` staging table (response type is a `ScheduledJobResponse`, not a `JobResponse`); the job doesn't appear in `jobs` — or in the job list — until the Scheduler promotes it. `idempotencyKey` and `maxAttempts` are not supported for this job type (see design_decisions.md, decision 3).

### `POST /api/queues/{queueId}/jobs/scheduled`
Same staging behavior as delayed, but triggered by an absolute timestamp rather than a relative delay.

### `POST /api/queues/{queueId}/jobs/cron`
Same staging behavior, triggered by a cron expression. On each promotion, the Scheduler computes the next occurrence and inserts a fresh `scheduled_jobs` row for it — the original row is never reused or mutated in place.

### `POST /api/queues/{queueId}/jobs/batch`
Creates one parent job (`type: BATCH`) plus N child jobs (`type: IMMEDIATE`, `parentJobId` set), all in a single transactional request.

Request:
```json
{
  "children": [
    { "payload": { "task": "process-record", "recordId": 1 } },
    { "payload": { "task": "process-record", "recordId": 2 } },
    { "payload": { "task": "process-record", "recordId": 3 } }
  ]
}
```
Response `201 Created`: the parent `JobResponse`, with `type: BATCH` and `totalChildren: 3`. Children resolve independently; the parent's status is derived once all children resolve (`COMPLETED` if all succeeded, `PARTIALLY_FAILED` if any hit the DLQ), and a `BATCH_RESOLVED` WebSocket event fires exactly once.

### `GET /api/queues/{queueId}/jobs?status={optional}&page={optional}&size={optional}`
Requires **VIEWER** role. Defaults to sorting `createdAt DESC`. Response is a paginated `Page<JobResponse>`. Only shows immediate and batch jobs directly, plus delayed/scheduled/cron jobs once they've been promoted — this is expected, not a bug (see design_decisions.md, decision 3).

---

## Dead Letter Queue

### `GET /api/queues/{queueId}/dead-letter-queue`
Requires **VIEWER** role (or higher). Lists jobs whose **current** status is still `DEAD_LETTER` — a job that was manually retried and has since moved on doesn't appear here (see design_decisions.md, decision 23).

Response `200 OK`:
```json
[
  {
    "id": 12,
    "jobId": 4238,
    "queueId": 3,
    "jobType": "IMMEDIATE",
    "payload": { "task": "process-payment", "simulateFailure": true },
    "attemptCount": 1,
    "maxAttempts": 1,
    "reason": "Simulated failure (job id=4238) (attempts exhausted: 1/1)",
    "movedAt": "2026-08-26T01:47:13.258",
    "retriedManually": false
  }
]
```

### `POST /api/jobs/{jobId}/retry`
Requires **MEMBER** role on the job's parent project. Manually retries a dead-lettered job with a full fresh attempt budget.

Behavior:
- Resets `status → QUEUED`, `attemptCount → 0`, `claimedByWorker → null`, `runAfter → now`.
- Marks the existing DLQ row's `retriedManually → true`.
- Broadcasts a `RETRY_SCHEDULED` event — the same event type the automatic retry path uses.

Response `200 OK` (a `JobResponse`):
```json
{
  "id": 4238,
  "queueId": 3,
  "parentJobId": null,
  "type": "IMMEDIATE",
  "status": "QUEUED",
  "payload": { "task": "process-payment", "simulateFailure": true },
  "priority": 1,
  "attemptCount": 0,
  "maxAttempts": 1,
  "runAfter": "2026-08-26T02:00:00",
  "idempotencyKey": null,
  "createdAt": "2026-08-24T08:37:03",
  "updatedAt": "2026-08-26T02:00:00"
}
```

Error responses:
- `409 Conflict` if the job's current status isn't `DEAD_LETTER`:
  `{"error": "Only jobs in DEAD_LETTER status can be manually retried (current status: QUEUED)", "status": 409}`
- `400 Bad Request` if the job ID doesn't exist:
  `{"error": "Job not found: 999999999", "status": 400}`

---

## Workers

### `GET /api/workers`
Requires standard JWT authentication, no RBAC gating (treated as a system-wide operational view, consistent with the WebSocket worker topic being open to any authenticated client).

Response `200 OK`:
```json
[
  {
    "id": 22,
    "name": "worker-abc12345",
    "status": "ACTIVE",
    "lastHeartbeatAt": "2026-08-26T01:52:44.000",
    "startedAt": "2026-08-26T01:52:39.258"
  }
]
```

---

## WebSocket events

**Handshake endpoint:** `ws://localhost:8080/ws` (SockJS fallback also available over plain HTTP). Protocol is STOMP over SockJS. Not JWT-authenticated — see the README's Known Limitations section.

Example client connection pattern:
```javascript
const sock = new SockJS('/ws');
const stompClient = Stomp.over(sock);
stompClient.connect({}, () => {
    stompClient.subscribe(`/topic/queues/${queueId}/jobs`, (message) => {
        const evt = JSON.parse(message.body);
        // patch the matching row in the job table, or insert a new one
    });
}, () => {
    // connection lost — auto-reconnect after 3s
    setTimeout(connectWebSocket, 3000);
});
```

### Topic: `/topic/queues/{queueId}/jobs`

Payload shape (`JobEventMessage`):
```json
{
  "jobId": 835,
  "queueId": 1,
  "parentJobId": null,
  "type": "IMMEDIATE",
  "status": "RUNNING",
  "eventType": "RUNNING",
  "attemptCount": 0,
  "maxAttempts": 5,
  "workerId": 3,
  "detail": null,
  "timestamp": "2026-08-24T08:30:02.114"
}
```

| `eventType` | Fires when |
|---|---|
| `CLAIMED` | A worker atomically claims the job |
| `RUNNING` | The job transitions from claimed to actively executing |
| `COMPLETED` | Execution succeeds |
| `RETRY_SCHEDULED` | Execution fails with attempts remaining, or a manual DLQ retry occurs |
| `DEAD_LETTER` | Execution fails and all attempts are exhausted |
| `BATCH_RESOLVED` | A batch parent's last child resolves (fires exactly once per batch) |

### Topic: `/topic/workers`

Payload shape:
```json
{
  "workerId": 3,
  "workerName": "worker-a1b2c3d4",
  "status": "ACTIVE",
  "timestamp": "2026-08-24T08:00:00.000"
}
```

| `status` | Fires when |
|---|---|
| `ACTIVE` | The worker process starts up |
| `UNRESPONSIVE` | The Reaper detects a stale heartbeat from this worker |
| `SHUTDOWN` | The worker process shuts down gracefully |

**Deliberately not broadcast:** per-job heartbeat ticks (would fire far too frequently to be useful) and aggregate queue statistics (served instead via the polled `GET /api/queues/{id}/stats` endpoint).
