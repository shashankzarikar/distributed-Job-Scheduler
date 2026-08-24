package com.jobscheduler.distributed_job_scheduler.worker;

import com.jobscheduler.distributed_job_scheduler.entity.Job;
import com.jobscheduler.distributed_job_scheduler.entity.Queue;
import com.jobscheduler.distributed_job_scheduler.entity.Worker;
import com.jobscheduler.distributed_job_scheduler.repository.QueueRepository;
import com.jobscheduler.distributed_job_scheduler.repository.JobRepository;
import com.jobscheduler.distributed_job_scheduler.repository.WorkerRepository;
import com.jobscheduler.distributed_job_scheduler.websocket.EventPublisher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * The worker engine: a single worker instance (see decision — single instance, multi-
 * threaded pool, chosen for time budget over multiple concurrent worker instances) that
 * polls all ACTIVE queues, atomically claims jobs via SKIP LOCKED, executes them concurrently
 * on a fixed thread pool, and sends heartbeats while jobs run.
 */
@Slf4j
@Component
public class WorkerEngine {

    private final WorkerRepository workerRepository;
    private final QueueRepository queueRepository;
    private final JobRepository jobRepository;
    private final JobExecutor jobExecutor;
    private final JobOutcomeHandler outcomeHandler;
    private final JobLifecycleService lifecycleService;
    private final EventPublisher eventPublisher; // [NEW - Step G]

    private final int poolSize;
    private final long heartbeatIntervalMs;

    private ExecutorService executorService;
    private ScheduledExecutorService heartbeatScheduler;
    private Long workerId;
    private volatile boolean shuttingDown = false;

    public WorkerEngine(
            WorkerRepository workerRepository,
            QueueRepository queueRepository,
            JobRepository jobRepository,
            JobExecutor jobExecutor,
            JobOutcomeHandler outcomeHandler,
            JobLifecycleService lifecycleService,
            EventPublisher eventPublisher,
            @Value("${app.worker.pool-size:5}") int poolSize,
            @Value("${app.worker.heartbeat-interval-ms:10000}") long heartbeatIntervalMs
    ) {
        this.workerRepository = workerRepository;
        this.queueRepository = queueRepository;
        this.jobRepository = jobRepository;
        this.jobExecutor = jobExecutor;
        this.outcomeHandler = outcomeHandler;
        this.lifecycleService = lifecycleService;
        this.eventPublisher = eventPublisher;
        this.poolSize = poolSize;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
    }

    @PostConstruct
    public void start() {
        executorService = Executors.newFixedThreadPool(poolSize);
        // Separate small pool for per-job heartbeat ticks so they never compete with
        // the main execution pool for a thread.
        heartbeatScheduler = Executors.newScheduledThreadPool(Math.max(1, poolSize / 2));

        Worker worker = new Worker();
        worker.setName("worker-" + UUID.randomUUID().toString().substring(0, 8));
        worker.setStatus(Worker.Status.ACTIVE);
        worker.setLastHeartbeatAt(LocalDateTime.now());
        worker = workerRepository.save(worker);
        this.workerId = worker.getId();

        eventPublisher.publishWorkerEvent(worker);
        log.info("WorkerEngine started: workerId={}, poolSize={}", workerId, poolSize);
    }

    @PreDestroy
    public void shutdown() {
        shuttingDown = true;
        log.info("WorkerEngine shutting down (workerId={})...", workerId);

        heartbeatScheduler.shutdown();
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Worker pool did not terminate within 30s; forcing shutdown of in-flight jobs");
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        workerRepository.findById(workerId).ifPresent(w -> {
            w.setStatus(Worker.Status.SHUTDOWN);
            workerRepository.save(w);
            eventPublisher.publishWorkerEvent(w);
        });

        log.info("WorkerEngine shut down cleanly (workerId={})", workerId);
    }

    @Scheduled(fixedDelayString = "${app.worker.poll-interval-ms:2000}")
    public void poll() {
        if (shuttingDown) return;

        List<Queue> activeQueues = queueRepository.findByStatus(Queue.Status.ACTIVE);
        for (Queue queue : activeQueues) {
            try {
                pollQueue(queue);
            } catch (Exception e) {
                log.error("Error polling queue {}: {}", queue.getId(), e.getMessage(), e);
            }
        }
    }

    private void pollQueue(Queue queue) {
        long inFlight = jobRepository.countByQueueIdAndStatusIn(
                queue.getId(), List.of(Job.Status.CLAIMED, Job.Status.RUNNING));
        int capacity = queue.getConcurrencyLimit() - (int) inFlight;
        if (capacity <= 0) return;

        List<Job> claimed = lifecycleService.claimJobs(queue.getId(), workerId, capacity);
        for (Job job : claimed) {
            executorService.submit(() -> processJob(job.getId()));
        }
    }

    private void processJob(Long jobId) {
        Long executionId = lifecycleService.markRunning(jobId);
        if (executionId == null) return; // already handled elsewhere, or gone

        ScheduledFuture<?> heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(
                () -> lifecycleService.updateHeartbeat(jobId, workerId),
                heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);

        try {
            Job job = jobRepository.findById(jobId).orElse(null);
            if (job == null) return;

            jobExecutor.execute(job);
            outcomeHandler.handleSuccess(jobId, executionId);
        } catch (JobExecutor.JobExecutionFailedException e) {
            outcomeHandler.handleExecutionFailure(jobId, executionId, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error executing job {}: {}", jobId, e.getMessage(), e);
            outcomeHandler.handleExecutionFailure(jobId, executionId, "Unexpected error: " + e.getMessage());
        } finally {
            heartbeatTask.cancel(false);
        }
    }
}