package com.jobscheduler.distributed_job_scheduler.dto.job;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

// Exactly one of delaySeconds / runAfter must be set — validated in JobService.
// No idempotencyKey or maxAttempts: scheduled_jobs has neither column.
// Both apply only once the job is promoted into the jobs table (Step E).
@Getter
@Setter
@NoArgsConstructor
public class CreateDelayedJobRequest {

    @NotNull(message = "payload is required")
    private Map<String, Object> payload;

    @Positive(message = "delaySeconds must be positive")
    private Long delaySeconds;

    @Future(message = "runAfter must be in the future")
    private Instant runAfter;

    private Integer priority;
}