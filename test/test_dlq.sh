#!/bin/bash
#
# test_dlq.sh — Automated test for the Dead Letter Queue endpoints
# (GET /api/queues/{queueId}/dead-letter-queue, POST /api/jobs/{jobId}/retry)
#
# Run this from Git Bash on Windows (per project note 3.22 — WSL2 cannot reach
# a Windows-hosted Spring Boot app by default). Requires: curl, jq.
#
#   Install jq on Git Bash if missing:
#     curl -L -o /usr/bin/jq.exe https://github.com/jqlang/jq/releases/latest/download/jq-windows-amd64.exe
#
# Usage:
#   BASE_URL=http://localhost:8080 ./test_dlq.sh
#

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() { echo -e "${GREEN}[PASS]${NC} $1"; PASS=$((PASS+1)); }
fail() { echo -e "${RED}[FAIL]${NC} $1"; FAIL=$((FAIL+1)); }
info() { echo -e "${YELLOW}[INFO]${NC} $1"; }

section() {
    echo ""
    echo "=============================================="
    echo " $1"
    echo "=============================================="
}

require_jq() {
    if ! command -v jq &> /dev/null; then
        echo -e "${RED}jq is required but not installed. See script header for install instructions.${NC}"
        exit 1
    fi
}
require_jq

# ---------- 0. Setup: register a fresh user, create project + queue ----------
section "0. Setup"

TS=$(date +%s)
EMAIL="dlqtest_${TS}@example.com"
PASSWORD="password123"
NAME="DLQ Test User"

REGISTER_RESPONSE=$(curl -s -X POST "$BASE_URL/api/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"$NAME\",\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")

TOKEN=$(echo "$REGISTER_RESPONSE" | jq -r '.token')

if [ "$TOKEN" == "null" ] || [ -z "$TOKEN" ]; then
    fail "User registration — could not obtain token. Response: $REGISTER_RESPONSE"
    echo -e "${RED}Aborting — cannot proceed without auth.${NC}"
    exit 1
else
    pass "User registration — token obtained ($EMAIL)"
fi

AUTH_HEADER="Authorization: Bearer $TOKEN"

PROJECT_RESPONSE=$(curl -s -X POST "$BASE_URL/api/projects" \
    -H "Content-Type: application/json" -H "$AUTH_HEADER" \
    -d "{\"name\":\"DLQ Test Project $TS\"}")
PROJECT_ID=$(echo "$PROJECT_RESPONSE" | jq -r '.id')

if [ "$PROJECT_ID" == "null" ] || [ -z "$PROJECT_ID" ]; then
    fail "Project creation — no id in response: $PROJECT_RESPONSE"
    exit 1
else
    pass "Project creation — id=$PROJECT_ID"
fi

QUEUE_RESPONSE=$(curl -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/queues" \
    -H "Content-Type: application/json" -H "$AUTH_HEADER" \
    -d "{\"name\":\"dlq-test-queue\",\"priority\":1,\"concurrencyLimit\":5}")
QUEUE_ID=$(echo "$QUEUE_RESPONSE" | jq -r '.id')

if [ "$QUEUE_ID" == "null" ] || [ -z "$QUEUE_ID" ]; then
    fail "Queue creation — no id in response: $QUEUE_RESPONSE"
    exit 1
else
    pass "Queue creation — id=$QUEUE_ID"
fi

# ---------- 1. DLQ list on an empty queue ----------
section "1. DLQ list — empty queue"

EMPTY_DLQ=$(curl -s -X GET "$BASE_URL/api/queues/$QUEUE_ID/dead-letter-queue" -H "$AUTH_HEADER")
EMPTY_DLQ_COUNT=$(echo "$EMPTY_DLQ" | jq 'length')

if [ "$EMPTY_DLQ_COUNT" == "0" ]; then
    pass "DLQ list returns empty array for a fresh queue"
else
    fail "Expected empty DLQ list, got: $EMPTY_DLQ"
fi

# ---------- 2. Create a job that will exhaust attempts and dead-letter ----------
section "2. Create a guaranteed-failure job (maxAttempts=1)"

# simulateFailure + maxAttempts=1 forces exactly one failed attempt, which
# immediately exhausts attempts and moves the job straight to DEAD_LETTER —
# see JobOutcomeHandler.applyFailure.
JOB_RESPONSE=$(curl -s -X POST "$BASE_URL/api/queues/$QUEUE_ID/jobs/immediate" \
    -H "Content-Type: application/json" -H "$AUTH_HEADER" \
    -d '{"payload":{"task":"dlq-test-job","simulateFailure":true},"maxAttempts":1}')
JOB_ID=$(echo "$JOB_RESPONSE" | jq -r '.id')

if [ "$JOB_ID" == "null" ] || [ -z "$JOB_ID" ]; then
    fail "Job creation — no id in response: $JOB_RESPONSE"
    exit 1
else
    pass "Job creation — id=$JOB_ID, maxAttempts=1, simulateFailure=true"
fi

# ---------- 3. Poll until the Worker Engine dead-letters it ----------
section "3. Wait for Worker Engine to move job to DEAD_LETTER"

MAX_WAIT_SECONDS=30
WAITED=0
JOB_STATUS=""

while [ "$WAITED" -lt "$MAX_WAIT_SECONDS" ]; do
    JOBS_PAGE=$(curl -s -X GET "$BASE_URL/api/queues/$QUEUE_ID/jobs?size=50" -H "$AUTH_HEADER")
    JOB_STATUS=$(echo "$JOBS_PAGE" | jq -r --arg id "$JOB_ID" '.content[] | select(.id == ($id | tonumber)) | .status')

    if [ "$JOB_STATUS" == "DEAD_LETTER" ]; then
        break
    fi

    sleep 2
    WAITED=$((WAITED+2))
    info "Waiting for job $JOB_ID to dead-letter... current status: ${JOB_STATUS:-unknown} (${WAITED}s elapsed)"
done

if [ "$JOB_STATUS" == "DEAD_LETTER" ]; then
    pass "Job reached DEAD_LETTER status within ${WAITED}s"
else
    fail "Job did not reach DEAD_LETTER within ${MAX_WAIT_SECONDS}s (last observed status: $JOB_STATUS)"
fi

# ---------- 4. DLQ list should now contain this job ----------
section "4. DLQ list — job should appear"

DLQ_AFTER_FAILURE=$(curl -s -X GET "$BASE_URL/api/queues/$QUEUE_ID/dead-letter-queue" -H "$AUTH_HEADER")
DLQ_ENTRY=$(echo "$DLQ_AFTER_FAILURE" | jq --arg id "$JOB_ID" '.[] | select(.jobId == ($id | tonumber))')

if [ -n "$DLQ_ENTRY" ]; then
    pass "Job $JOB_ID found in DLQ list"

    RETRIED_MANUALLY=$(echo "$DLQ_ENTRY" | jq -r '.retriedManually')
    if [ "$RETRIED_MANUALLY" == "false" ]; then
        pass "DLQ entry retriedManually is false before any retry"
    else
        fail "Expected retriedManually=false before retry, got: $RETRIED_MANUALLY"
    fi

    REASON=$(echo "$DLQ_ENTRY" | jq -r '.reason')
    if [ -n "$REASON" ] && [ "$REASON" != "null" ]; then
        pass "DLQ entry has a non-empty reason: \"$REASON\""
    else
        fail "DLQ entry reason is missing/null"
    fi
else
    fail "Job $JOB_ID NOT found in DLQ list. Full response: $DLQ_AFTER_FAILURE"
fi

# ---------- 5. Manual retry ----------
section "5. Manual retry via POST /api/jobs/{jobId}/retry"

RETRY_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/jobs/$JOB_ID/retry" -H "$AUTH_HEADER")
RETRY_HTTP_CODE=$(echo "$RETRY_RESPONSE" | tail -n1)
RETRY_BODY=$(echo "$RETRY_RESPONSE" | sed '$d')

if [ "$RETRY_HTTP_CODE" == "200" ]; then
    pass "Retry endpoint returned 200 OK"
else
    fail "Retry endpoint returned $RETRY_HTTP_CODE, expected 200. Body: $RETRY_BODY"
fi

RETRY_STATUS=$(echo "$RETRY_BODY" | jq -r '.status')
RETRY_ATTEMPTS=$(echo "$RETRY_BODY" | jq -r '.attemptCount')

if [ "$RETRY_STATUS" == "QUEUED" ]; then
    pass "Job status reset to QUEUED after retry"
else
    fail "Expected status QUEUED after retry, got: $RETRY_STATUS"
fi

if [ "$RETRY_ATTEMPTS" == "0" ]; then
    pass "Job attemptCount reset to 0 after retry"
else
    fail "Expected attemptCount 0 after retry, got: $RETRY_ATTEMPTS"
fi

# ---------- 6. DLQ list should no longer show this job as active ----------
section "6. DLQ list — job should disappear after retry"

DLQ_AFTER_RETRY=$(curl -s -X GET "$BASE_URL/api/queues/$QUEUE_ID/dead-letter-queue" -H "$AUTH_HEADER")
DLQ_ENTRY_AFTER=$(echo "$DLQ_AFTER_RETRY" | jq --arg id "$JOB_ID" '.[] | select(.jobId == ($id | tonumber))')

if [ -z "$DLQ_ENTRY_AFTER" ]; then
    pass "Job $JOB_ID no longer appears in the active DLQ list (status is QUEUED, not DEAD_LETTER)"
else
    fail "Job $JOB_ID still appears in DLQ list after retry. Entry: $DLQ_ENTRY_AFTER"
fi

# ---------- 7. Negative test: retrying a non-DEAD_LETTER job should fail ----------
section "7. Negative test — retrying an already-QUEUED job should be rejected"

INVALID_RETRY=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/jobs/$JOB_ID/retry" -H "$AUTH_HEADER")
INVALID_RETRY_CODE=$(echo "$INVALID_RETRY" | tail -n1)

if [ "$INVALID_RETRY_CODE" == "409" ]; then
    pass "Retrying a non-DEAD_LETTER job correctly returns 409 Conflict"
else
    fail "Expected 409 for retrying a non-DEAD_LETTER job, got $INVALID_RETRY_CODE"
fi

# ---------- 8. Negative test: retrying a nonexistent job ----------
section "8. Negative test — retrying a nonexistent jobId"

NONEXISTENT_RETRY=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/jobs/999999999/retry" -H "$AUTH_HEADER")
NONEXISTENT_CODE=$(echo "$NONEXISTENT_RETRY" | tail -n1)

if [ "$NONEXISTENT_CODE" == "400" ]; then
    pass "Retrying a nonexistent jobId correctly returns 400"
else
    fail "Expected 400 for nonexistent jobId, got $NONEXISTENT_CODE"
fi

# ---------- Known limitation note ----------
section "Known limitation (not tested here — see JobService.retryDeadLetterJob javadoc)"
info "This script deliberately does NOT wait for the retried job's next outcome."
info "Since the job's payload still has simulateFailure=true and maxAttempts=1,"
info "the Worker Engine will fail it again and attempt a second DeadLetterQueue"
info "insert for the same job_id, which violates the unique constraint on"
info "dead_letter_queue.job_id. This is a pre-existing gap in JobOutcomeHandler,"
info "flagged as a follow-up, not something this test suite covers."

# ---------- Summary ----------
section "Summary"
echo -e "${GREEN}Passed: $PASS${NC}"
echo -e "${RED}Failed: $FAIL${NC}"

if [ "$FAIL" -eq 0 ]; then
    echo -e "${GREEN}ALL TESTS PASSED${NC}"
    exit 0
else
    echo -e "${RED}SOME TESTS FAILED${NC}"
    exit 1
fi
