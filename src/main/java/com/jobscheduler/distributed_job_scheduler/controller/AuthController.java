package com.jobscheduler.distributed_job_scheduler.controller;

import com.jobscheduler.distributed_job_scheduler.dto.auth.AuthResponse;
import com.jobscheduler.distributed_job_scheduler.dto.auth.LoginRequest;
import com.jobscheduler.distributed_job_scheduler.dto.auth.RegisterRequest;
import com.jobscheduler.distributed_job_scheduler.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }
}