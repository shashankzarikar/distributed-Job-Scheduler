package com.jobscheduler.distributed_job_scheduler.worker;

import com.jobscheduler.distributed_job_scheduler.entity.Job;
import com.jobscheduler.distributed_job_scheduler.entity.JobExecution;
import com.jobscheduler.distributed_job_scheduler.entity.Worker;
import com.jobscheduler.distributed_job_scheduler.repository.JobExecutionRepository;
import com.jobscheduler.distributed_job_scheduler.repository.JobRepository;
import com.jobscheduler.distributed_job_scheduler.repository.WorkerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Transactional claim/start/heartbeat operations, called by WorkerEngine.
 *
 * IMPORTANT — this is deliberately a separate Spring bean from WorkerEngine, not a set of
 * @Transactional methods on WorkerEngine itself. Spring's @Transactional works via a proxy
 * wrapping the bean; calling an @Transactional method on `this` from another method in the
 * SAME class bypasses the proxy entirely and silently runs with no transaction at all. That
 * would be a real correctness bug here specifically — claimJobs relies on the SKIP LOCKED
 * row locks staying held through the update step (same reasoning as JobPromotionScheduler's
 * decision 3.18), and markRunning needs the CLAIMED->RUNNING transition and its JobExecution
 * insert to be atomic. Keeping this as its own bean sidesteps the self-invocation trap.
 */
@Service
public class JobLifecycleService {

    private final JobRepository jobRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final WorkerRepository workerRepository;

    public JobLifecycleService(
            JobRepository jobRepository,
            JobExecutionRepository jobExecutionRepository,
            WorkerRepository workerRepository
    ) {
        this.jobRepository = jobRepository;
        this.jobExecutionRepository = jobExecutionRepository;
        this.workerRepository = workerRepository;
    }

    @Transactional
    public List<Job> claimJobs(Long queueId, Long workerId, int limit) {
        List<Job> claimable = jobRepository.findClaimableJobs(queueId, limit);
        Worker worker = workerRepository.findById(workerId)
                .orElseThrow(() -> new IllegalStateException("Worker not found: " + workerId));

        for (Job job : claimable) {
            job.setStatus(Job.Status.CLAIMED);
            job.setClaimedByWorker(worker);
            job.setClaimedAt(LocalDateTime.now());
            jobRepository.save(job);
        }
        return claimable;
    }

    /**
     * @return the new JobExecution's id, or null if the job was no longer CLAIMED
     * (defensive guard — shouldn't normally happen given how quickly this runs after claiming)
     */
    @Transactional
    public Long markRunning(Long jobId) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != Job.Status.CLAIMED) {
            return null;
        }

        job.setStatus(Job.Status.RUNNING);
        job.setLastHeartbeatAt(LocalDateTime.now());
        jobRepository.save(job);

        JobExecution execution = new JobExecution();
        execution.setJob(job);
        execution.setWorker(job.getClaimedByWorker());
        execution.setAttemptNumber(job.getAttemptCount() + 1);
        execution.setStatus(JobExecution.Status.RUNNING);
        return jobExecutionRepository.save(execution).getId();
    }

    @Transactional
    public void updateHeartbeat(Long jobId, Long workerId) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setLastHeartbeatAt(LocalDateTime.now());
            jobRepository.save(job);
        });
        workerRepository.findById(workerId).ifPresent(w -> {
            w.setLastHeartbeatAt(LocalDateTime.now());
            workerRepository.save(w);
        });
    }
}