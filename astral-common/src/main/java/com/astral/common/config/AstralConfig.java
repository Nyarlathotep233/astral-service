package com.astral.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "astral")
public class AstralConfig {

    private static String uploadType;

    private static String uploadDir;

    private static String externalApiKey;

    private static String adminApiKey;

    public static String getUploadType() {
        return uploadType;
    }

    public void setUploadType(String uploadType) {
        AstralConfig.uploadType = uploadType;
    }

    public static String getUploadDir() {
        return uploadDir;
    }

    public void setUploadDir(String uploadDir) {
        AstralConfig.uploadDir = uploadDir;
    }

    public static String getExternalApiKey() {
        return externalApiKey;
    }

    public void setExternalApiKey(String externalApiKey) {
        AstralConfig.externalApiKey = externalApiKey;
    }

    public static String getAdminApiKey() {
        return adminApiKey;
    }

    public void setAdminApiKey(String adminApiKey) {
        AstralConfig.adminApiKey = adminApiKey;
    }
}
