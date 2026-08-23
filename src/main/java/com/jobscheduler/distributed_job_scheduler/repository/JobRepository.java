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

    @Query("""
            SELECT j FROM Job j
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
}