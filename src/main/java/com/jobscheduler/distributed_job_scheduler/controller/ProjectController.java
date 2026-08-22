package com.jobscheduler.distributed_job_scheduler.controller;

import com.jobscheduler.distributed_job_scheduler.dto.project.*;
import com.jobscheduler.distributed_job_scheduler.entity.User;
import com.jobscheduler.distributed_job_scheduler.security.SecurityUtils;
import com.jobscheduler.distributed_job_scheduler.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final SecurityUtils securityUtils;

    public ProjectController(ProjectService projectService, SecurityUtils securityUtils) {
        this.projectService = projectService;
        this.securityUtils = securityUtils;
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> createProject(@Valid @RequestBody CreateProjectRequest request) {
        User currentUser = securityUtils.getCurrentUser();
        ProjectResponse response = projectService.createProject(currentUser, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<ProjectResponse>> listMyProjects() {
        User currentUser = securityUtils.getCurrentUser();
        return ResponseEntity.ok(projectService.listMyProjects(currentUser));
    }

    @PostMapping("/{projectId}/members")
    public ResponseEntity<ProjectMemberResponse> addMember(
            @PathVariable Long projectId,
            @Valid @RequestBody AddMemberRequest request
    ) {
        User currentUser = securityUtils.getCurrentUser();
        ProjectMemberResponse response = projectService.addMember(currentUser, projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{projectId}/members")
    public ResponseEntity<List<ProjectMemberResponse>> listMembers(@PathVariable Long projectId) {
        User currentUser = securityUtils.getCurrentUser();
        return ResponseEntity.ok(projectService.listMembers(currentUser, projectId));
    }
}