package com.jobscheduler.distributed_job_scheduler.service;

import com.jobscheduler.distributed_job_scheduler.dto.job.*;
import com.jobscheduler.distributed_job_scheduler.entity.*;
import com.jobscheduler.distributed_job_scheduler.repository.JobRepository;
import com.jobscheduler.distributed_job_scheduler.repository.QueueRepository;
import com.jobscheduler.distributed_job_scheduler.repository.ScheduledJobRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

@Service
public class JobService {

    private final JobRepository jobRepository;
    private final ScheduledJobRepository scheduledJobRepository;
    private final QueueRepository queueRepository;
    private final com.jobscheduler.distributed_job_scheduler.service.ProjectService projectService; // reuse RBAC checks, same pattern as QueueService
    private final ObjectMapper objectMapper;      // serialize/deserialize the JSON payload column

    public JobService(
            JobRepository jobRepository,
            ScheduledJobRepository scheduledJobRepository,
            QueueRepository queueRepository,
            com.jobscheduler.distributed_job_scheduler.service.ProjectService projectService,
            ObjectMapper objectMapper
    ) {
        this.jobRepository = jobRepository;
        this.scheduledJobRepository = scheduledJobRepository;
        this.queueRepository = queueRepository;
        this.projectService = projectService;
        this.objectMapper = objectMapper;
    }

    /**
     * Shared helper: loads a queue by ID and verifies the current user has at
     * least the given role on the queue's parent project. Same pattern as
     * QueueService.getQueueAndCheckAccess — a queue's access is always
     * inherited from its project, never checked independently.
     */
    private Queue getQueueAndCheckAccess(User currentUser, Long queueId, ProjectMember.Role minimumRole) {
        Queue queue = queueRepository.findById(queueId)
                .orElseThrow(() -> new IllegalArgumentException("Queue not found: " + queueId));

        projectService.requireRole(currentUser, queue.getProject().getId(), minimumRole);
        return queue;
    }

    // --- payload (Map <-> JSON string) helpers, since Job.payload / ScheduledJob.payload are stored as JSON strings ---

    private String writePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Invalid payload: could not serialize to JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPayload(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("Corrupt payload JSON in database", e);
        }
    }
    @Transactional
    public JobResponse createImmediateJob(User currentUser, Long queueId, CreateImmediateJobRequest request) {
        Queue queue = getQueueAndCheckAccess(currentUser, queueId, ProjectMember.Role.MEMBER);

        checkIdempotencyKeyAvailable(request.getIdempotencyKey());

        Job job = new Job();
        job.setQueue(queue);
        job.setType(Job.Type.IMMEDIATE);
        job.setStatus(Job.Status.QUEUED);
        job.setPayload(writePayload(request.getPayload()));
        job.setPriority(request.getPriority() != null ? request.getPriority() : queue.getPriority());
        job.setRetryPolicy(queue.getRetryPolicy());
        job.setMaxAttempts(request.getMaxAttempts() != null ? request.getMaxAttempts() : 5);
        job.setIdempotencyKey(request.getIdempotencyKey());
        job.setRunAfter(LocalDateTime.now()); // immediately claimable

        Job saved = jobRepository.save(job);
        return toJobResponse(saved);
    }

    /**
     * Throws a conflict if a job with this idempotencyKey already exists.
     * Global uniqueness — matches the `unique = true` DB constraint on jobs.idempotency_key.
     * No-op if idempotencyKey is null (it's optional).
     */
    private void checkIdempotencyKeyAvailable(String idempotencyKey) {
        if (idempotencyKey == null) return;
        if (jobRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
            throw new IllegalStateException("A job with idempotencyKey '" + idempotencyKey + "' already exists");
        }
    }

    private JobResponse toJobResponse(Job job) {
        return new JobResponse(
                job.getId(),
                job.getQueue().getId(),
                job.getParentJob() != null ? job.getParentJob().getId() : null,
                job.getType(),
                job.getStatus(),
                readPayload(job.getPayload()),
                job.getPriority(),
                job.getAttemptCount(),
                job.getMaxAttempts(),
                job.getRunAfter(),
                job.getIdempotencyKey(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
    private LocalDateTime toLocalDateTime(java.time.Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private ScheduledJobResponse toScheduledJobResponse(ScheduledJob sj) {
        return new ScheduledJobResponse(
                sj.getId(),
                sj.getQueue().getId(),
                sj.getJobType(),
                readPayload(sj.getPayload()),
                sj.getPriority(),
                sj.getCronExpression(),
                sj.getNextRunTime(),
                sj.getIsRecurring(),
                sj.getPromoted(),
                sj.getCreatedAt()
        );
    }
    @Transactional
    public ScheduledJobResponse createDelayedJob(User currentUser, Long queueId, CreateDelayedJobRequest request) {
        Queue queue = getQueueAndCheckAccess(currentUser, queueId, ProjectMember.Role.MEMBER);

        boolean hasDelay = request.getDelaySeconds() != null;
        boolean hasRunAfter = request.getRunAfter() != null;
        if (hasDelay == hasRunAfter) { // both set, or neither set
            throw new IllegalArgumentException("Exactly one of delaySeconds or runAfter must be provided");
        }

        LocalDateTime nextRunTime = hasDelay
                ? LocalDateTime.now().plusSeconds(request.getDelaySeconds())
                : toLocalDateTime(request.getRunAfter());

        ScheduledJob scheduledJob = new ScheduledJob();
        scheduledJob.setQueue(queue);
        scheduledJob.setJobType(ScheduledJob.JobType.DELAYED);
        scheduledJob.setPayload(writePayload(request.getPayload()));
        scheduledJob.setPriority(request.getPriority() != null ? request.getPriority() : queue.getPriority());
        scheduledJob.setNextRunTime(nextRunTime);
        scheduledJob.setIsRecurring(false);
        scheduledJob.setPromoted(false);

        ScheduledJob saved = scheduledJobRepository.save(scheduledJob);
        return toScheduledJobResponse(saved);
    }
    @Transactional
    public ScheduledJobResponse createScheduledJob(User currentUser, Long queueId, CreateScheduledJobRequest request) {
        Queue queue = getQueueAndCheckAccess(currentUser, queueId, ProjectMember.Role.MEMBER);

        ScheduledJob scheduledJob = new ScheduledJob();
        scheduledJob.setQueue(queue);
        scheduledJob.setJobType(ScheduledJob.JobType.SCHEDULED);
        scheduledJob.setPayload(writePayload(request.getPayload()));
        scheduledJob.setPriority(request.getPriority() != null ? request.getPriority() : queue.getPriority());
        scheduledJob.setNextRunTime(toLocalDateTime(request.getScheduledAt()));
        scheduledJob.setIsRecurring(false);
        scheduledJob.setPromoted(false);

        ScheduledJob saved = scheduledJobRepository.save(scheduledJob);
        return toScheduledJobResponse(saved);
    }
    @Transactional
    public ScheduledJobResponse createCronJob(User currentUser, Long queueId, CreateCronJobRequest request) {
        Queue queue = getQueueAndCheckAccess(currentUser, queueId, ProjectMember.Role.MEMBER);

        org.springframework.scheduling.support.CronExpression cron;
        try {
            cron = org.springframework.scheduling.support.CronExpression.parse(request.getCronExpression());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cronExpression: " + request.getCronExpression());
        }

        LocalDateTime nextRunTime = cron.next(LocalDateTime.now());
        if (nextRunTime == null) {
            throw new IllegalArgumentException("cronExpression has no future occurrences: " + request.getCronExpression());
        }

        ScheduledJob scheduledJob = new ScheduledJob();
        scheduledJob.setQueue(queue);
        scheduledJob.setJobType(ScheduledJob.JobType.CRON);
        scheduledJob.setPayload(writePayload(request.getPayload()));
        scheduledJob.setPriority(request.getPriority() != null ? request.getPriority() : queue.getPriority());
        scheduledJob.setCronExpression(request.getCronExpression());
        scheduledJob.setNextRunTime(nextRunTime);
        scheduledJob.setIsRecurring(true);
        scheduledJob.setPromoted(false);

        ScheduledJob saved = scheduledJobRepository.save(scheduledJob);
        return toScheduledJobResponse(saved);
    }
}