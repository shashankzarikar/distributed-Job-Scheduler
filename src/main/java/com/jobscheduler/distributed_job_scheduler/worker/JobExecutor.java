package com.jobscheduler.distributed_job_scheduler.worker;

import com.jobscheduler.distributed_job_scheduler.entity.Job;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Random;

/**
 * Simulated job execution — there's no real business task defined by this assignment,
 * so this class stands in for "whatever work a real worker would do." Controllable via
 * payload so retries/failures/DLQ/heartbeats can be demoed on demand in Postman:
 *
 *   { "simulateFailure": true }     -> always fails
 *   { "simulateFailure": false }    -> always succeeds
 *   { "simulateDurationMs": 15000 } -> sleeps ~15s (e.g. to test heartbeat ticks, which
 *                                      default to firing every 10s during RUNNING)
 *
 * If simulateFailure isn't in the payload, falls back to a 20% random failure chance so
 * ordinary jobs still exercise the retry path some of the time without special setup.
 */
@Slf4j
@Component
public class JobExecutor {

    private static final double DEFAULT_FAILURE_CHANCE = 0.2;
    private static final long DEFAULT_MIN_DURATION_MS = 200;
    private static final long DEFAULT_MAX_DURATION_MS = 1500;

    private final ObjectMapper objectMapper;
    private final Random random = new Random();

    public JobExecutor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void execute(Job job) throws JobExecutionFailedException {
        Map<String, Object> payload = readPayload(job.getPayload());

        try {
            Thread.sleep(extractDurationMs(payload));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JobExecutionFailedException("Interrupted during execution (likely graceful shutdown)");
        }

        if (extractFailureDecision(payload)) {
            throw new JobExecutionFailedException("Simulated failure (job id=" + job.getId() + ")");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPayload(String json) {
        if (json == null) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Job payload could not be parsed for execution simulation; treating as empty", e);
            return Map.of();
        }
    }

    private long extractDurationMs(Map<String, Object> payload) {
        Object v = payload.get("simulateDurationMs");
        if (v instanceof Number n) {
            return Math.max(0, n.longValue());
        }
        return DEFAULT_MIN_DURATION_MS + (long) (random.nextDouble() * (DEFAULT_MAX_DURATION_MS - DEFAULT_MIN_DURATION_MS));
    }

    private boolean extractFailureDecision(Map<String, Object> payload) {
        Object v = payload.get("simulateFailure");
        if (v instanceof Boolean b) {
            return b;
        }
        return random.nextDouble() < DEFAULT_FAILURE_CHANCE;
    }

    public static class JobExecutionFailedException extends Exception {
        public JobExecutionFailedException(String message) {
            super(message);
        }
    }
}