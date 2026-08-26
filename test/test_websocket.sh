#!/usr/bin/env bash

# Exit immediately on uncaught error
set -e

BASE_URL="http://localhost:8080"
WS_URL="http://localhost:8080/ws"

echo "====================================================="
echo "   Distributed Job Scheduler - Automated Test Suite"
echo "====================================================="

# Check backend health
echo -n "Checking backend connectivity at $BASE_URL... "
if curl -s -f "$BASE_URL" > /dev/null 2>&1 || [ $? -eq 22 ]; then
  echo "Backend reachable."
else
  echo "Backend not responding. Please make sure Spring Boot is running on port 8080."
  exit 1
fi

echo ""
echo "-----------------------------------------------------"
echo "TEST 1: Immediate Job (Happy Path)"
echo "Expected: CLAIMED -> RUNNING -> COMPLETED"
echo "-----------------------------------------------------"
curl -s -X POST "$BASE_URL/api/queues/1/jobs/immediate" \
  -H "Content-Type: application/json" \
  -d '{
    "payload": {
      "task": "test-happy-path",
      "simulateFailure": false,
      "simulateDurationMs": 2000
    }
  }' | sed 's/.*/Response: &/'

echo "Job submitted. Sleeping 3 seconds for completion..."
sleep 3
echo "Test 1 Trigger Complete."

echo ""
echo "-----------------------------------------------------"
echo "TEST 2: Job Retry & Dead Letter Queue (DLQ)"
echo "Expected: CLAIMED -> RUNNING -> RETRY_SCHEDULED -> ... -> DEAD_LETTER"
echo "-----------------------------------------------------"
curl -s -X POST "$BASE_URL/api/queues/1/jobs/immediate" \
  -H "Content-Type: application/json" \
  -d '{
    "payload": {
      "task": "test-dlq",
      "simulateFailure": true,
      "simulateDurationMs": 1000
    },
    "maxAttempts": 2
  }' | sed 's/.*/Response: &/'

echo "Job submitted. Sleeping 5 seconds for retries..."
sleep 5
echo "Test 2 Trigger Complete."

echo ""
echo "-----------------------------------------------------"
echo "TEST 3: Queue Isolation Verification"
echo "Submitting to Queue 1 vs Queue 2..."
echo "-----------------------------------------------------"
echo "Firing job to Queue 1:"
curl -s -X POST "$BASE_URL/api/queues/1/jobs/immediate" \
  -H "Content-Type: application/json" \
  -d '{
    "payload": {
      "task": "queue-1-job",
      "simulateFailure": false,
      "simulateDurationMs": 1000
    }
  }' | sed 's/.*/Queue 1 Response: &/'

echo "Firing job to Queue 2:"
curl -s -X POST "$BASE_URL/api/queues/2/jobs/immediate" \
  -H "Content-Type: application/json" \
  -d '{
    "payload": {
      "task": "queue-2-job",
      "simulateFailure": false,
      "simulateDurationMs": 1000
    }
  }' | sed 's/.*/Queue 2 Response: &/'

sleep 2
echo "Test 3 Trigger Complete."

echo ""
echo "-----------------------------------------------------"
echo "TEST 4: Silent Heartbeat Verification (Long Job)"
echo "Expected: Single RUNNING event, no spam during execution"
echo "-----------------------------------------------------"
curl -s -X POST "$BASE_URL/api/queues/1/jobs/immediate" \
  -H "Content-Type: application/json" \
  -d '{
    "payload": {
      "task": "long-duration-job",
      "simulateFailure": false,
      "simulateDurationMs": 15000
    }
  }' | sed 's/.*/Response: &/'

echo "Long job running (15s duration). Sleeping 16 seconds..."
sleep 16
echo "Test 4 Trigger Complete."

echo ""
echo "====================================================="
echo "   All automated test triggers executed successfully!"
echo "====================================================="