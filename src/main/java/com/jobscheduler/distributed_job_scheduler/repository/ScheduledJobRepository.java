package com.jobscheduler.distributed_job_scheduler.repository;

import com.jobscheduler.distributed_job_scheduler.entity.ScheduledJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ScheduledJobRepository extends JpaRepository<ScheduledJob, Long> {

    /**
     * Used by the Scheduler poller to find delayed/cron jobs ready to be
     * promoted into the live `jobs` table. Locked the same way as job claiming,
     * so this stays safe even if the app scales to multiple instances later.
     */
    @Query(value = """
            SELECT * FROM scheduled_jobs
            WHERE promoted = false
              AND next_run_time <= NOW()
            ORDER BY next_run_time ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ScheduledJob> findDueForPromotion(@Param("limit") int limit);
}