#!/usr/bin/env bash
#
# Scheduler (Step E) automated test script
# Run in Git Bash or WSL on Windows.
#
# Requires: curl, jq
#   - WSL:      sudo apt-get install -y jq
#   - Git Bash: download jq.exe from https://jqlang.org/download/ and put it
#               on your PATH (e.g. C:\Windows or a folder already in PATH)
#
# Usage:
#   chmod +x test_scheduler.sh
#   ./test_scheduler.sh

set -uo pipefail

BASE_URL="http://localhost:8080"
QUEUE_ID=1
EMAIL="test@example.com"
PASSWORD="password123"

PASS_COUNT=0
FAIL_COUNT=0
RESULTS=()

pass() { RESULTS+=("PASS - $1"); PASS_COUNT=$((PASS_COUNT+1)); echo "  [PASS] $1"; }
fail() { RESULTS+=("FAIL - $1"); FAIL_COUNT=$((FAIL_COUNT+1)); echo "  [FAIL] $1"; }

echo "=================================================="
echo " Step E Scheduler Test Suite"
echo "=================================================="

# ---------- STEP 0: LOGIN ----------
echo ""
echo "[Step 0] Logging in..."
LOGIN_RESPONSE=$(curl -s -X POST "$BASE_URL/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")

TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.token // empty')

if [ -z "$TOKEN" ]; then
  echo "Login failed. Raw response:"
  echo "$LOGIN_RESPONSE"
  echo ""
  echo "If the field name isn't 'token', open AuthResponse.java and check the"
  echo "actual field name, then edit the jq path in this script (search for '.token')."
  exit 1
fi
echo "  Got token: ${TOKEN:0:20}..."
AUTH_HEADER="Authorization: Bearer $TOKEN"

# ---------- STEP 1: CREATE DELAYED JOB ----------
echo ""
echo "[Step 1] Creating delayed job (delaySeconds=15)..."
DELAYED_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/queues/$QUEUE_ID/jobs/delayed" \
  -H "$AUTH_HEADER" -H "Content-Type: application/json" \
  -d '{"payload":{"action":"test_delayed_promotion"},"delaySeconds":15}')
DELAYED_BODY=$(echo "$DELAYED_RESPONSE" | head -n -1)
DELAYED_STATUS=$(echo "$DELAYED_RESPONSE" | tail -n 1)
echo "  Status: $DELAYED_STATUS"
echo "  Body:   $DELAYED_BODY"

if [ "$DELAYED_STATUS" == "201" ]; then
  pass "Delayed job creation returned 201"
else
  fail "Delayed job creation returned $DELAYED_STATUS (expected 201)"
fi

CREATED_AT=$(echo "$DELAYED_BODY" | jq -r '.createdAt // empty')
echo "  createdAt from response: $CREATED_AT"
echo "  --> Compare this to your system clock manually. If off by hours, timezone bug is back."

echo ""
echo "  Waiting 20 seconds for scheduler poll + promotion..."
sleep 20

# ---------- STEP 2: CHECK DELAYED JOB PROMOTED ----------
echo ""
echo "[Step 2] Checking if delayed job was promoted..."
JOBS_RESPONSE=$(curl -s -X GET "$BASE_URL/api/queues/$QUEUE_ID/jobs?status=QUEUED" -H "$AUTH_HEADER")
FOUND=$(echo "$JOBS_RESPONSE" | jq '[.content[] | select(.type == "DELAYED" and .payload.action == "test_delayed_promotion")] | length')

if [ "$FOUND" -ge 1 ]; then
  pass "Delayed job promoted and visible in job listing"
else
  fail "Delayed job NOT found in job listing after wait"
fi

# ---------- STEP 3: CREATE SCHEDULED JOB ----------
echo ""
echo "[Step 3] Creating scheduled job (scheduledAt = now+90s UTC)..."
if date -u -d "+90 seconds" +"%Y-%m-%dT%H:%M:%SZ" >/dev/null 2>&1; then
  SCHEDULED_AT=$(date -u -d "+90 seconds" +"%Y-%m-%dT%H:%M:%SZ")
else
  # BSD/macOS date fallback
  SCHEDULED_AT=$(date -u -v+90S +"%Y-%m-%dT%H:%M:%SZ")
fi
echo "  Using scheduledAt: $SCHEDULED_AT"

SCHEDULED_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/queues/$QUEUE_ID/jobs/scheduled" \
  -H "$AUTH_HEADER" -H "Content-Type: application/json" \
  -d "{\"payload\":{\"action\":\"test_scheduled_promotion\"},\"scheduledAt\":\"$SCHEDULED_AT\"}")
SCHEDULED_BODY=$(echo "$SCHEDULED_RESPONSE" | head -n -1)
SCHEDULED_STATUS=$(echo "$SCHEDULED_RESPONSE" | tail -n 1)
echo "  Status: $SCHEDULED_STATUS"
echo "  Body:   $SCHEDULED_BODY"

if [ "$SCHEDULED_STATUS" == "201" ]; then
  pass "Scheduled job creation returned 201"
else
  fail "Scheduled job creation returned $SCHEDULED_STATUS (expected 201)"
fi

echo ""
echo "  Waiting 100 seconds for scheduled time + poll + promotion..."
sleep 100

# ---------- STEP 4: CHECK SCHEDULED JOB PROMOTED ----------
echo ""
echo "[Step 4] Checking if scheduled job was promoted..."
JOBS_RESPONSE=$(curl -s -X GET "$BASE_URL/api/queues/$QUEUE_ID/jobs?status=QUEUED" -H "$AUTH_HEADER")
FOUND=$(echo "$JOBS_RESPONSE" | jq '[.content[] | select(.type == "SCHEDULED" and .payload.action == "test_scheduled_promotion")] | length')

if [ "$FOUND" -ge 1 ]; then
  pass "Scheduled job promoted and visible in job listing"
else
  fail "Scheduled job NOT found in job listing after wait"
fi

# ---------- STEP 5: CREATE CRON JOB ----------
echo ""
echo "[Step 5] Creating cron job (*/30 * * * * *)..."
CRON_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/queues/$QUEUE_ID/jobs/cron" \
  -H "$AUTH_HEADER" -H "Content-Type: application/json" \
  -d '{"payload":{"action":"test_cron_promotion"},"cronExpression":"*/30 * * * * *"}')
CRON_BODY=$(echo "$CRON_RESPONSE" | head -n -1)
CRON_STATUS=$(echo "$CRON_RESPONSE" | tail -n 1)
echo "  Status: $CRON_STATUS"
echo "  Body:   $CRON_BODY"

if [ "$CRON_STATUS" == "201" ]; then
  pass "Cron job creation returned 201"
else
  fail "Cron job creation returned $CRON_STATUS (expected 201)"
fi

echo ""
echo "  Waiting 40 seconds for first cron occurrence..."
sleep 40

# ---------- STEP 6: CHECK CRON PROMOTED AT LEAST ONCE ----------
echo ""
echo "[Step 6a] Checking cron job promoted at least once..."
JOBS_RESPONSE=$(curl -s -X GET "$BASE_URL/api/queues/$QUEUE_ID/jobs?status=QUEUED" -H "$AUTH_HEADER")
CRON_COUNT_1=$(echo "$JOBS_RESPONSE" | jq '[.content[] | select(.type == "CRON" and .payload.action == "test_cron_promotion")] | length')
echo "  CRON jobs found so far: $CRON_COUNT_1"

if [ "$CRON_COUNT_1" -ge 1 ]; then
  pass "Cron job promoted at least once ($CRON_COUNT_1 found)"
else
  fail "Cron job NOT promoted after first interval"
fi

echo ""
echo "  Waiting another 40 seconds for second cron occurrence..."
sleep 40

echo ""
echo "[Step 6b] Checking cron job promoted a second time..."
JOBS_RESPONSE=$(curl -s -X GET "$BASE_URL/api/queues/$QUEUE_ID/jobs?status=QUEUED" -H "$AUTH_HEADER")
CRON_COUNT_2=$(echo "$JOBS_RESPONSE" | jq '[.content[] | select(.type == "CRON" and .payload.action == "test_cron_promotion")] | length')
echo "  CRON jobs found now: $CRON_COUNT_2"

if [ "$CRON_COUNT_2" -ge 2 ]; then
  pass "Cron job promoted a second time (recurrence chain confirmed, $CRON_COUNT_2 total)"
else
  fail "Cron job did NOT promote a second time (expected >= 2, got $CRON_COUNT_2)"
fi

# ---------- STEP 7: CREATE IMMEDIATE JOB ----------
echo ""
echo "[Step 7] Creating immediate job (should skip staging entirely)..."
IMMEDIATE_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/queues/$QUEUE_ID/jobs/immediate" \
  -H "$AUTH_HEADER" -H "Content-Type: application/json" \
  -d '{"payload":{"action":"sanity_check_immediate"}}')
IMMEDIATE_BODY=$(echo "$IMMEDIATE_RESPONSE" | head -n -1)
IMMEDIATE_STATUS=$(echo "$IMMEDIATE_RESPONSE" | tail -n 1)
echo "  Status: $IMMEDIATE_STATUS"
echo "  Body:   $IMMEDIATE_BODY"

IMMEDIATE_JOB_STATUS=$(echo "$IMMEDIATE_BODY" | jq -r '.status // empty')
if [ "$IMMEDIATE_STATUS" == "201" ] && [ "$IMMEDIATE_JOB_STATUS" == "QUEUED" ]; then
  pass "Immediate job created with status QUEUED right away (no staging delay)"
else
  fail "Immediate job creation unexpected (HTTP $IMMEDIATE_STATUS, status=$IMMEDIATE_JOB_STATUS)"
fi

# ---------- STEP 8: CONFIRM IMMEDIATE JOB VISIBLE RIGHT AWAY ----------
echo ""
echo "[Step 8] Confirming immediate job visible in listing without waiting..."
JOBS_RESPONSE=$(curl -s -X GET "$BASE_URL/api/queues/$QUEUE_ID/jobs?status=QUEUED" -H "$AUTH_HEADER")
FOUND=$(echo "$JOBS_RESPONSE" | jq '[.content[] | select(.type == "IMMEDIATE" and .payload.action == "sanity_check_immediate")] | length')

if [ "$FOUND" -ge 1 ]; then
  pass "Immediate job visible immediately in job listing"
else
  fail "Immediate job NOT found in job listing"
fi

# ---------- SUMMARY ----------
echo ""
echo "=================================================="
echo " SUMMARY: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "=================================================="
for r in "${RESULTS[@]}"; do
  echo "  $r"
done

if [ "$FAIL_COUNT" -gt 0 ]; then
  exit 1
fi
exit 0
