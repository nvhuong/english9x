package com.english9x.grading.ai;

import com.english9x.content.skill.api.PromptTemplate;
import com.english9x.grading.ai.api.Response;
import com.english9x.grading.ai.api.SkillVersion;
import com.english9x.grading.ai.api.Task;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fills the four holes of a grading skill's template, then adds the criteria, the schema and (on a retry) the reasons the last answer was refused. */
final class PromptRenderer {

    private PromptRenderer() {}

    static String render(SkillVersion skill, Task task, Response response, List<String> refusals) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("rubric", skill.rubric());
        values.put("samples", PromptTemplate.samplesText(skill.samples()));
        values.put("task", task.text());
        values.put("response", response instanceof Response.Text t ? t.text() : "(the learner's spoken answer is attached as audio)");
        StringBuilder out = new StringBuilder(PromptTemplate.render(skill.promptTemplate(), values));
        out.append("\n\nScore every criterion within its range:\n");
        if (skill.criteria() != null && skill.criteria().isArray()) {
            for (JsonNode c : skill.criteria()) {
                out.append("- ").append(c.path("code").asText()).append(" (").append(c.path("name").asText()).append("): ")
                        .append(c.path("min").asText()).append(" to ").append(c.path("max").asText()).append(", step ").append(c.path("step").asText()).append('\n');
            }
        }
        out.append("\nAnswer with ONE JSON object and nothing else, matching this JSON Schema:\n").append(skill.resultSchema());
        if (!refusals.isEmpty()) {
            out.append("\n\nYour previous answer was refused for these reasons; answer again and fix them:\n");
            refusals.forEach(r -> out.append("- ").append(r).append('\n'));
        }
        return out.toString();
    }
}
