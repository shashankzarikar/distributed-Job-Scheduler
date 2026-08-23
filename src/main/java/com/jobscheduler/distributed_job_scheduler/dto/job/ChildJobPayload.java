package com.jobscheduler.distributed_job_scheduler.dto.job;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
public class ChildJobPayload {

    @NotNull(message = "payload is required for each child job")
    private Map<String, Object> payload;

    private Integer priority;

    @Min(value = 1, message = "maxAttempts must be at least 1")
    private Integer maxAttempts;
}