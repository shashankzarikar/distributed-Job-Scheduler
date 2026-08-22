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
@Table(name = "scheduled_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ScheduledJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "queue_id", nullable = false)
    private Queue queue;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 20)
    private JobType jobType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private String payload;

    @Column(nullable = false)
    private Integer priority = 0;

    @Column(name = "cron_expression", length = 120)
    private String cronExpression;

    @Column(name = "next_run_time", nullable = false)
    private LocalDateTime nextRunTime;

    @Column(name = "is_recurring", nullable = false)
    private Boolean isRecurring = false;

    @Column(nullable = false)
    private Boolean promoted = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promoted_job_id")
    private Job promotedJob;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public enum JobType {
        DELAYED, SCHEDULED, CRON
    }
}