#!/usr/bin/env bash
#
# test_worker_engine.sh
#
# Automated end-to-end test of the Worker Engine (Step F):
#   Test 1 - Immediate job, guaranteed success
#   Test 2 - Guaranteed failure -> retries -> Dead Letter Queue
#   Test 3 - Heartbeat updates during a long-running job
#   Test 4 - Batch job aggregation (parent counters + derived status)
#   Test 5 - Concurrency limit is respected (never exceeds queue.concurrencyLimit)
#   Test 6 - Batch parent is never itself claimed/executed
#
# Test 7 (Reaper) is NOT automated here on purpose - it requires killing the app
# mid-execution, which isn't something a script running IN this same process can do
# to itself cleanly. Do that one manually per the step-by-step guide.
#
# Requires: curl, jq. Optionally: mysql CLI for the deeper DB-level checks
# (job_executions, dead_letter_queue, workers, job_logs aren't exposed via any API
# endpoint yet, so those specific checks are skipped gracefully if `mysql` isn't found).
#
# Run from Git Bash on Windows (see decision 3.22 in the project state doc - WSL2
# can't reach a Windows-hosted Spring Boot app by default on this machine).

set -uo pipefail

# ============================================================
# CONFIG - edit these before running
# ============================================================
BASE_URL="${BASE_URL:-http://localhost:8080}"

# An existing registered user who is at least MEMBER on the project that owns QUEUE_ID
EMAIL="${EMAIL:-test@example.com}"
PASSWORD="${PASSWORD:-password123}"

# Queue to run all tests against - must already exist (created via Day 1 Queue APIs)
QUEUE_ID="${QUEUE_ID:-1}"

# Set this to the queue's actual concurrency_limit (from the queues table / create response)
# so Test 5 knows what ceiling to check against.
CONCURRENCY_LIMIT="${CONCURRENCY_LIMIT:-5}"

# Optional direct-MySQL checks (job_executions / dead_letter_queue / workers / job_logs
# have no REST endpoints yet, so these are the only way to verify them automatically)
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-job_scheduler}"
DB_USER="${DB_USER:-job_scheduler_user}"
DB_PASS="${DB_PASS:-}"

# ============================================================
# Setup
# ============================================================
PASS=0
FAIL=0
RESULTS=()

command -v jq >/dev/null 2>&1 || { echo "jq is required but not found on PATH. Install it and re-run."; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "curl is required but not found on PATH."; exit 1; }

MYSQL_AVAILABLE=false
if command -v mysql >/dev/null 2>&1 && [ -n "$DB_PASS" ]; then
    MYSQL_AVAILABLE=true
fi

pass() { echo "  [PASS] $1"; RESULTS+=("PASS - $1"); PASS=$((PASS+1)); }
fail() { echo "  [FAIL] $1"; RESULTS+=("FAIL - $1"); FAIL=$((FAIL+1)); }
info() { echo "  [INFO] $1"; }

mysql_query() {
    # Usage: mysql_query "SELECT ..."
    mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p"$DB_PASS" "$DB_NAME" -N -B -e "$1" 2>/dev/null
}

# ============================================================
# Auth
# ============================================================
echo "=== Logging in as $EMAIL ==="
LOGIN_RESPONSE=$(curl -s -X POST "$BASE_URL/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")

TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.token // empty')

if [ -z "$TOKEN" ]; then
    echo "Login failed. Response was:"
    echo "$LOGIN_RESPONSE"
    exit 1
fi
echo "Logged in OK."
echo

AUTH_HEADER="Authorization: Bearer $TOKEN"

# ============================================================
# Helpers
# ============================================================

# create_job <endpoint suffix e.g. "immediate"> <json body> -> echoes the created id
create_job() {
    local endpoint="$1"
    local body="$2"
    local response
    response=$(curl -s -X POST "$BASE_URL/api/queues/$QUEUE_ID/jobs/$endpoint" \
        -H "$AUTH_HEADER" -H "Content-Type: application/json" \
        -d "$body")
    echo "$response" | jq -r '.id // empty'
}

# get_job_status <id> -> echoes status string, or "" if not found on the first page
get_job_status() {
    local id="$1"
    curl -s "$BASE_URL/api/queues/$QUEUE_ID/jobs?size=200" -H "$AUTH_HEADER" \
        | jq -r --argjson id "$id" '.content[] | select(.id == $id) | .status'
}

get_job_field() {
    local id="$1"
    local field="$2"
    curl -s "$BASE_URL/api/queues/$QUEUE_ID/jobs?size=200" -H "$AUTH_HEADER" \
        | jq -r --argjson id "$id" --arg field "$field" '.content[] | select(.id == $id) | .[$field]'
}

# wait_for_status <id> <space-separated list of acceptable terminal statuses> <timeout seconds>
# echoes the final status observed (may be a non-terminal status if it timed out)
wait_for_status() {
    local id="$1"
    local terminal_statuses="$2"
    local timeout="$3"
    local elapsed=0
    local status=""

    while [ "$elapsed" -lt "$timeout" ]; do
        status=$(get_job_status "$id")
        for s in $terminal_statuses; do
            if [ "$status" == "$s" ]; then
                echo "$status"
                return 0
            fi
        done
        sleep 2
        elapsed=$((elapsed+2))
    done
    echo "$status"
}

count_jobs_by_status() {
    local status="$1"
    curl -s "$BASE_URL/api/queues/$QUEUE_ID/jobs?status=$status&size=500" -H "$AUTH_HEADER" \
        | jq '.content | length'
}

# ============================================================
# Test 1 - Immediate job, guaranteed success
# ============================================================
echo "=== Test 1: Immediate job, guaranteed success ==="
JOB1_ID=$(create_job "immediate" '{"payload": {"simulateFailure": false}}')
if [ -z "$JOB1_ID" ]; then
    fail "Test 1: job creation did not return an id"
else
    info "Created job id=$JOB1_ID, waiting for COMPLETED..."
    STATUS=$(wait_for_status "$JOB1_ID" "COMPLETED DEAD_LETTER" 30)
    if [ "$STATUS" == "COMPLETED" ]; then
        pass "Test 1: job $JOB1_ID reached COMPLETED"
    else
        fail "Test 1: job $JOB1_ID ended up '$STATUS' instead of COMPLETED (timed out after 30s)"
    fi

    if $MYSQL_AVAILABLE; then
        EXEC_STATUS=$(mysql_query "SELECT status FROM job_executions WHERE job_id=$JOB1_ID ORDER BY id DESC LIMIT 1;")
        if [ "$EXEC_STATUS" == "SUCCESS" ]; then
            pass "Test 1: job_executions row shows SUCCESS"
        else
            fail "Test 1: job_executions status was '$EXEC_STATUS', expected SUCCESS"
        fi
    fi
fi
echo

# ============================================================
# Test 2 - Guaranteed failure -> retries -> DLQ
# ============================================================
echo "=== Test 2: Guaranteed failure -> retry -> Dead Letter Queue ==="
echo "    (this one is slow - default retry delay is 30s per attempt unless your"
echo "     queue has a fast custom RetryPolicy attached. Budget ~2 minutes for maxAttempts=3.)"
JOB2_ID=$(create_job "immediate" '{"payload": {"simulateFailure": true}, "maxAttempts": 3}')
if [ -z "$JOB2_ID" ]; then
    fail "Test 2: job creation did not return an id"
else
    info "Created job id=$JOB2_ID, waiting for DEAD_LETTER (timeout 150s)..."
    STATUS=$(wait_for_status "$JOB2_ID" "DEAD_LETTER" 150)
    if [ "$STATUS" == "DEAD_LETTER" ]; then
        ATTEMPTS=$(get_job_field "$JOB2_ID" "attemptCount")
        if [ "$ATTEMPTS" == "3" ]; then
            pass "Test 2: job $JOB2_ID reached DEAD_LETTER after exactly 3 attempts"
        else
            fail "Test 2: job $JOB2_ID reached DEAD_LETTER but attemptCount was '$ATTEMPTS', expected 3"
        fi
    else
        fail "Test 2: job $JOB2_ID ended up '$STATUS' instead of DEAD_LETTER (timed out after 150s)"
    fi

    if $MYSQL_AVAILABLE; then
        DLQ_COUNT=$(mysql_query "SELECT COUNT(*) FROM dead_letter_queue WHERE job_id=$JOB2_ID;")
        if [ "$DLQ_COUNT" == "1" ]; then
            pass "Test 2: exactly one dead_letter_queue row exists for job $JOB2_ID"
        else
            fail "Test 2: expected 1 dead_letter_queue row for job $JOB2_ID, found $DLQ_COUNT"
        fi

        EXEC_COUNT=$(mysql_query "SELECT COUNT(*) FROM job_executions WHERE job_id=$JOB2_ID AND status='FAILURE';")
        if [ "$EXEC_COUNT" == "3" ]; then
            pass "Test 2: 3 FAILURE job_executions rows recorded"
        else
            fail "Test 2: expected 3 FAILURE job_executions rows, found $EXEC_COUNT"
        fi
    fi
fi
echo

# ============================================================
# Test 3 - Heartbeat updates during a long-running job
# ============================================================
echo "=== Test 3: Heartbeat updates while RUNNING ==="
if ! $MYSQL_AVAILABLE; then
    echo "  [SKIP] Test 3 requires direct MySQL access (last_heartbeat_at isn't exposed via any API)."
    echo "         Set DB_PASS and ensure the mysql CLI is on PATH to enable this check."
else
    JOB3_ID=$(create_job "immediate" '{"payload": {"simulateDurationMs": 25000, "simulateFailure": false}}')
    if [ -z "$JOB3_ID" ]; then
        fail "Test 3: job creation did not return an id"
    else
        info "Created job id=$JOB3_ID (25s simulated duration). Waiting for it to start RUNNING..."
        elapsed=0
        while [ "$(get_job_status "$JOB3_ID")" != "RUNNING" ] && [ "$elapsed" -lt 15 ]; do
            sleep 1
            elapsed=$((elapsed+1))
        done

        HB1=$(mysql_query "SELECT last_heartbeat_at FROM jobs WHERE id=$JOB3_ID;")
        info "Heartbeat reading #1: $HB1"
        sleep 12
        HB2=$(mysql_query "SELECT last_heartbeat_at FROM jobs WHERE id=$JOB3_ID;")
        info "Heartbeat reading #2: $HB2"

        if [ -n "$HB1" ] && [ -n "$HB2" ] && [ "$HB1" != "$HB2" ]; then
            pass "Test 3: last_heartbeat_at advanced between readings ($HB1 -> $HB2)"
        else
            fail "Test 3: last_heartbeat_at did not change between readings ($HB1 -> $HB2)"
        fi

        # drain the rest of the 25s so it doesn't bleed into later tests
        wait_for_status "$JOB3_ID" "COMPLETED DEAD_LETTER" 20 > /dev/null
    fi
fi
echo

# ============================================================
# Test 4 - Batch job aggregation
# ============================================================
echo "=== Test 4: Batch job aggregation ==="
BATCH_BODY='{
  "children": [
    {"payload": {"simulateFailure": false}},
    {"payload": {"simulateFailure": false}},
    {"payload": {"simulateFailure": true}, "maxAttempts": 1}
  ]
}'
BATCH_ID=$(create_job "batch" "$BATCH_BODY")
if [ -z "$BATCH_ID" ]; then
    fail "Test 4: batch job creation did not return an id"
else
    info "Created batch parent id=$BATCH_ID, waiting for children to resolve..."
    elapsed=0
    RESOLVED=0
    while [ "$elapsed" -lt 40 ]; do
        RESOLVED_COUNT=$(curl -s "$BASE_URL/api/queues/$QUEUE_ID/jobs?size=200" -H "$AUTH_HEADER" \
            | jq --argjson pid "$BATCH_ID" '[.content[] | select(.parentJobId == $pid) | select(.status == "COMPLETED" or .status == "DEAD_LETTER")] | length')
        if [ "$RESOLVED_COUNT" == "3" ]; then
            RESOLVED=1
            break
        fi
        sleep 2
        elapsed=$((elapsed+2))
    done

    if [ "$RESOLVED" == "1" ]; then
        pass "Test 4: all 3 batch children resolved"
    else
        fail "Test 4: batch children did not all resolve within 40s"
    fi

    sleep 2 # let the parent-status derivation write settle
    PARENT_STATUS=$(get_job_status "$BATCH_ID")
    if [ "$PARENT_STATUS" == "PARTIALLY_FAILED" ]; then
        pass "Test 4: batch parent $BATCH_ID correctly derived to PARTIALLY_FAILED"
    else
        fail "Test 4: batch parent $BATCH_ID status was '$PARENT_STATUS', expected PARTIALLY_FAILED"
    fi

    if $MYSQL_AVAILABLE; then
        COUNTERS=$(mysql_query "SELECT completed_children, failed_children, total_children FROM jobs WHERE id=$BATCH_ID;")
        info "Parent counters (completed / failed / total): $COUNTERS"
        EXPECTED="2	1	3"
        if [ "$COUNTERS" == "$EXPECTED" ]; then
            pass "Test 4: parent counters are exactly 2 completed / 1 failed / 3 total"
        else
            fail "Test 4: parent counters were '$COUNTERS', expected '2 completed / 1 failed / 3 total'"
        fi
    fi
fi
echo

# ============================================================
# Test 5 - Concurrency limit respected
# ============================================================
echo "=== Test 5: Concurrency limit (queue.concurrencyLimit=$CONCURRENCY_LIMIT) is respected ==="
echo "    Firing 5 jobs with an 8s simulated duration each..."
for i in 1 2 3 4 5; do
    create_job "immediate" '{"payload": {"simulateDurationMs": 8000, "simulateFailure": false}}' > /dev/null
done

MAX_INFLIGHT=0
elapsed=0
while [ "$elapsed" -lt 12 ]; do
    CLAIMED=$(count_jobs_by_status "CLAIMED")
    RUNNING=$(count_jobs_by_status "RUNNING")
    INFLIGHT=$((CLAIMED+RUNNING))
    if [ "$INFLIGHT" -gt "$MAX_INFLIGHT" ]; then
        MAX_INFLIGHT=$INFLIGHT
    fi
    sleep 1
    elapsed=$((elapsed+1))
done

info "Peak observed in-flight (CLAIMED+RUNNING) count: $MAX_INFLIGHT"
if [ "$MAX_INFLIGHT" -le "$CONCURRENCY_LIMIT" ]; then
    pass "Test 5: peak in-flight ($MAX_INFLIGHT) never exceeded concurrencyLimit ($CONCURRENCY_LIMIT)"
else
    fail "Test 5: peak in-flight ($MAX_INFLIGHT) EXCEEDED concurrencyLimit ($CONCURRENCY_LIMIT)"
fi
echo "    (letting the remaining jobs drain...)"
sleep 10
echo

# ============================================================
# Test 6 - Batch parent is never itself claimed
# ============================================================
echo "=== Test 6: Batch parent from Test 4 was never claimed/executed directly ==="
if [ -z "${BATCH_ID:-}" ]; then
    echo "  [SKIP] Test 6 depends on Test 4's batch id, which wasn't created."
elif $MYSQL_AVAILABLE; then
    CLAIMED_AT=$(mysql_query "SELECT claimed_at FROM jobs WHERE id=$BATCH_ID;")
    if [ -z "$CLAIMED_AT" ] || [ "$CLAIMED_AT" == "NULL" ]; then
        pass "Test 6: batch parent $BATCH_ID has claimed_at = NULL (never claimed by a worker)"
    else
        fail "Test 6: batch parent $BATCH_ID has claimed_at = '$CLAIMED_AT' - it was claimed, which shouldn't happen"
    fi
else
    echo "  [SKIP] Test 6 requires direct MySQL access (claimedByWorker isn't exposed via the API)."
fi
echo

# ============================================================
# Summary
# ============================================================
echo "================================================================"
echo "SUMMARY: $PASS passed, $FAIL failed"
echo "================================================================"
for r in "${RESULTS[@]}"; do
    echo "  $r"
done

if [ "$FAIL" -gt 0 ]; then
    echo
    echo "Before assuming any FAIL is a real Worker Engine bug: re-check directly against"
    echo "MySQL first (same discipline that caught the pagination false-alarm during"
    echo "Scheduler testing) - especially for any job that just needed a bit more time"
    echo "than this script's timeouts allowed."
    exit 1
fi

exit 0
