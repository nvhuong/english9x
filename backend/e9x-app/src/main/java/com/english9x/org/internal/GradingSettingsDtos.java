package com.english9x.org.internal;

import com.english9x.domain.grading.GradingMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Request / response bodies of the centre grading configuration (F50). */
final class GradingSettingsDtos {

    private GradingSettingsDtos() {}

    /** Which side grades the organisation's learners: the centre's own teachers or English9x. Mirrors {@code org_grading_sides.side}. */
    @Schema(name = "Grader", enumAsRef = true)
    enum Grader { CENTER, ENGLISH9X }

    @Schema(name = "GradingSettingsRequest")
    record GradingSettingsRequest(
            @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Grader grader,
            @Schema(nullable = true, description = "The Writing/Speaking mode for the centre's learners; absent leaves it unchanged") GradingMode mode) {}

    @Schema(name = "GradingSettingsView")
    record GradingSettingsView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Grader grader,
            @Schema(nullable = true, description = "The organisation's Writing/Speaking mode, null when none is set (the exam's applies)") GradingMode mode) {}
}
