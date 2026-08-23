package com.jobscheduler.distributed_job_scheduler.dto.job;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

// Exactly one of delaySeconds / runAfter must be set — validated in JobService.
// No idempotencyKey: scheduled_jobs has no such column; idempotency is only
// enforced for job types that go straight into the jobs table (immediate, batch).
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

    @Min(value = 1, message = "maxAttempts must be at least 1")
    private Integer maxAttempts;
}