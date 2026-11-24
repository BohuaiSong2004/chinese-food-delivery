package com.wokflow.api.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

class LiveHubShutdownTest {
    @Test
    void shutdownEndsEveryOpenStream() throws Exception {
        var hub = new LiveHub(null, JsonMapper.builder().build(), new SimpleMeterRegistry());
        SseEmitter a = hub.open("order:1", Duration.ofMinutes(30), null, null);
        SseEmitter b = hub.open("restaurant:2", Duration.ofMinutes(30), null, null);
        assertThat(hub.openStreams()).isEqualTo(2);

        hub.closeAll();

        assertThat(hub.openStreams()).isZero();
        assertThatThrownBy(() -> a.send("late")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> b.send("late")).isInstanceOf(IllegalStateException.class);
        hub.closeAll(); // idempotent
        assertThat(hub.openStreams()).isZero();
    }
}
