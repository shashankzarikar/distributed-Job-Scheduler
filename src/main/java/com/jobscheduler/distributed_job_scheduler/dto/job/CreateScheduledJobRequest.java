package com.jobscheduler.distributed_job_scheduler.dto.job;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
public class CreateScheduledJobRequest {

    @NotNull(message = "payload is required")
    private Map<String, Object> payload;

    @NotNull(message = "scheduledAt is required")
    @Future(message = "scheduledAt must be in the future")
    private Instant scheduledAt;

    private Integer priority;
}