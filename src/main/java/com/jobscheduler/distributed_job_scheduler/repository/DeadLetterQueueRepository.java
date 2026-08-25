package com.jobscheduler.distributed_job_scheduler.repository;

import com.jobscheduler.distributed_job_scheduler.entity.DeadLetterQueue;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DeadLetterQueueRepository extends JpaRepository<DeadLetterQueue, Long> {
    Optional<DeadLetterQueue> findByJobId(Long jobId);
    List<DeadLetterQueue> findByJob_Queue_Id(Long queueId);
}