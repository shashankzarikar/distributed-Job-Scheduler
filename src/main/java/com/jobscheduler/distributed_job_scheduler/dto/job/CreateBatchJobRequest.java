package com.jobscheduler.distributed_job_scheduler.dto.job;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class CreateBatchJobRequest {

    // Batch-level defaults; each child can still override its own priority/maxAttempts
    private Integer priority;

    @Min(value = 1, message = "maxAttempts must be at least 1")
    private Integer maxAttempts;

    @NotEmpty(message = "a batch must have at least one child job")
    @Valid
    private List<ChildJobPayload> children;
}