package com.jobscheduler.distributed_job_scheduler.worker;

import com.jobscheduler.distributed_job_scheduler.entity.Job;
import com.jobscheduler.distributed_job_scheduler.entity.Worker;
import com.jobscheduler.distributed_job_scheduler.repository.JobRepository;
import com.jobscheduler.distributed_job_scheduler.repository.WorkerRepository;
import com.jobscheduler.distributed_job_scheduler.websocket.EventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Detects jobs stuck in RUNNING whose worker stopped sending heartbeats (crashed, killed,
 * or otherwise died mid-execution). Marks the owning Worker UNRESPONSIVE and routes the job
 * through JobOutcomeHandler's normal retry-or-DLQ decision, same as an execution failure.
 */
@Slf4j
@Component
public class JobReaper {

    private final JobRepository jobRepository;
    private final WorkerRepository workerRepository;
    private final JobOutcomeHandler outcomeHandler;
    private final int timeoutSeconds;
    private final EventPublisher eventPublisher;

    public JobReaper(
            JobRepository jobRepository,
            WorkerRepository workerRepository,
            JobOutcomeHandler outcomeHandler,
            EventPublisher eventPublisher, // [NEW - Step G]
            @Value("${app.worker.heartbeat-timeout-seconds:30}") int timeoutSeconds
    ) {
        this.jobRepository = jobRepository;
        this.workerRepository = workerRepository;
        this.outcomeHandler = outcomeHandler;
        this.eventPublisher = eventPublisher;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Scheduled(fixedDelayString = "${app.worker.reaper-interval-ms:15000}")
    public void reapStaleJobs() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(timeoutSeconds);
        List<Job> staleJobs = jobRepository.findStaleRunningJobs(threshold);

        if (staleJobs.isEmpty()) return;

        log.warn("Reaper: found {} stale RUNNING job(s) past heartbeat timeout ({}s)",
                staleJobs.size(), timeoutSeconds);

        for (Job job : staleJobs) {
            try {
                markWorkerUnresponsive(job);
                outcomeHandler.handleReapedTimeout(job.getId(),
                        "Worker heartbeat timeout (no heartbeat for " + timeoutSeconds + "s)");
            } catch (Exception e) {
                log.error("Failed to reap job id={}: {}", job.getId(), e.getMessage(), e);
            }
        }
    }

    // No @Transactional needed here — findById/save are each independently transactional
    // via Spring Data JPA's own repository proxy, and we're not combining them with
    // anything else that needs atomicity as a unit.
    private void markWorkerUnresponsive(Job job) {
        if (job.getClaimedByWorker() == null) return;

        Long workerId = job.getClaimedByWorker().getId();
        workerRepository.findById(workerId).ifPresent(w -> {
            if (w.getStatus() == Worker.Status.ACTIVE) {
                w.setStatus(Worker.Status.UNRESPONSIVE);
                workerRepository.save(w);
                eventPublisher.publishWorkerEvent(w);
                log.warn("Marked worker id={} UNRESPONSIVE (stale job id={})", workerId, job.getId());
            }
        });
    }
}