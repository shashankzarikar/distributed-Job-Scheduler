package com.jobscheduler.distributed_job_scheduler.security;

import com.jobscheduler.distributed_job_scheduler.entity.User;
import com.jobscheduler.distributed_job_scheduler.repository.UserRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SecurityUtils {

    private final UserRepository userRepository;

    public SecurityUtils(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Returns the currently authenticated user, resolved from the JWT's email claim.
     * Every controller that needs "who is making this request" calls this.
     */
    public User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + email));
    }
}