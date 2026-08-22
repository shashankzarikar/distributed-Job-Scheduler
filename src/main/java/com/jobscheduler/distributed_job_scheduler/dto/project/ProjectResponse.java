package com.jobscheduler.distributed_job_scheduler.dto.project;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class ProjectResponse {
    private Long id;
    private String name;
    private Long organizationId;
    private String yourRole; // OWNER / MEMBER / VIEWER — for the requesting user
    private LocalDateTime createdAt;
}