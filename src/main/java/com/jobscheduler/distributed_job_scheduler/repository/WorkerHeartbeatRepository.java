package com.jobscheduler.distributed_job_scheduler.repository;

import com.jobscheduler.distributed_job_scheduler.entity.WorkerHeartbeat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerHeartbeatRepository extends JpaRepository<WorkerHeartbeat, Long> {
}