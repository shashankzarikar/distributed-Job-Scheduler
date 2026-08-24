package com.jobscheduler.distributed_job_scheduler.worker;

import com.jobscheduler.distributed_job_scheduler.dto.websocket.JobEventMessage;
import com.jobscheduler.distributed_job_scheduler.entity.Job;
import com.jobscheduler.distributed_job_scheduler.entity.JobExecution;
import com.jobscheduler.distributed_job_scheduler.entity.Worker;
import com.jobscheduler.distributed_job_scheduler.repository.JobExecutionRepository;
import com.jobscheduler.distributed_job_scheduler.repository.JobRepository;
import com.jobscheduler.distributed_job_scheduler.repository.WorkerRepository;
import com.jobscheduler.distributed_job_scheduler.websocket.EventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class JobLifecycleService {

    private final JobRepository jobRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final WorkerRepository workerRepository;
    private final EventPublisher eventPublisher; // [NEW - Step G]

    public JobLifecycleService(
            JobRepository jobRepository,
            JobExecutionRepository jobExecutionRepository,
            WorkerRepository workerRepository,
            EventPublisher eventPublisher
    ) {
        this.jobRepository = jobRepository;
        this.jobExecutionRepository = jobExecutionRepository;
        this.workerRepository = workerRepository;
        this.eventPublisher = eventPublisher;
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

            eventPublisher.publishJobEvent(job, JobEventMessage.EventType.CLAIMED, workerId, null);
        }
        return claimable;
    }

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
        Long executionId = jobExecutionRepository.save(execution).getId();

        Long workerId = job.getClaimedByWorker() != null ? job.getClaimedByWorker().getId() : null;
        eventPublisher.publishJobEvent(job, JobEventMessage.EventType.RUNNING, workerId, null);

        return executionId;
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
        // Heartbeat ticks deliberately NOT broadcast — they fire every 10s per running job
        // and would flood the topic with no meaningful state change for a dashboard to show.
    }
}