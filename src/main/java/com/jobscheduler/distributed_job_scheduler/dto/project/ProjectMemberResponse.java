package com.jobscheduler.distributed_job_scheduler.dto.project;

import com.jobscheduler.distributed_job_scheduler.entity.ProjectMember;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ProjectMemberResponse {
    private Long userId;
    private String name;
    private String email;
    private ProjectMember.Role role;
}