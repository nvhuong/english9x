package com.english9x.analytics.aimonitor;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The AI monitor (F56). */
final class AiMonitorDtos {

    private AiMonitorDtos() {}

    @Schema(name = "AiCostDay")
    record DayCost(LocalDate day, BigDecimal costVnd, long tokens, int evaluations, boolean overThreshold) {}

    @Schema(name = "AiCostPerson")
    record PersonCost(LocalDate day, UUID userId, String email, BigDecimal costVnd, long inputTokens, long outputTokens, int evaluations) {}

    @Schema(name = "AiFailedJob")
    record FailedJob(UUID id, String type, int attempts, String lastError, Instant failedAt) {}

    @Schema(name = "AiDeviation", description = "Teacher band minus AI band per question type (positive: the AI scored lower than the teacher)")
    record Deviation(String typeCode, String typeName, int samples, BigDecimal meanDeviation, BigDecimal meanAbsDeviation) {}

    @Schema(name = "AiMonitor")
    record Monitor(LocalDate from, LocalDate to, BigDecimal dailyCostAlert, boolean costAlert, List<LocalDate> alertDays, BigDecimal totalCostVnd, List<DayCost> days,
            List<PersonCost> costByDayAndPerson, List<FailedJob> failedJobs, int aiFailedGradings, List<Deviation> deviation) {}
}
