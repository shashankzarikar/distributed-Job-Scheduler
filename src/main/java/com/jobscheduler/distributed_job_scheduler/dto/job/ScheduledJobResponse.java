package com.jobscheduler.distributed_job_scheduler.dto.job;

import com.jobscheduler.distributed_job_scheduler.entity.ScheduledJob;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Map;

@Getter
@AllArgsConstructor
public class ScheduledJobResponse {
    private Long id;
    private Long queueId;
    private ScheduledJob.JobType jobType;
    private Map<String, Object> payload;
    private Integer priority;
    private String cronExpression;
    private LocalDateTime nextRunTime;
    private Boolean isRecurring;
    private Boolean promoted;
    private LocalDateTime createdAt;
}