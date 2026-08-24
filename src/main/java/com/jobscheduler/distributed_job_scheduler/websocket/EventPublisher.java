package com.jobscheduler.distributed_job_scheduler.websocket;

import com.jobscheduler.distributed_job_scheduler.dto.websocket.JobEventMessage;
import com.jobscheduler.distributed_job_scheduler.dto.websocket.WorkerEventMessage;
import com.jobscheduler.distributed_job_scheduler.entity.Job;
import com.jobscheduler.distributed_job_scheduler.entity.Worker;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Single chokepoint for pushing live updates to WebSocket-subscribed dashboard clients.
 * Deliberately its own bean rather than scattering messagingTemplate.convertAndSend(...)
 * calls across JobLifecycleService/JobOutcomeHandler/JobReaper/WorkerEngine directly —
 * keeps the topic-naming scheme (and the whole "what gets broadcast, and how" decision
 * from the Step G design discussion) in exactly one file.
 *
 * Topics:
 *   /topic/queues/{queueId}/jobs — job status transitions, scoped per queue so a dashboard
 *                                   client only receives events for the queue(s) it's viewing
 *   /topic/workers                — worker status transitions, global (workers aren't
 *                                   queue-scoped — see decision 3.6)
 */
@Component
public class EventPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public EventPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void publishJobEvent(Job job, JobEventMessage.EventType eventType, Long workerId, String detail) {
        JobEventMessage message = new JobEventMessage(
                job.getId(),
                job.getQueue().getId(),
                job.getParentJob() != null ? job.getParentJob().getId() : null,
                job.getType(),
                job.getStatus(),
                eventType,
                job.getAttemptCount(),
                job.getMaxAttempts(),
                workerId,
                detail,
                LocalDateTime.now()
        );

        messagingTemplate.convertAndSend("/topic/queues/" + job.getQueue().getId() + "/jobs", message);
    }

    public void publishWorkerEvent(Worker worker) {
        WorkerEventMessage message = new WorkerEventMessage(
                worker.getId(),
                worker.getName(),
                worker.getStatus(),
                LocalDateTime.now()
        );

        messagingTemplate.convertAndSend("/topic/workers", message);
    }
}