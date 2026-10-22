package org.tifo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling is enabled here. The WebSocket endpoints that used to live in this class are gone: they
 * had no client (P0-19), so they were dead weight - open sockets, a JWT handshake on every upgrade,
 * and handlers that nothing ever called.
 */
@Configuration
@EnableScheduling
public class WebSocketConfig {
}
