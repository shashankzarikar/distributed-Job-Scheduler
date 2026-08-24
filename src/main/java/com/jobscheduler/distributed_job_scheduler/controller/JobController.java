package com.jobscheduler.distributed_job_scheduler.controller;

import com.jobscheduler.distributed_job_scheduler.dto.job.*;
import com.jobscheduler.distributed_job_scheduler.entity.Job;
import com.jobscheduler.distributed_job_scheduler.entity.User;
import com.jobscheduler.distributed_job_scheduler.security.SecurityUtils;
import com.jobscheduler.distributed_job_scheduler.service.JobService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/queues/{queueId}/jobs")
public class JobController {

    private final JobService jobService;
    private final SecurityUtils securityUtils;

    public JobController(JobService jobService, SecurityUtils securityUtils) {
        this.jobService = jobService;
        this.securityUtils = securityUtils;
    }

    @PostMapping("/immediate")
    public ResponseEntity<JobResponse> createImmediate(
            @PathVariable Long queueId,
            @Valid @RequestBody CreateImmediateJobRequest request
    ) {
        User currentUser = securityUtils.getCurrentUser();
        JobResponse response = jobService.createImmediateJob(currentUser, queueId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/delayed")
    public ResponseEntity<ScheduledJobResponse> createDelayed(
            @PathVariable Long queueId,
            @Valid @RequestBody CreateDelayedJobRequest request
    ) {
        User currentUser = securityUtils.getCurrentUser();
        ScheduledJobResponse response = jobService.createDelayedJob(currentUser, queueId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/scheduled")
    public ResponseEntity<ScheduledJobResponse> createScheduled(
            @PathVariable Long queueId,
            @Valid @RequestBody CreateScheduledJobRequest request
    ) {
        User currentUser = securityUtils.getCurrentUser();
        ScheduledJobResponse response = jobService.createScheduledJob(currentUser, queueId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/cron")
    public ResponseEntity<ScheduledJobResponse> createCron(
            @PathVariable Long queueId,
            @Valid @RequestBody CreateCronJobRequest request
    ) {
        User currentUser = securityUtils.getCurrentUser();
        ScheduledJobResponse response = jobService.createCronJob(currentUser, queueId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/batch")
    public ResponseEntity<JobResponse> createBatch(
            @PathVariable Long queueId,
            @Valid @RequestBody CreateBatchJobRequest request
    ) {
        User currentUser = securityUtils.getCurrentUser();
        JobResponse response = jobService.createBatchJob(currentUser, queueId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<Page<JobResponse>> listJobs(
            @PathVariable Long queueId,
            @RequestParam(required = false) Job.Status status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        User currentUser = securityUtils.getCurrentUser();
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(jobService.listJobs(currentUser, queueId, status, pageable));
    }
}