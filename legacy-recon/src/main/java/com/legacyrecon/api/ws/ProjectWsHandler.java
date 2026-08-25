package com.legacyrecon.api.ws;

import com.legacyrecon.pipeline.PipelineStageEvent;
import com.legacyrecon.util.Json;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 04.4 WebSocket 处理器：为项目级 session 广播 pipeline 事件。
 * 事件：pipeline.stage.started/finished/error, parse.file.progress, run.completed,
 *      enrichment.insight.pending, export.ready。
 */
@Component
public class ProjectWsHandler extends TextWebSocketHandler {

    private final Map<String, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String projectId = projectIdOf(session);
        sessions.computeIfAbsent(projectId, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String projectId = projectIdOf(session);
        Set<WebSocketSession> set = sessions.get(projectId);
        if (set != null) {
            set.remove(session);
        }
    }

    private String projectIdOf(WebSocketSession session) {
        String path = session.getUri() == null ? "" : session.getUri().getPath();
        String[] seg = path.split("/");
        return seg.length >= 1 ? seg[seg.length - 1] : "unknown";
    }

    /** 广播一个管道事件到该项目的所有在线 session。 */
    public void broadcast(PipelineStageEvent event) {
        Set<WebSocketSession> set = sessions.get(event.projectId);
        if (set == null) {
            return;
        }
        TextMessage msg = new TextMessage(Json.toJson(event));
        for (WebSocketSession s : set) {
            if (s.isOpen()) {
                try {
                    s.sendMessage(msg);
                } catch (Exception ignore) {
                }
            }
        }
    }
}