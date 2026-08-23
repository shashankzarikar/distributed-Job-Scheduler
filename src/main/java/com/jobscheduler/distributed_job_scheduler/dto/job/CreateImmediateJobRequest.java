package com.jobscheduler.distributed_job_scheduler.dto.job;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
public class CreateImmediateJobRequest {

    @NotNull(message = "payload is required")
    private Map<String, Object> payload;

    private Integer priority;

    @Size(max = 255, message = "idempotencyKey must be at most 255 characters")
    private String idempotencyKey;

    @Min(value = 1, message = "maxAttempts must be at least 1")
    private Integer maxAttempts;
}