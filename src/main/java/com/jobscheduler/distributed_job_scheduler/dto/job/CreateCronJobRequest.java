package com.jobscheduler.distributed_job_scheduler.dto.job;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

// cronExpression format validated in JobService via Spring's CronExpression.parse()
@Getter
@Setter
@NoArgsConstructor
public class CreateCronJobRequest {

    @NotNull(message = "payload is required")
    private Map<String, Object> payload;

    @NotBlank(message = "cronExpression is required")
    private String cronExpression;

    private Integer priority;
}