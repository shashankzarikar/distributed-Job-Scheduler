package com.jobscheduler.distributed_job_scheduler.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "queue_id", nullable = false)
    private Queue queue;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_job_id")
    private Job parentJob;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.QUEUED;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private String payload;

    @Column(nullable = false)
    private Integer priority = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "retry_policy_id")
    private RetryPolicy retryPolicy;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    @Column(name = "max_attempts", nullable = false)
    private Integer maxAttempts = 5;

    @Column(name = "run_after")
    private LocalDateTime runAfter;

    @Column(name = "idempotency_key", unique = true, length = 190)
    private String idempotencyKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "claimed_by_worker_id")
    private Worker claimedByWorker;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "last_heartbeat_at")
    private LocalDateTime lastHeartbeatAt;

    @Column(name = "total_children", nullable = false)
    private Integer totalChildren = 0;

    @Column(name = "completed_children", nullable = false)
    private Integer completedChildren = 0;

    @Column(name = "failed_children", nullable = false)
    private Integer failedChildren = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public enum Type {
        IMMEDIATE, DELAYED, SCHEDULED, CRON, BATCH
    }

    public enum Status {
        QUEUED, SCHEDULED, CLAIMED, RUNNING, COMPLETED, FAILED, DEAD_LETTER, PARTIALLY_FAILED
    }
}