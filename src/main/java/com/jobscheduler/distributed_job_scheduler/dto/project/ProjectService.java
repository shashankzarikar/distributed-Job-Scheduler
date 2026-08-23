package com.jobscheduler.distributed_job_scheduler.service;

import com.jobscheduler.distributed_job_scheduler.dto.project.*;
import com.jobscheduler.distributed_job_scheduler.entity.*;
import com.jobscheduler.distributed_job_scheduler.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final OrganizationRepository organizationRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;

    public ProjectService(
            ProjectRepository projectRepository,
            OrganizationRepository organizationRepository,
            ProjectMemberRepository projectMemberRepository,
            UserRepository userRepository
    ) {
        this.projectRepository = projectRepository;
        this.organizationRepository = organizationRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.userRepository = userRepository;
    }

    /**
     * Creates a project. If the user doesn't own an organization yet, one is
     * auto-created for them (keeps onboarding simple — no separate "create org" step
     * required before creating your first project).
     * The creator is automatically added as OWNER of the new project.
     */
    @Transactional
    public ProjectResponse createProject(User currentUser, CreateProjectRequest request) {
        Organization org = organizationRepository.findAll().stream()
                .filter(o -> o.getOwner().getId().equals(currentUser.getId()))
                .findFirst()
                .orElseGet(() -> {
                    Organization newOrg = new Organization();
                    newOrg.setName(currentUser.getName() + "'s Organization");
                    newOrg.setOwner(currentUser);
                    return organizationRepository.save(newOrg);
                });

        Project project = new Project();
        project.setOrganization(org);
        project.setName(request.getName());
        Project saved = projectRepository.save(project);

        ProjectMember membership = new ProjectMember();
        membership.setProject(saved);
        membership.setUser(currentUser);
        membership.setRole(ProjectMember.Role.OWNER);
        projectMemberRepository.save(membership);

        return new ProjectResponse(saved.getId(), saved.getName(), org.getId(), "OWNER", saved.getCreatedAt());
    }

    /**
     * Lists only the projects the current user is actually a member of.
     */
    @Transactional(readOnly = true)
    public List<ProjectResponse> listMyProjects(User currentUser) {
        return projectMemberRepository.findAll().stream()
                .filter(pm -> pm.getUser().getId().equals(currentUser.getId()))
                .map(pm -> new ProjectResponse(
                        pm.getProject().getId(),
                        pm.getProject().getName(),
                        pm.getProject().getOrganization().getId(),
                        pm.getRole().name(),
                        pm.getProject().getCreatedAt()
                ))
                .toList();
    }

    /**
     * Adds a member to a project. Only an OWNER of the project may do this —
     * enforced here at the service layer, not via Spring Security roles,
     * since RBAC in this system is per-project, not global.
     */
    @Transactional
    public ProjectMemberResponse addMember(User currentUser, Long projectId, AddMemberRequest request) {
        requireRole(currentUser, projectId, ProjectMember.Role.OWNER);

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        User targetUser = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("No user found with email: " + request.getEmail()));

        if (projectMemberRepository.existsByProjectIdAndUserId(projectId, targetUser.getId())) {
            throw new IllegalArgumentException("User is already a member of this project");
        }

        ProjectMember member = new ProjectMember();
        member.setProject(project);
        member.setUser(targetUser);
        member.setRole(request.getRole());
        projectMemberRepository.save(member);

        return new ProjectMemberResponse(targetUser.getId(), targetUser.getName(), targetUser.getEmail(), member.getRole());
    }
    @Transactional(readOnly = true)
    public List<ProjectMemberResponse> listMembers(User currentUser, Long projectId) {
        requireMembership(currentUser, projectId); // any role can view the member list

        return projectMemberRepository.findAll().stream()
                .filter(pm -> pm.getProject().getId().equals(projectId))
                .map(pm -> new ProjectMemberResponse(
                        pm.getUser().getId(), pm.getUser().getName(), pm.getUser().getEmail(), pm.getRole()
                ))
                .toList();
    }

    /**
     * Core RBAC check used throughout the system: does this user have at least
     * this role (or higher) on this project? OWNER > MEMBER > VIEWER.
     */
    public void requireRole(User user, Long projectId, ProjectMember.Role minimumRole) {
        ProjectMember membership = projectMemberRepository.findByProjectIdAndUserId(projectId, user.getId())
                .orElseThrow(() -> new SecurityException("You are not a member of this project"));

        if (!hasAtLeastRole(membership.getRole(), minimumRole)) {
            throw new SecurityException("You do not have permission to perform this action");
        }
    }

    public void requireMembership(User user, Long projectId) {
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, user.getId())) {
            throw new SecurityException("You are not a member of this project");
        }
    }

    private boolean hasAtLeastRole(ProjectMember.Role actual, ProjectMember.Role required) {
        // OWNER=2, MEMBER=1, VIEWER=0 — higher number = more permissions
        return roleRank(actual) >= roleRank(required);
    }

    private int roleRank(ProjectMember.Role role) {
        return switch (role) {
            case OWNER -> 2;
            case MEMBER -> 1;
            case VIEWER -> 0;
        };
    }
}