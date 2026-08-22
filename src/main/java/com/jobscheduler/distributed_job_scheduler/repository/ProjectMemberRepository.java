package com.jobscheduler.distributed_job_scheduler.repository;

import com.jobscheduler.distributed_job_scheduler.entity.ProjectMember;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {
    Optional<ProjectMember> findByProjectIdAndUserId(Long projectId, Long userId);
    boolean existsByProjectIdAndUserId(Long projectId, Long userId);
}