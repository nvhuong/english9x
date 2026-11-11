package com.english9x.exam.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * {@code e9x.exam.*} (spec 15.1). {@code lateSyncGrace}: how long after a section ends an answer written before it ended is still accepted;
 * {@code recordingUploadTolerance}: how long after the Speaking part ends its recordings may still be uploaded; {@code maxAnswerSize}: the largest answer
 * (JSON text); {@code maxRecordingSize}: the largest recording. {@code autosaveInterval} is read by the client (GET /api/config/public).
 */
@ConfigurationProperties("e9x.exam")
record ExamProperties(
        @DefaultValue("2") int defaultMaxResumes,
        @DefaultValue("5s") Duration autosaveInterval,
        @DefaultValue("2m") Duration lateSyncGrace,
        @DefaultValue("10s") Duration recordingUploadTolerance,
        @DefaultValue("25MB") DataSize maxRecordingSize,
        @DefaultValue("64KB") DataSize maxAnswerSize) {

    ExamProperties {
        if (defaultMaxResumes < 0 || defaultMaxResumes > 20 || lateSyncGrace.isNegative() || recordingUploadTolerance.isNegative()
                || maxAnswerSize.toBytes() < 1 || maxRecordingSize.toBytes() < 1) {
            throw new IllegalStateException("e9x.exam: invalid values");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ExamProperties.class)
    static class Registration {}
}
