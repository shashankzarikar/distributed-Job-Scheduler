package com.jobscheduler.distributed_job_scheduler.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "worker_heartbeats")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WorkerHeartbeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "worker_id", nullable = false)
    private Worker worker;

    @Column(name = "heartbeat_at", nullable = false)
    private LocalDateTime heartbeatAt;

    @PrePersist
    protected void onCreate() {
        this.heartbeatAt = LocalDateTime.now();
    }
}