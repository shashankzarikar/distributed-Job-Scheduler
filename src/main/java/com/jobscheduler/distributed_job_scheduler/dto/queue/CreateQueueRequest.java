package com.jobscheduler.distributed_job_scheduler.dto.queue;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateQueueRequest {

    @NotBlank(message = "Queue name is required")
    private String name;

    private Integer priority = 0;

    @Min(value = 1, message = "Concurrency limit must be at least 1")
    private Integer concurrencyLimit = 5;

    private Long retryPolicyId; // optional — queue can have no default retry policy
}