package com.jobscheduler.distributed_job_scheduler.repository;

import com.jobscheduler.distributed_job_scheduler.entity.Job;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface JobRepository extends JpaRepository<Job, Long> {

    // While Work Engine Work  Added "AND type != 'BATCH'" — batch parent rows are pure aggregators,
    // never meant to be claimed/executed themselves. Their status is derived from their
    // children (see JobOutcomeHandler.onChildResolved), not run through the worker pool.
    @Query(value = """
            SELECT * FROM jobs
            WHERE queue_id = :queueId
              AND status IN ('QUEUED', 'SCHEDULED')
              AND type != 'BATCH'
              AND (run_after IS NULL OR run_after <= NOW())
            ORDER BY priority DESC, created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Job> findClaimableJobs(@Param("queueId") Long queueId, @Param("limit") int limit);

    // While Work Engine Work  Added "LEFT JOIN FETCH j.claimedByWorker" — JobReaper reads
    // job.getClaimedByWorker() after this query's own transaction has already closed,
    // which would otherwise throw LazyInitializationException (same class of bug as the
    // one already fixed in ProjectService — see 5.6 in the state doc).
    @Query("""
            SELECT j FROM Job j
            LEFT JOIN FETCH j.claimedByWorker
            WHERE j.status = com.jobscheduler.distributed_job_scheduler.entity.Job.Status.RUNNING
              AND j.lastHeartbeatAt < :threshold
            """)
    List<Job> findStaleRunningJobs(@Param("threshold") LocalDateTime threshold);

    List<Job> findByParentJobId(Long parentJobId);

    List<Job> findByQueueIdAndStatus(Long queueId, Job.Status status);

    // Job Explorer / listing endpoint — paginated, optionally filtered by status
    Page<Job> findByQueueId(Long queueId, Pageable pageable);

    Page<Job> findByQueueIdAndStatus(Long queueId, Job.Status status, Pageable pageable);

    // Idempotency check — used by JobService before creating a new job
    Optional<Job> findByIdempotencyKey(String idempotencyKey);

    // [NEW - While Work Engine Work] Used by WorkerEngine.pollQueue to compute remaining capacity
    // against Queue.concurrencyLimit before claiming more jobs for that queue.
    long countByQueueIdAndStatusIn(Long queueId, List<Job.Status> statuses);
}