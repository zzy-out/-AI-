package com.legacyrecon.api.ws;

import com.legacyrecon.pipeline.PipelineStageEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 将 ApplicationEvent 型的管道事件转发到 WebSocket（04.4 R23：含 pipeline.stage.error）。
 */
@Component
public class WsEventForwarder {

    private final ProjectWsHandler handler;

    public WsEventForwarder(ProjectWsHandler handler) {
        this.handler = handler;
    }

    @EventListener(PipelineStageEvent.class)
    public void forward(PipelineStageEvent event) {
        handler.broadcast(event);
    }
}