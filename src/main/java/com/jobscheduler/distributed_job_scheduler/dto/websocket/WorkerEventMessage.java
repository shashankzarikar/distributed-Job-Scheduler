package com.jobscheduler.distributed_job_scheduler.dto.websocket;

import com.jobscheduler.distributed_job_scheduler.entity.Worker;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class WorkerEventMessage {
    private Long workerId;
    private String workerName;
    private Worker.Status status;
    private LocalDateTime timestamp;
}