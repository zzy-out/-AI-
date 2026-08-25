package com.legacyrecon.pipeline;

/**
 * 04.4 WebSocket 管道事件模型（pipeline.stage.started/finished/error, run.completed 等）。
 */
public class PipelineStageEvent {
    public enum Type {
        stage_started, stage_finished, stage_error, file_progress,
        insight_pending, run_completed, export_ready
    }

    public Type type;
    public String projectId;
    public String runId;
    public String stage;
    public String errorCode;
    public String message;
    public Object payload;

    public PipelineStageEvent(Type type, String projectId, String runId) {
        this.type = type;
        this.projectId = projectId;
        this.runId = runId;
    }

    public PipelineStageEvent message(String msg, String code) {
        this.message = msg;
        this.errorCode = code;
        return this;
    }

    public PipelineStageEvent payload(Object p) {
        this.payload = p;
        return this;
    }

    public PipelineStageEvent stage(String s) {
        this.stage = s;
        return this;
    }
}