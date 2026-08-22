package com.jobscheduler.distributed_job_scheduler.repository;

import com.jobscheduler.distributed_job_scheduler.entity.JobExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface JobExecutionRepository extends JpaRepository<JobExecution, Long> {
    List<JobExecution> findByJobIdOrderByAttemptNumberAsc(Long jobId);
}