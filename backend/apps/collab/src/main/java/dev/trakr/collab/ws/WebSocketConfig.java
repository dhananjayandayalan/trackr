package dev.trakr.collab.ws;

import dev.trakr.collab.config.CollabProperties;
import dev.trakr.collab.security.SessionAuthHandshakeInterceptor;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Which URL is a WebSocket, who handles it, and the container's limits (06-collab §5, §7). Plain WebSocket — no
 * STOMP, no SockJS: y-websocket speaks raw binary frames, and every browser has native WebSockets.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
class WebSocketConfig implements WebSocketConfigurer {

    /** 06-collab §5: one frame at most 1 MiB. Bigger frames are refused by Tomcat with close code 1009. */
    static final int MAX_FRAME_BYTES = 1024 * 1024;
    /** No frame for 10 minutes: closed. Clients send one every 30 s (the provider's resync), so only dead ones go. */
    static final Duration IDLE_TIMEOUT = Duration.ofMinutes(10);

    private final CollabWebSocketHandler handler;
    private final SessionAuthHandshakeInterceptor authentication;
    private final CollabProperties properties;

    WebSocketConfig(CollabWebSocketHandler handler, SessionAuthHandshakeInterceptor authentication,
            CollabProperties properties) {
        this.handler = handler;
        this.authentication = authentication;
        this.properties = properties;
    }

    // BLOCK 1: one handler for every issue; the key is the path's last segment (the interceptor reads it).
    // setAllowedOrigins adds Spring's Origin check in front of our interceptor: a handshake from another site is
    // refused with 403 before anyone looks at its cookie.
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/collab/issues/*")
                .addInterceptors(authentication)
                .setAllowedOrigins(properties.app().origin().toArray(String[]::new));
    }

    // BLOCK 2: the servlet container's WebSocket settings (Tomcat's JSR-356 implementation underneath)
    @Bean
    ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(MAX_FRAME_BYTES);
        // we speak binary only; BinaryWebSocketHandler closes on any text frame with 1003
        container.setMaxTextMessageBufferSize(1024);
        container.setMaxSessionIdleTimeout(IDLE_TIMEOUT.toMillis());
        return container;
    }
}
