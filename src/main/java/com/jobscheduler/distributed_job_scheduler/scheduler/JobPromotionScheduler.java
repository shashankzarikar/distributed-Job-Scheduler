package com.jobscheduler.distributed_job_scheduler.scheduler;

import com.jobscheduler.distributed_job_scheduler.entity.Job;
import com.jobscheduler.distributed_job_scheduler.entity.ScheduledJob;
import com.jobscheduler.distributed_job_scheduler.repository.JobRepository;
import com.jobscheduler.distributed_job_scheduler.repository.ScheduledJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Polls scheduled_jobs for rows that are due (next_run_time <= now, promoted = false)
 * and promotes them into real Job rows in the jobs table.
 *
 * IMPORTANT — transaction boundary note:
 * The fetch (findDueForPromotion, which uses SELECT ... FOR UPDATE SKIP LOCKED) and every
 * promotion in this batch run inside ONE @Transactional method. This is deliberate: the row
 * locks from SKIP LOCKED are only useful if they're still held when we flip promoted = true —
 * if the fetch and the promote happened in separate transactions, the lock would be released
 * the instant the fetch call returned, and a second scheduler instance (if one ever ran) could
 * grab and promote the same row twice.
 *
 * Trade-off this creates: a caught exception inside the per-row loop (bad cron syntax, etc.)
 * will NOT roll back the rest of the batch, since we catch it before it propagates. But a
 * genuine DB-level failure (e.g. a flush-triggering constraint violation) can still mark the
 * whole transaction rollback-only and take the batch down with it. True per-row isolation would
 * require separate connections (REQUIRES_NEW), which risks self-deadlocking against the row
 * lock this same transaction is already holding. Acceptable, documented simplification at this
 * project's scale — not something to "fix" without a real reason.
 */
@Slf4j
@Component
public class JobPromotionScheduler {

    private final ScheduledJobRepository scheduledJobRepository;
    private final JobRepository jobRepository;
    private final int batchSize;

    public JobPromotionScheduler(
            ScheduledJobRepository scheduledJobRepository,
            JobRepository jobRepository,
            @Value("${app.scheduler.batch-size:20}") int batchSize) {
        this.scheduledJobRepository = scheduledJobRepository;
        this.jobRepository = jobRepository;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.scheduler.poll-interval-ms:2000}")
    @Transactional
    public void promoteDueJobs() {
        List<ScheduledJob> dueJobs = scheduledJobRepository.findDueForPromotion(batchSize);

        if (dueJobs.isEmpty()) {
            return;
        }

        log.info("Scheduler: found {} due job(s) to promote", dueJobs.size());

        for (ScheduledJob scheduledJob : dueJobs) {
            try {
                promoteOne(scheduledJob);
            } catch (Exception e) {
                // Caught so one malformed row doesn't kill the rest of the batch.
                // See class-level javadoc for the limits of this guarantee.
                log.error("Failed to promote scheduled_jobs id={}: {}",
                        scheduledJob.getId(), e.getMessage(), e);
            }
        }
    }

    private void promoteOne(ScheduledJob scheduledJob) {
        // 1. Create the real Job row.
        Job job = new Job();
        job.setQueue(scheduledJob.getQueue());
        job.setType(Job.Type.valueOf(scheduledJob.getJobType().name()));
        job.setStatus(Job.Status.QUEUED);
        job.setPayload(scheduledJob.getPayload());
        job.setPriority(scheduledJob.getPriority());
        job.setRunAfter(LocalDateTime.now());
        // maxAttempts intentionally left at the entity default (5) — scheduled_jobs has no
        // max_attempts column to carry a custom value from (see decision 3.12).
        jobRepository.save(job);

        // 2. Mark this scheduled_jobs row as promoted and link it to the new Job.
        scheduledJob.setPromoted(true);
        scheduledJob.setPromotedJob(job);
        scheduledJobRepository.save(scheduledJob);

        log.info("Promoted scheduled_jobs id={} -> jobs id={} (type={})",
                scheduledJob.getId(), job.getId(), job.getType());

        // 3. If recurring (CRON), compute the next occurrence and insert a FRESH
        //    scheduled_jobs row for it — never reuse this one, so promotion history stays
        //    auditable (per decision 3.4 / Step E spec in version_2.md).
        if (Boolean.TRUE.equals(scheduledJob.getIsRecurring())) {
            scheduleNextCronOccurrence(scheduledJob);
        }
    }

    private void scheduleNextCronOccurrence(ScheduledJob previous) {
        CronExpression cron = CronExpression.parse(previous.getCronExpression());
        LocalDateTime next = cron.next(previous.getNextRunTime());

        if (next == null) {
            // Cron expression has no future occurrence (shouldn't normally happen with
            // standard 6-field expressions, but guard against it rather than NPE).
            log.warn("Cron expression '{}' for scheduled_jobs id={} produced no next run time; " +
                            "not rescheduling further.",
                    previous.getCronExpression(), previous.getId());
            return;
        }

        ScheduledJob nextOccurrence = new ScheduledJob();
        nextOccurrence.setQueue(previous.getQueue());
        nextOccurrence.setJobType(ScheduledJob.JobType.CRON);
        nextOccurrence.setPayload(previous.getPayload());
        nextOccurrence.setPriority(previous.getPriority());
        nextOccurrence.setCronExpression(previous.getCronExpression());
        nextOccurrence.setNextRunTime(next);
        nextOccurrence.setIsRecurring(true);
        nextOccurrence.setPromoted(false);

        scheduledJobRepository.save(nextOccurrence);

        log.info("Scheduled next occurrence of cron job (from scheduled_jobs id={}) " +
                        "at {} (new scheduled_jobs id={})",
                previous.getId(), next, nextOccurrence.getId());
    }
}