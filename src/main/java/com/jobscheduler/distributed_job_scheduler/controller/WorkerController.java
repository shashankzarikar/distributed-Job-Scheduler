package com.jobscheduler.distributed_job_scheduler.controller;

import com.jobscheduler.distributed_job_scheduler.entity.Worker;
import com.jobscheduler.distributed_job_scheduler.repository.WorkerRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class WorkerController {

    private final WorkerRepository workerRepository;

    public WorkerController(WorkerRepository workerRepository) {
        this.workerRepository = workerRepository;
    }

    /**
     * Lists all workers with their current status. Backs the initial page-load
     * state for the Step H worker dashboard — the live /topic/workers WebSocket
     * feed (Step G) takes over for real-time updates after that. No REST endpoint
     * existed for this table before now (see design doc 8.5).
     */
    @GetMapping("/api/workers")
    public List<Worker> listWorkers() {
        return workerRepository.findAll();
    }
}