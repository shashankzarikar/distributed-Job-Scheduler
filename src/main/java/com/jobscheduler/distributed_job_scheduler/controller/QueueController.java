package com.jobscheduler.distributed_job_scheduler.controller;

import com.jobscheduler.distributed_job_scheduler.dto.queue.*;
import com.jobscheduler.distributed_job_scheduler.entity.User;
import com.jobscheduler.distributed_job_scheduler.security.SecurityUtils;
import com.jobscheduler.distributed_job_scheduler.service.QueueService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class QueueController {

    private final QueueService queueService;
    private final SecurityUtils securityUtils;

    public QueueController(QueueService queueService, SecurityUtils securityUtils) {
        this.queueService = queueService;
        this.securityUtils = securityUtils;
    }

    @PostMapping("/api/projects/{projectId}/queues")
    public ResponseEntity<QueueResponse> createQueue(
            @PathVariable Long projectId,
            @Valid @RequestBody CreateQueueRequest request
    ) {
        User currentUser = securityUtils.getCurrentUser();
        QueueResponse response = queueService.createQueue(currentUser, projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/api/projects/{projectId}/queues")
    public ResponseEntity<List<QueueResponse>> listQueues(@PathVariable Long projectId) {
        User currentUser = securityUtils.getCurrentUser();
        return ResponseEntity.ok(queueService.listQueues(currentUser, projectId));
    }

    @PatchMapping("/api/queues/{queueId}/pause")
    public ResponseEntity<QueueResponse> pauseQueue(@PathVariable Long queueId) {
        User currentUser = securityUtils.getCurrentUser();
        return ResponseEntity.ok(queueService.pauseQueue(currentUser, queueId));
    }

    @PatchMapping("/api/queues/{queueId}/resume")
    public ResponseEntity<QueueResponse> resumeQueue(@PathVariable Long queueId) {
        User currentUser = securityUtils.getCurrentUser();
        return ResponseEntity.ok(queueService.resumeQueue(currentUser, queueId));
    }

    @GetMapping("/api/queues/{queueId}/stats")
    public ResponseEntity<QueueStatsResponse> getStats(@PathVariable Long queueId) {
        User currentUser = securityUtils.getCurrentUser();
        return ResponseEntity.ok(queueService.getStats(currentUser, queueId));
    }
}