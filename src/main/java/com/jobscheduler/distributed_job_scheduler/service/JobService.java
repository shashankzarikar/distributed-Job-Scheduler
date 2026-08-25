package com.jobscheduler.distributed_job_scheduler.service;

import com.jobscheduler.distributed_job_scheduler.dto.job.*;
import com.jobscheduler.distributed_job_scheduler.dto.websocket.JobEventMessage;
import com.jobscheduler.distributed_job_scheduler.entity.*;
import com.jobscheduler.distributed_job_scheduler.repository.DeadLetterQueueRepository;
import com.jobscheduler.distributed_job_scheduler.repository.JobRepository;
import com.jobscheduler.distributed_job_scheduler.repository.QueueRepository;
import com.jobscheduler.distributed_job_scheduler.repository.ScheduledJobRepository;
import com.jobscheduler.distributed_job_scheduler.websocket.EventPublisher;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Service
public class JobService {

    private final JobRepository jobRepository;
    private final ScheduledJobRepository scheduledJobRepository;
    private final QueueRepository queueRepository;
    private final DeadLetterQueueRepository deadLetterQueueRepository;
    private final com.jobscheduler.distributed_job_scheduler.service.ProjectService projectService; // reuse RBAC checks, same pattern as QueueService
    private final ObjectMapper objectMapper;      // serialize/deserialize the JSON payload column
    private final EventPublisher eventPublisher;

    public JobService(
            JobRepository jobRepository,
            ScheduledJobRepository scheduledJobRepository,
            QueueRepository queueRepository,
            DeadLetterQueueRepository deadLetterQueueRepository,
            com.jobscheduler.distributed_job_scheduler.service.ProjectService projectService,
            ObjectMapper objectMapper,
            EventPublisher eventPublisher
    ) {
        this.jobRepository = jobRepository;
        this.scheduledJobRepository = scheduledJobRepository;
        this.queueRepository = queueRepository;
        this.deadLetterQueueRepository = deadLetterQueueRepository;
        this.projectService = projectService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
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
    @Transactional
    public JobResponse createBatchJob(User currentUser, Long queueId, CreateBatchJobRequest request) {
        Queue queue = getQueueAndCheckAccess(currentUser, queueId, ProjectMember.Role.MEMBER);

        // Parent job — holds no payload of its own, just tracks overall batch progress
        Job parent = new Job();
        parent.setQueue(queue);
        parent.setType(Job.Type.BATCH);
        parent.setStatus(Job.Status.QUEUED);
        parent.setPriority(request.getPriority() != null ? request.getPriority() : queue.getPriority());
        parent.setRetryPolicy(queue.getRetryPolicy());
        parent.setMaxAttempts(request.getMaxAttempts() != null ? request.getMaxAttempts() : 5);
        parent.setRunAfter(LocalDateTime.now());
        parent.setTotalChildren(request.getChildren().size());

        Job savedParent = jobRepository.save(parent);

        for (ChildJobPayload childPayload : request.getChildren()) {
            checkIdempotencyKeyAvailable(null); // children don't carry idempotencyKey per current DTO — no-op, kept for clarity

            Job child = new Job();
            child.setQueue(queue);
            child.setParentJob(savedParent);
            child.setType(Job.Type.IMMEDIATE); // children execute as ordinary immediate jobs
            child.setStatus(Job.Status.QUEUED);
            child.setPayload(writePayload(childPayload.getPayload()));
            child.setPriority(childPayload.getPriority() != null ? childPayload.getPriority() : parent.getPriority());
            child.setRetryPolicy(queue.getRetryPolicy());
            child.setMaxAttempts(childPayload.getMaxAttempts() != null ? childPayload.getMaxAttempts() : parent.getMaxAttempts());
            child.setRunAfter(LocalDateTime.now());

            jobRepository.save(child);
        }

        return toJobResponse(savedParent);
    }
    @Transactional(readOnly = true)
    public Page<JobResponse> listJobs(User currentUser, Long queueId, Job.Status statusFilter, Pageable pageable) {
        Queue queue = getQueueAndCheckAccess(currentUser, queueId, ProjectMember.Role.VIEWER);

        Page<Job> jobs = (statusFilter != null)
                ? jobRepository.findByQueueIdAndStatus(queue.getId(), statusFilter, pageable)
                : jobRepository.findByQueueId(queue.getId(), pageable);

        return jobs.map(this::toJobResponse);
    }

    /**
     * Lists dead-lettered jobs for a queue. Filters to jobs whose status is
     * *currently* DEAD_LETTER — a job that was manually retried and has since
     * succeeded (or dead-lettered a second time) shouldn't clutter the active
     * DLQ view with its stale first-attempt record.
     */
    @Transactional(readOnly = true)
    public List<DeadLetterQueueResponse> listDeadLetterQueue(User currentUser, Long queueId) {
        Queue queue = getQueueAndCheckAccess(currentUser, queueId, ProjectMember.Role.VIEWER);

        return deadLetterQueueRepository.findByJob_Queue_Id(queue.getId()).stream()
                .filter(dlq -> dlq.getJob().getStatus() == Job.Status.DEAD_LETTER)
                .map(this::toDeadLetterQueueResponse)
                .toList();
    }

    /**
     * Manually retries a dead-lettered job: resets it to QUEUED with a clean
     * attempt count (a manual retry is a deliberate human decision to give the
     * job a fresh full set of attempts, not just "undo the last failure") and
     * marks the DLQ record as retriedManually. Broadcasts the same
     * RETRY_SCHEDULED event the automatic retry path uses (JobOutcomeHandler),
     * so queue.html's live feed picks this up with no extra frontend work.
     *
     * Known edge case, not fixed here (out of scope for this endpoint): if the
     * retried job fails and exhausts attempts again, JobOutcomeHandler.applyFailure
     * will attempt to INSERT a second DeadLetterQueue row for the same job_id,
     * which violates DeadLetterQueue's unique job_id constraint. Flagged as a
     * follow-up for JobOutcomeHandler, not addressed in this change.
     */
    @Transactional
    public JobResponse retryDeadLetterJob(User currentUser, Long jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        projectService.requireRole(currentUser, job.getQueue().getProject().getId(), ProjectMember.Role.MEMBER);

        if (job.getStatus() != Job.Status.DEAD_LETTER) {
            throw new IllegalStateException(
                    "Only jobs in DEAD_LETTER status can be manually retried (current status: " + job.getStatus() + ")");
        }

        DeadLetterQueue dlq = deadLetterQueueRepository.findByJobId(jobId)
                .orElseThrow(() -> new IllegalStateException("No dead-letter-queue record found for job: " + jobId));

        job.setStatus(Job.Status.QUEUED);
        job.setAttemptCount(0);
        job.setClaimedByWorker(null);
        job.setRunAfter(LocalDateTime.now());
        Job saved = jobRepository.save(job);

        dlq.setRetriedManually(true);
        deadLetterQueueRepository.save(dlq);

        eventPublisher.publishJobEvent(saved, JobEventMessage.EventType.RETRY_SCHEDULED, null,
                "Manually retried from Dead Letter Queue");

        return toJobResponse(saved);
    }

    private DeadLetterQueueResponse toDeadLetterQueueResponse(DeadLetterQueue dlq) {
        Job job = dlq.getJob();
        return new DeadLetterQueueResponse(
                dlq.getId(),
                job.getId(),
                job.getQueue().getId(),
                job.getType(),
                readPayload(job.getPayload()),
                job.getAttemptCount(),
                job.getMaxAttempts(),
                dlq.getReason(),
                dlq.getMovedAt(),
                dlq.getRetriedManually()
        );
    }
}