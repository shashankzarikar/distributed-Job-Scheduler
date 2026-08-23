package com.jobscheduler.distributed_job_scheduler.service;

import com.jobscheduler.distributed_job_scheduler.dto.queue.CreateQueueRequest;
import com.jobscheduler.distributed_job_scheduler.dto.queue.QueueResponse;
import com.jobscheduler.distributed_job_scheduler.dto.queue.QueueStatsResponse;
import com.jobscheduler.distributed_job_scheduler.entity.*;
import com.jobscheduler.distributed_job_scheduler.repository.JobRepository;
import com.jobscheduler.distributed_job_scheduler.repository.ProjectRepository;
import com.jobscheduler.distributed_job_scheduler.repository.QueueRepository;
import com.jobscheduler.distributed_job_scheduler.repository.RetryPolicyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class QueueService {

    private final QueueRepository queueRepository;
    private final ProjectRepository projectRepository;
    private final RetryPolicyRepository retryPolicyRepository;
    private final JobRepository jobRepository;
    private final com.jobscheduler.distributed_job_scheduler.service.ProjectService projectService; // reuse RBAC checks

    public QueueService(
            QueueRepository queueRepository,
            ProjectRepository projectRepository,
            RetryPolicyRepository retryPolicyRepository,
            JobRepository jobRepository,
            com.jobscheduler.distributed_job_scheduler.service.ProjectService projectService
    ) {
        this.queueRepository = queueRepository;
        this.projectRepository = projectRepository;
        this.retryPolicyRepository = retryPolicyRepository;
        this.jobRepository = jobRepository;
        this.projectService = projectService;
    }

    @Transactional
    public QueueResponse createQueue(User currentUser, Long projectId, CreateQueueRequest request) {
        projectService.requireRole(currentUser, projectId, ProjectMember.Role.MEMBER);

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        RetryPolicy retryPolicy = null;
        if (request.getRetryPolicyId() != null) {
            retryPolicy = retryPolicyRepository.findById(request.getRetryPolicyId())
                    .orElseThrow(() -> new IllegalArgumentException("Retry policy not found: " + request.getRetryPolicyId()));
        }

        Queue queue = new Queue();
        queue.setProject(project);
        queue.setName(request.getName());
        queue.setPriority(request.getPriority());
        queue.setConcurrencyLimit(request.getConcurrencyLimit());
        queue.setRetryPolicy(retryPolicy);
        queue.setStatus(Queue.Status.ACTIVE);

        Queue saved = queueRepository.save(queue);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<QueueResponse> listQueues(User currentUser, Long projectId) {
        projectService.requireMembership(currentUser, projectId);

        return queueRepository.findByProjectId(projectId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public QueueResponse pauseQueue(User currentUser, Long queueId) {
        Queue queue = getQueueAndCheckAccess(currentUser, queueId, ProjectMember.Role.MEMBER);
        queue.setStatus(Queue.Status.PAUSED);
        return toResponse(queueRepository.save(queue));
    }

    @Transactional
    public QueueResponse resumeQueue(User currentUser, Long queueId) {
        Queue queue = getQueueAndCheckAccess(currentUser, queueId, ProjectMember.Role.MEMBER);
        queue.setStatus(Queue.Status.ACTIVE);
        return toResponse(queueRepository.save(queue));
    }

    @Transactional(readOnly = true)
    public QueueStatsResponse getStats(User currentUser, Long queueId) {
        Queue queue = getQueueAndCheckAccess(currentUser, queueId, ProjectMember.Role.VIEWER);

        long queuedCount = jobRepository.findByQueueIdAndStatus(queueId, Job.Status.QUEUED).size();
        long runningCount = jobRepository.findByQueueIdAndStatus(queueId, Job.Status.RUNNING).size();
        long completedCount = jobRepository.findByQueueIdAndStatus(queueId, Job.Status.COMPLETED).size();
        long failedCount = jobRepository.findByQueueIdAndStatus(queueId, Job.Status.FAILED).size();
        long deadLetterCount = jobRepository.findByQueueIdAndStatus(queueId, Job.Status.DEAD_LETTER).size();

        return new QueueStatsResponse(queueId, queuedCount, runningCount, completedCount, failedCount, deadLetterCount);
    }

    /**
     * Shared helper: loads a queue by ID and verifies the current user has at
     * least the given role on the queue's parent project. Used by every
     * queue-scoped action (pause/resume/stats) since a queue doesn't carry its
     * own membership list — access is always inherited from its project.
     */
    private Queue getQueueAndCheckAccess(User currentUser, Long queueId, ProjectMember.Role minimumRole) {
        Queue queue = queueRepository.findById(queueId)
                .orElseThrow(() -> new IllegalArgumentException("Queue not found: " + queueId));

        projectService.requireRole(currentUser, queue.getProject().getId(), minimumRole);
        return queue;
    }

    private QueueResponse toResponse(Queue queue) {
        return new QueueResponse(
                queue.getId(),
                queue.getProject().getId(),
                queue.getName(),
                queue.getPriority(),
                queue.getConcurrencyLimit(),
                queue.getRetryPolicy() != null ? queue.getRetryPolicy().getId() : null,
                queue.getStatus(),
                queue.getCreatedAt()
        );
    }
}