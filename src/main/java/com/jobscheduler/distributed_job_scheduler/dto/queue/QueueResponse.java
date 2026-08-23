package com.jobscheduler.distributed_job_scheduler.dto.queue;

import com.jobscheduler.distributed_job_scheduler.entity.Queue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class QueueResponse {
    private Long id;
    private Long projectId;
    private String name;
    private Integer priority;
    private Integer concurrencyLimit;
    private Long retryPolicyId;
    private Queue.Status status;
    private LocalDateTime createdAt;
}