package com.astral.business.scenes.entity;

import lombok.Data;

@Data
public class SceneSaveRequest {
    private Astral3DScenes scene;
    private Integer expectedVersion;
    private String operationId;
}
