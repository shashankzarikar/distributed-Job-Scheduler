package com.jobscheduler.distributed_job_scheduler.worker;

import com.jobscheduler.distributed_job_scheduler.dto.websocket.JobEventMessage;
import com.jobscheduler.distributed_job_scheduler.entity.*;
import com.jobscheduler.distributed_job_scheduler.repository.*;
import com.jobscheduler.distributed_job_scheduler.websocket.EventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Applies the outcome of a job attempt — success, execution failure, or a Reaper-detected
 * timeout — to the job row, its JobExecution record, JobLog entries, and (if it's a batch
 * child) its parent's progress counters. Shared between WorkerEngine and JobReaper so this
 * retry/DLQ/batch-aggregation logic exists in exactly one place.
 *
 * This is a separate bean from WorkerEngine/JobReaper deliberately — see the note in
 * JobLifecycleService about why self-invocation would silently skip @Transactional.
 */
@Slf4j
@Component
public class JobOutcomeHandler {

    private final JobRepository jobRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final JobLogRepository jobLogRepository;
    private final DeadLetterQueueRepository deadLetterQueueRepository;
    private final RetryCalculator retryCalculator;

    private final EventPublisher eventPublisher;

    public JobOutcomeHandler(
            JobRepository jobRepository,
            JobExecutionRepository jobExecutionRepository,
            JobLogRepository jobLogRepository,
            DeadLetterQueueRepository deadLetterQueueRepository,
            RetryCalculator retryCalculator,
            EventPublisher eventPublisher
    ) {
        this.jobRepository = jobRepository;
        this.jobExecutionRepository = jobExecutionRepository;
        this.jobLogRepository = jobLogRepository;
        this.deadLetterQueueRepository = deadLetterQueueRepository;
        this.retryCalculator = retryCalculator;
        this.eventPublisher = eventPublisher;
    }
    @Transactional
    public void handleSuccess(Long jobId, Long executionId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Job disappeared mid-execution: " + jobId));

        job.setStatus(Job.Status.COMPLETED);
        job.setClaimedByWorker(null);
        jobRepository.save(job);

        if (executionId != null) {
            jobExecutionRepository.findById(executionId).ifPresent(exec -> {
                exec.setStatus(JobExecution.Status.SUCCESS);
                exec.setFinishedAt(LocalDateTime.now());
                jobExecutionRepository.save(exec);
            });
        }

        writeLog(job, JobLog.Level.INFO, "Job completed successfully");
        eventPublisher.publishJobEvent(job, JobEventMessage.EventType.COMPLETED, null, null);

        if (job.getParentJob() != null) {
            onChildResolved(job.getParentJob().getId(), true);
        }
    }

    /** Normal execution-path failure — has a live JobExecution row to close out. */
    @Transactional
    public void handleExecutionFailure(Long jobId, Long executionId, String errorMessage) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Job disappeared mid-execution: " + jobId));

        if (executionId != null) {
            jobExecutionRepository.findById(executionId).ifPresent(exec -> {
                exec.setStatus(JobExecution.Status.FAILURE);
                exec.setFinishedAt(LocalDateTime.now());
                exec.setErrorMessage(errorMessage);
                jobExecutionRepository.save(exec);
            });
        }

        applyFailure(job, errorMessage);
    }

    /**
     * Reaper path — the worker that was running this job is presumed dead, so there's no
     * live execution to close out; goes straight to the retry/DLQ decision.
     */
    @Transactional
    public void handleReapedTimeout(Long jobId, String reason) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Job disappeared before reaping: " + jobId));

        applyFailure(job, reason);
    }

    private void applyFailure(Job job, String reason) {
        int newAttemptCount = job.getAttemptCount() + 1;
        job.setAttemptCount(newAttemptCount);

        boolean attemptsExhausted = newAttemptCount >= job.getMaxAttempts();

        if (attemptsExhausted) {
            job.setStatus(Job.Status.DEAD_LETTER);
            job.setClaimedByWorker(null);
            jobRepository.save(job);

            DeadLetterQueue dlq = new DeadLetterQueue();
            dlq.setJob(job);
            dlq.setReason(reason + " (attempts exhausted: " + newAttemptCount + "/" + job.getMaxAttempts() + ")");
            deadLetterQueueRepository.save(dlq);

            writeLog(job, JobLog.Level.ERROR,
                    "Job moved to Dead Letter Queue after " + newAttemptCount + " attempt(s): " + reason);
            eventPublisher.publishJobEvent(job, JobEventMessage.EventType.DEAD_LETTER, null, reason);

            if (job.getParentJob() != null) {
                onChildResolved(job.getParentJob().getId(), false);
            }
        } else {
            int delaySeconds = retryCalculator.nextDelaySeconds(job.getRetryPolicy(), newAttemptCount);
            job.setStatus(Job.Status.QUEUED);
            job.setClaimedByWorker(null);
            job.setRunAfter(LocalDateTime.now().plusSeconds(delaySeconds));
            jobRepository.save(job);

            String detail = "Attempt " + newAttemptCount + "/" + job.getMaxAttempts() + " failed (" + reason
                    + "); retrying in " + delaySeconds + "s";
            writeLog(job, JobLog.Level.WARN, detail);
            eventPublisher.publishJobEvent(job, JobEventMessage.EventType.RETRY_SCHEDULED, null, detail);
        }
    }

    /**
     * Updates a batch parent's completed_children/failed_children counters when a child
     * resolves, and derives the parent's overall status once every child is accounted for.
     * (Fills in the gap flagged as unimplemented in 6.7 / decision 3.13.)
     */
    private void onChildResolved(Long parentJobId, boolean succeeded) {
        Job parent = jobRepository.findById(parentJobId)
                .orElseThrow(() -> new IllegalStateException("Parent job not found: " + parentJobId));

        if (succeeded) {
            parent.setCompletedChildren(parent.getCompletedChildren() + 1);
        } else {
            parent.setFailedChildren(parent.getFailedChildren() + 1);
        }

        int resolvedCount = parent.getCompletedChildren() + parent.getFailedChildren();
        boolean justResolved = false;
        if (resolvedCount >= parent.getTotalChildren()) {
            if (parent.getFailedChildren() == 0) {
                parent.setStatus(Job.Status.COMPLETED);
            } else if (parent.getCompletedChildren() == 0) {
                parent.setStatus(Job.Status.FAILED);
            } else {
                parent.setStatus(Job.Status.PARTIALLY_FAILED);
            }
            justResolved = true;
        }

        jobRepository.save(parent);

        if (justResolved) {
            eventPublisher.publishJobEvent(parent, JobEventMessage.EventType.BATCH_RESOLVED, null, null);
        }
    }

    private void writeLog(Job job, JobLog.Level level, String message) {
        JobLog log = new JobLog();
        log.setJob(job);
        log.setLevel(level);
        log.setMessage(message);
        jobLogRepository.save(log);
    }
}