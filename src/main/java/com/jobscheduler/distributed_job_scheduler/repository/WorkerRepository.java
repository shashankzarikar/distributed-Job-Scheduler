package com.jobscheduler.distributed_job_scheduler.repository;

import com.jobscheduler.distributed_job_scheduler.entity.Worker;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface WorkerRepository extends JpaRepository<Worker, Long> {
    List<Worker> findByStatus(Worker.Status status);
}