package com.jobscheduler.distributed_job_scheduler.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "retry_policies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RetryPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Strategy strategy;

    @Column(name = "base_delay_seconds", nullable = false)
    private Integer baseDelaySeconds = 30;

    @Column(name = "max_delay_seconds", nullable = false)
    private Integer maxDelaySeconds = 3600;

    @Column(name = "max_attempts", nullable = false)
    private Integer maxAttempts = 5;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public enum Strategy {
        FIXED, LINEAR, EXPONENTIAL
    }
}