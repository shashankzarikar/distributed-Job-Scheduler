package com.jobscheduler.distributed_job_scheduler.dto.job;

import com.jobscheduler.distributed_job_scheduler.entity.Job;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Map;

@Getter
@AllArgsConstructor
public class DeadLetterQueueResponse {
    private Long id;
    private Long jobId;
    private Long queueId;
    private Job.Type jobType;
    private Map<String, Object> payload;
    private Integer attemptCount;
    private Integer maxAttempts;
    private String reason;
    private LocalDateTime movedAt;
    private Boolean retriedManually;
}