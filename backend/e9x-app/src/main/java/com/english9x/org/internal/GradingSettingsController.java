package com.english9x.org.internal;

import com.english9x.org.internal.GradingSettingsDtos.GradingSettingsRequest;
import com.english9x.org.internal.GradingSettingsDtos.GradingSettingsView;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Centre grading configuration (F50): the side that grades the centre's learners and their Writing/Speaking mode. */
@RestController
class GradingSettingsController {

    private final GradingSettingsService settings;

    GradingSettingsController(GradingSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping("/api/orgs/{id}/grading-settings")
    GradingSettingsView get(@PathVariable UUID id) {
        return settings.get(id);
    }

    @PutMapping("/api/orgs/{id}/grading-settings")
    GradingSettingsView set(@PathVariable UUID id, @Valid @RequestBody GradingSettingsRequest body) {
        return settings.set(id, body);
    }
}
