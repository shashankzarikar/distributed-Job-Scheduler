package com.jobscheduler.distributed_job_scheduler.dto.queue;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class QueueStatsResponse {
    private Long queueId;
    private long queuedCount;
    private long runningCount;
    private long completedCount;
    private long failedCount;
    private long deadLetterCount;
}