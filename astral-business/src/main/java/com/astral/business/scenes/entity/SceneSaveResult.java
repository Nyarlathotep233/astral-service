package com.astral.business.scenes.entity;

import lombok.Data;

@Data
public class SceneSaveResult {
    private Astral3DScenes scene;
    private String operationId;
    private Integer expectedVersion;
    private boolean replayed;
    private java.util.List<String> warnings;
}
