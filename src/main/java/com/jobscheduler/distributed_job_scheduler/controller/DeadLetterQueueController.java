package com.jobscheduler.distributed_job_scheduler.controller;

import com.jobscheduler.distributed_job_scheduler.dto.job.DeadLetterQueueResponse;
import com.jobscheduler.distributed_job_scheduler.dto.job.JobResponse;
import com.jobscheduler.distributed_job_scheduler.entity.User;
import com.jobscheduler.distributed_job_scheduler.security.SecurityUtils;
import com.jobscheduler.distributed_job_scheduler.service.JobService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class DeadLetterQueueController {

    private final JobService jobService;
    private final SecurityUtils securityUtils;

    public DeadLetterQueueController(JobService jobService, SecurityUtils securityUtils) {
        this.jobService = jobService;
        this.securityUtils = securityUtils;
    }

    @GetMapping("/api/queues/{queueId}/dead-letter-queue")
    public ResponseEntity<List<DeadLetterQueueResponse>> listDeadLetterQueue(@PathVariable Long queueId) {
        User currentUser = securityUtils.getCurrentUser();
        return ResponseEntity.ok(jobService.listDeadLetterQueue(currentUser, queueId));
    }

    @PostMapping("/api/jobs/{jobId}/retry")
    public ResponseEntity<JobResponse> retryJob(@PathVariable Long jobId) {
        User currentUser = securityUtils.getCurrentUser();
        return ResponseEntity.ok(jobService.retryDeadLetterJob(currentUser, jobId));
    }
}