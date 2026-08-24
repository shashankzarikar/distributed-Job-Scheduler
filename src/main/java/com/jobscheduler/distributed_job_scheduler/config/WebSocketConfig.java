package com.jobscheduler.distributed_job_scheduler.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Simple in-memory broker — sufficient at this project's scale (single worker
        // instance, no separate message-broker process like RabbitMQ needed).
        registry.enableSimpleBroker("/topic");
        // Not currently used (server pushes only; no client-to-server STOMP messages),
        // but conventional to set so the prefix space is reserved if that changes later.
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // SockJS fallback keeps this working from a plain HTML file opened directly
        // (Step H frontend) without needing a proper native-WebSocket-friendly host.
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}