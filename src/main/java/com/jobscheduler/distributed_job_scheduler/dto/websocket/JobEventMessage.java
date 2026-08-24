package com.jobscheduler.distributed_job_scheduler.dto.websocket;

import com.jobscheduler.distributed_job_scheduler.entity.Job;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class JobEventMessage {
    private Long jobId;
    private Long queueId;
    private Long parentJobId;
    private Job.Type type;
    private Job.Status status;
    private EventType eventType;
    private Integer attemptCount;
    private Integer maxAttempts;
    private Long workerId;   // nullable — not always meaningful (e.g. on completion, worker is already detached)
    private String detail;   // nullable — e.g. failure reason, retry delay note
    private LocalDateTime timestamp;

    public enum EventType {
        CLAIMED, RUNNING, COMPLETED, RETRY_SCHEDULED, DEAD_LETTER, BATCH_RESOLVED
    }
}