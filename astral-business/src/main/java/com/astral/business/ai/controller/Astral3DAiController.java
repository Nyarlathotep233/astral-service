package com.astral.business.ai.controller;

import com.astral.common.result.Result;
import lombok.Data;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 编辑器 AI 能力（迭代 A：mock 打通）
 */
@RestController
@RequestMapping("/editor3d/ai")
public class Astral3DAiController {

    @PostMapping("/script/mock")
    public Result<?> generateScriptMock(@RequestBody ScriptMockRequest request) {
        if (request == null || !StringUtils.hasText(request.getRequirement())) {
            return Result.error("需求不能为空");
        }

        ScriptMockResponse response = new ScriptMockResponse();
        response.setRequestId(UUID.randomUUID().toString());
        response.setModel("mock-script-v1");
        response.setScriptName(getScriptName(request));
        response.setSource(buildSource(request));
        response.setExplanation(buildExplanation(request));

        List<String> warnings = new ArrayList<>();
        warnings.add("当前为 mock 结果，仅用于前后端链路验证。");
        warnings.add("请在应用脚本前检查对象名称和逻辑是否符合场景。");
        response.setWarnings(warnings);

        return Result.success(response);
    }

    private String getScriptName(ScriptMockRequest request) {
        if (StringUtils.hasText(request.getScriptName())) {
            return request.getScriptName();
        }
        return "AI_Generated_Script";
    }

    private String buildSource(ScriptMockRequest request) {
        String objectName = escapeJsString(request.getObjectName());
        String requirement = escapeJsString(request.getRequirement());

        StringBuilder sb = new StringBuilder();
        sb.append("/**\n");
        sb.append(" * AI Mock Generated Script\n");
        sb.append(" * requirement: ").append(requirement).append("\n");
        sb.append(" */\n\n");
        sb.append("const CONFIG = {\n");
        sb.append("  autoRotate: true,\n");
        sb.append("  speedDegPerSec: 18,\n");
        sb.append("};\n\n");
        sb.append("let target = null;\n\n");
        sb.append("function loaded() {\n");
        sb.append("  target = scene.getObjectByName(\"").append(objectName).append("\") || this;\n");
        sb.append("}\n\n");
        sb.append("function afterAnimation(delta, toBeRender) {\n");
        sb.append("  if (!CONFIG.autoRotate || !target) return;\n");
        sb.append("  const step = THREE.MathUtils.degToRad(CONFIG.speedDegPerSec) * delta;\n");
        sb.append("  target.rotation.y += step;\n");
        sb.append("  toBeRender(true);\n");
        sb.append("}\n\n");
        sb.append("function onKeyDown(event) {\n");
        sb.append("  if (event.key === \" \") {\n");
        sb.append("    CONFIG.autoRotate = !CONFIG.autoRotate;\n");
        sb.append("    console.log(\"autoRotate =\", CONFIG.autoRotate);\n");
        sb.append("  }\n");
        sb.append("}\n");

        return sb.toString();
    }

    private String buildExplanation(ScriptMockRequest request) {
        String object = StringUtils.hasText(request.getObjectName()) ? request.getObjectName() : "当前对象";
        return "已为对象 " + object + " 生成一个最小可运行示例：loaded 绑定目标对象，afterAnimation 按 Y 轴匀速旋转，空格键切换自动旋转。";
    }

    private String escapeJsString(String input) {
        if (input == null) {
            return "";
        }
        return input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ");
    }

    @Data
    public static class ScriptMockRequest {
        private String sceneId;
        private String objectId;
        private String objectName;
        private String objectType;
        private String scriptName;
        private String currentSource;
        private String requirement;
        private String mode;
    }

    @Data
    public static class ScriptMockResponse {
        private String requestId;
        private String model;
        private String scriptName;
        private String source;
        private String explanation;
        private List<String> warnings;
    }
}
