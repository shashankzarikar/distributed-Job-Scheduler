package com.jobscheduler.distributed_job_scheduler.repository;

import com.jobscheduler.distributed_job_scheduler.entity.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface JobRepository extends JpaRepository<Job, Long> {

    /**
     * Atomic job claiming. SELECT ... FOR UPDATE SKIP LOCKED locks the returned rows
     * and skips any rows already locked by another concurrent transaction (another worker).
     * This guarantees two workers can never claim the same job.
     *
     * nativeQuery = true because SKIP LOCKED is not part of standard JPQL.
     */
    @Query(value = """
            SELECT * FROM jobs
            WHERE queue_id = :queueId
              AND status IN ('QUEUED', 'SCHEDULED')
              AND (run_after IS NULL OR run_after <= NOW())
            ORDER BY priority DESC, created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Job> findClaimableJobs(@Param("queueId") Long queueId, @Param("limit") int limit);

    /**
     * Reaper query: finds jobs stuck RUNNING whose worker has gone silent
     * (no heartbeat within the configured timeout window).
     */
    @Query("""
            SELECT j FROM Job j
            WHERE j.status = com.jobscheduler.distributed_job_scheduler.entity.Job.Status.RUNNING
              AND j.lastHeartbeatAt < :threshold
            """)
    List<Job> findStaleRunningJobs(@Param("threshold") LocalDateTime threshold);

    List<Job> findByParentJobId(Long parentJobId);

    List<Job> findByQueueIdAndStatus(Long queueId, Job.Status status);
}