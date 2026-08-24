package com.jobscheduler.distributed_job_scheduler.repository;

import com.jobscheduler.distributed_job_scheduler.entity.Queue;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface QueueRepository extends JpaRepository<Queue, Long> {
    List<Queue> findByProjectId(Long projectId);

    // WorkerEngine polls across ALL active queues system-wide (a worker
    // isn't scoped to one project) — paused queues are correctly skipped.
    List<Queue> findByStatus(Queue.Status status);
}