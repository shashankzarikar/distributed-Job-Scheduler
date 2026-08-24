package com.jobscheduler.distributed_job_scheduler.worker;

import com.jobscheduler.distributed_job_scheduler.entity.RetryPolicy;
import org.springframework.stereotype.Component;

/**
 * Pure delay-computation logic for the three retry strategies. Note: the job's own
 * maxAttempts (set at creation, defaulting to 5 — see decision 3.12) is the authority
 * on WHEN retries stop; this class only decides HOW LONG to wait before the next one.
 */
@Component
public class RetryCalculator {

    private static final int DEFAULT_DELAY_SECONDS = 30;
    private static final int DEFAULT_MAX_DELAY_SECONDS = 3600;

    /**
     * @param attemptCount the attempt number that just failed (1-indexed, already incremented)
     */
    public int nextDelaySeconds(RetryPolicy policy, int attemptCount) {
        if (policy == null) {
            return DEFAULT_DELAY_SECONDS; // job's queue has no default retry policy configured
        }

        int base = policy.getBaseDelaySeconds();
        int max = policy.getMaxDelaySeconds() != null ? policy.getMaxDelaySeconds() : DEFAULT_MAX_DELAY_SECONDS;

        int delay = switch (policy.getStrategy()) {
            case FIXED -> base;
            case LINEAR -> base * attemptCount;
            case EXPONENTIAL -> (int) Math.min((long) base * (1L << Math.max(0, attemptCount - 1)), Integer.MAX_VALUE);
        };

        return Math.min(delay, max);
    }
}