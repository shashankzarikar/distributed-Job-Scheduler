-- =============================================================
-- Distributed Job Scheduler — Full Schema
-- =============================================================

-- ---------- USERS ----------
CREATE TABLE users (
                       id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                       name          VARCHAR(120)  NOT NULL,
                       email         VARCHAR(190)  NOT NULL,
                       password_hash VARCHAR(255)  NOT NULL,
                       created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                       UNIQUE KEY uq_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- ORGANIZATIONS ----------
CREATE TABLE organizations (
                               id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                               name          VARCHAR(150)  NOT NULL,
                               owner_user_id BIGINT        NOT NULL,
                               created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                               CONSTRAINT fk_org_owner FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- PROJECTS ----------
CREATE TABLE projects (
                          id              BIGINT AUTO_INCREMENT PRIMARY KEY,
                          organization_id BIGINT        NOT NULL,
                          name            VARCHAR(150)  NOT NULL,
                          created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          CONSTRAINT fk_project_org FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE,
                          KEY idx_projects_org (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- PROJECT MEMBERS (RBAC: Owner / Member / Viewer) ----------
CREATE TABLE project_members (
                                 id         BIGINT AUTO_INCREMENT PRIMARY KEY,
                                 project_id BIGINT      NOT NULL,
                                 user_id    BIGINT      NOT NULL,
                                 role       ENUM('OWNER','MEMBER','VIEWER') NOT NULL DEFAULT 'MEMBER',
                                 created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                 CONSTRAINT fk_pm_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
                                 CONSTRAINT fk_pm_user    FOREIGN KEY (user_id)    REFERENCES users(id)    ON DELETE CASCADE,
                                 UNIQUE KEY uq_project_user (project_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- RETRY POLICIES ----------
CREATE TABLE retry_policies (
                                id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
                                name               VARCHAR(100) NOT NULL,
                                strategy           ENUM('FIXED','LINEAR','EXPONENTIAL') NOT NULL,
                                base_delay_seconds INT          NOT NULL DEFAULT 30,
                                max_delay_seconds  INT          NOT NULL DEFAULT 3600,
                                max_attempts       INT          NOT NULL DEFAULT 5,
                                created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- QUEUES ----------
CREATE TABLE queues (
                        id                BIGINT AUTO_INCREMENT PRIMARY KEY,
                        project_id        BIGINT        NOT NULL,
                        name              VARCHAR(120)  NOT NULL,
                        priority          INT           NOT NULL DEFAULT 0,
                        concurrency_limit INT           NOT NULL DEFAULT 5,
                        retry_policy_id   BIGINT        NULL,
                        status            ENUM('ACTIVE','PAUSED') NOT NULL DEFAULT 'ACTIVE',
                        created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_queue_project FOREIGN KEY (project_id)      REFERENCES projects(id)       ON DELETE CASCADE,
                        CONSTRAINT fk_queue_retry   FOREIGN KEY (retry_policy_id) REFERENCES retry_policies(id) ON DELETE SET NULL,
                        UNIQUE KEY uq_queue_project_name (project_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- WORKERS ----------
CREATE TABLE workers (
                         id               BIGINT AUTO_INCREMENT PRIMARY KEY,
                         name             VARCHAR(150) NOT NULL,
                         status           ENUM('ACTIVE','UNRESPONSIVE','DEAD','SHUTDOWN') NOT NULL DEFAULT 'ACTIVE',
                         last_heartbeat_at TIMESTAMP   NULL,
                         started_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         KEY idx_workers_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- WORKER HEARTBEATS ----------
CREATE TABLE worker_heartbeats (
                                   id           BIGINT AUTO_INCREMENT PRIMARY KEY,
                                   worker_id    BIGINT    NOT NULL,
                                   heartbeat_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                   CONSTRAINT fk_heartbeat_worker FOREIGN KEY (worker_id) REFERENCES workers(id) ON DELETE CASCADE,
                                   KEY idx_heartbeats_worker_time (worker_id, heartbeat_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- JOBS ----------
CREATE TABLE jobs (
                      id                BIGINT AUTO_INCREMENT PRIMARY KEY,
                      queue_id          BIGINT        NOT NULL,
                      parent_job_id     BIGINT        NULL,
                      type              ENUM('IMMEDIATE','DELAYED','SCHEDULED','CRON','BATCH') NOT NULL,
                      status            ENUM('QUEUED','SCHEDULED','CLAIMED','RUNNING','COMPLETED','FAILED','DEAD_LETTER','PARTIALLY_FAILED') NOT NULL DEFAULT 'QUEUED',
                      payload           JSON          NULL,
                      priority          INT           NOT NULL DEFAULT 0,
                      retry_policy_id   BIGINT        NULL,
                      attempt_count     INT           NOT NULL DEFAULT 0,
                      max_attempts      INT           NOT NULL DEFAULT 5,
                      run_after         TIMESTAMP     NULL,
                      idempotency_key   VARCHAR(190)  NULL,
                      claimed_by_worker_id BIGINT     NULL,
                      claimed_at        TIMESTAMP     NULL,
                      last_heartbeat_at TIMESTAMP     NULL,
                      total_children    INT           NOT NULL DEFAULT 0,
                      completed_children INT          NOT NULL DEFAULT 0,
                      failed_children   INT           NOT NULL DEFAULT 0,
                      created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      updated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      CONSTRAINT fk_job_queue    FOREIGN KEY (queue_id)            REFERENCES queues(id)          ON DELETE CASCADE,
                      CONSTRAINT fk_job_parent   FOREIGN KEY (parent_job_id)       REFERENCES jobs(id)             ON DELETE CASCADE,
                      CONSTRAINT fk_job_retry    FOREIGN KEY (retry_policy_id)     REFERENCES retry_policies(id)   ON DELETE SET NULL,
                      CONSTRAINT fk_job_worker   FOREIGN KEY (claimed_by_worker_id) REFERENCES workers(id)         ON DELETE SET NULL,
                      UNIQUE KEY uq_job_idempotency (idempotency_key),
                      KEY idx_jobs_claim_poll (queue_id, status, run_after, priority),
                      KEY idx_jobs_status (status),
                      KEY idx_jobs_parent (parent_job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- SCHEDULED JOBS ----------
CREATE TABLE scheduled_jobs (
                                id               BIGINT AUTO_INCREMENT PRIMARY KEY,
                                queue_id         BIGINT        NOT NULL,
                                job_type         ENUM('DELAYED','SCHEDULED','CRON') NOT NULL,
                                payload          JSON          NULL,
                                priority         INT           NOT NULL DEFAULT 0,
                                cron_expression  VARCHAR(120)  NULL,
                                next_run_time    TIMESTAMP     NOT NULL,
                                is_recurring     BOOLEAN       NOT NULL DEFAULT FALSE,
                                promoted         BOOLEAN       NOT NULL DEFAULT FALSE,
                                promoted_job_id  BIGINT        NULL,
                                created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                CONSTRAINT fk_sched_queue FOREIGN KEY (queue_id)        REFERENCES queues(id) ON DELETE CASCADE,
                                CONSTRAINT fk_sched_job   FOREIGN KEY (promoted_job_id) REFERENCES jobs(id)   ON DELETE SET NULL,
                                KEY idx_scheduled_poll (promoted, next_run_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- JOB EXECUTIONS ----------
CREATE TABLE job_executions (
                                id             BIGINT AUTO_INCREMENT PRIMARY KEY,
                                job_id         BIGINT        NOT NULL,
                                worker_id      BIGINT        NULL,
                                attempt_number INT           NOT NULL,
                                status         ENUM('RUNNING','SUCCESS','FAILURE') NOT NULL,
                                started_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                finished_at    TIMESTAMP     NULL,
                                error_message  TEXT          NULL,
                                CONSTRAINT fk_exec_job    FOREIGN KEY (job_id)    REFERENCES jobs(id)    ON DELETE CASCADE,
                                CONSTRAINT fk_exec_worker FOREIGN KEY (worker_id) REFERENCES workers(id) ON DELETE SET NULL,
                                KEY idx_executions_job (job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- JOB LOGS ----------
CREATE TABLE job_logs (
                          id           BIGINT AUTO_INCREMENT PRIMARY KEY,
                          job_id       BIGINT        NOT NULL,
                          execution_id BIGINT        NULL,
                          level        ENUM('INFO','WARN','ERROR') NOT NULL DEFAULT 'INFO',
                          message      TEXT          NOT NULL,
                          created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          CONSTRAINT fk_log_job       FOREIGN KEY (job_id)       REFERENCES jobs(id)            ON DELETE CASCADE,
                          CONSTRAINT fk_log_execution FOREIGN KEY (execution_id) REFERENCES job_executions(id)  ON DELETE CASCADE,
                          KEY idx_logs_job (job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- DEAD LETTER QUEUE ----------
CREATE TABLE dead_letter_queue (
                                   id               BIGINT AUTO_INCREMENT PRIMARY KEY,
                                   job_id           BIGINT        NOT NULL,
                                   reason           TEXT          NULL,
                                   moved_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                   retried_manually BOOLEAN       NOT NULL DEFAULT FALSE,
                                   CONSTRAINT fk_dlq_job FOREIGN KEY (job_id) REFERENCES jobs(id) ON DELETE CASCADE,
                                   UNIQUE KEY uq_dlq_job (job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
