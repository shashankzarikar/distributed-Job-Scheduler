package com.jobscheduler.distributed_job_scheduler.repository;

import com.jobscheduler.distributed_job_scheduler.entity.RetryPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RetryPolicyRepository extends JpaRepository<RetryPolicy, Long> {
}