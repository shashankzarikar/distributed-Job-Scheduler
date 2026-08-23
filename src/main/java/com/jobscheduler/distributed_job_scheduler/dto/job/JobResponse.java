package com.jobscheduler.distributed_job_scheduler.dto.job;

import com.jobscheduler.distributed_job_scheduler.entity.Job;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Map;

@Getter
@AllArgsConstructor
public class JobResponse {
    private Long id;
    private Long queueId;
    private Long parentJobId;
    private Job.Type type;
    private Job.Status status;
    private Map<String, Object> payload;
    private Integer priority;
    private Integer attemptCount;
    private Integer maxAttempts;
    private LocalDateTime runAfter;
    private String idempotencyKey;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}