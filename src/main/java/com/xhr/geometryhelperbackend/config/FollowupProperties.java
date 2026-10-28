package com.xhr.geometryhelperbackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 追问业务配置。
 */
@ConfigurationProperties(prefix = "app.followup")
public class FollowupProperties {

    /**
     * 每道题最多允许的追问次数。默认 3 次；设为 0 可关闭追问。
     */
    private int maxQuestions = 3;

    public int getMaxQuestions() {
        return this.maxQuestions;
    }

    public void setMaxQuestions(int maxQuestions) {
        this.maxQuestions = maxQuestions;
    }
}
