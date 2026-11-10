package com.english9x.content.strategy.internal;

import com.english9x.content.media.api.MediaAssets;
import com.english9x.content.media.api.MediaKind;
import com.english9x.content.question.api.QuestionVersions;
import com.english9x.content.strategy.internal.StrategyProblems.FieldProblem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Everything the service checks about the blocks of a lesson: the JSON Schema of each block type (catalog/strategy-block.schema.json, validated block by
 * block against {@code $defs/<type>} so every error has a precise path such as {@code blocks[3].url}), the host of a video-embed against the allowlist
 * ({@code e9x.content.strategy.video-hosts}), the files the blocks point at (this exam's, of the right kind, ones the author may use), and the question
 * versions they embed (they must exist, be visible to the author, and be of the same exam).
 */
@Component
class BlockValidator {

    private final Map<String, JsonSchema> byType = new HashMap<>();
    private final StrategyProperties props;
    private final MediaAssets media;
    private final QuestionVersions questions;
    private final ObjectMapper json;

    BlockValidator(StrategyProperties props, MediaAssets media, QuestionVersions questions, ObjectMapper json) throws IOException {
        this.props = props;
        this.media = media;
        this.questions = questions;
        this.json = json;
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        try (InputStream in = new ClassPathResource("catalog/strategy-block.schema.json").getInputStream()) {
            JsonNode doc = json.readTree(in);
            for (Iterator<String> it = doc.get("$defs").fieldNames(); it.hasNext();) {
                String type = it.next();
                ObjectNode wrapper = json.createObjectNode();
                wrapper.put("$schema", doc.get("$schema").asText());
                wrapper.set("$defs", doc.get("$defs"));
                wrapper.put("$ref", "#/$defs/" + type);
                JsonSchema schema = factory.getSchema(wrapper);
                schema.initializeValidators();
                byType.put(type, schema);
            }
        }
    }

    List<String> blockTypes() {
        return byType.keySet().stream().sorted().toList();
    }

    List<FieldProblem> validate(String examCode, JsonNode blocks) {
        List<FieldProblem> out = new ArrayList<>();
        if (blocks == null || !blocks.isArray()) {
            out.add(new FieldProblem("blocks", "must be a list of blocks"));
            return out;
        }
        if (blocks.isEmpty()) {
            out.add(new FieldProblem("blocks", "needs at least one block"));
        }
        if (blocks.size() > props.maxBlocks()) {
            out.add(new FieldProblem("blocks", "has more than " + props.maxBlocks() + " blocks"));
            return out;
        }
        try {
            if (json.writeValueAsBytes(blocks).length > props.maxJsonSize().toBytes()) {
                out.add(new FieldProblem("blocks", "is larger than " + props.maxJsonSize().toBytes() + " bytes"));
                return out;
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            out.add(new FieldProblem("blocks", "cannot be read"));
            return out;
        }
        for (int i = 0; i < blocks.size(); i++) {
            String at = "blocks[" + i + "]";
            JsonNode b = blocks.get(i);
            JsonSchema schema = b.isObject() && b.path("type").isTextual() ? byType.get(b.path("type").asText()) : null;
            if (schema == null) {
                out.add(new FieldProblem(at + ".type", "is not a block type (" + String.join(", ", blockTypes()) + ")"));
                continue;
            }
            List<FieldProblem> shape = new ArrayList<>();
            for (ValidationMessage m : schema.validate(b)) {
                shape.add(toProblem(at, m));
            }
            if (!shape.isEmpty()) {
                out.addAll(shape);
                continue;
            }
            semantics(examCode, at, b, out);
        }
        return out;
    }

    private void semantics(String examCode, String at, JsonNode b, List<FieldProblem> out) {
        switch (b.get("type").asText()) {
            case "video-embed" -> videoEmbed(at, b, out);
            case "image" -> mediaFile(examCode, at + ".imageMediaId", b.get("imageMediaId").asText(), MediaKind.IMAGE, out);
            case "audio" -> mediaFile(examCode, at + ".audioMediaId", b.get("audioMediaId").asText(), MediaKind.AUDIO, out);
            case "video-upload" -> mediaFile(examCode, at + ".videoMediaId", b.get("videoMediaId").asText(), MediaKind.VIDEO, out);
            case "embedded-question" -> embeddedQuestion(examCode, at + ".questionVersionId", b.get("questionVersionId").asText(), out);
            default -> { }
        }
    }

    private void videoEmbed(String at, JsonNode b, List<FieldProblem> out) {
        String url = b.get("url").asText();
        String provider = b.get("provider").asText();
        Optional<String> host = VideoUrls.httpsHost(url);
        if (host.isEmpty() || !props.allows(host.get())) {
            out.add(new FieldProblem(at + ".url", "is not an address of an allowed video host (YouTube or Vimeo, https)"));
        } else if (VideoUrls.videoId(provider, url).isEmpty()) {
            out.add(new FieldProblem(at + ".url", "is not the address of a " + provider.toUpperCase(Locale.ROOT).charAt(0) + provider.substring(1) + " video"));
        }
    }

    private void mediaFile(String examCode, String path, String value, MediaKind kind, List<FieldProblem> out) {
        boolean ok;
        try {
            ok = media.find(UUID.fromString(value)).filter(m -> m.examCode().equals(examCode)).filter(m -> m.kind() == kind).isPresent();
        } catch (IllegalArgumentException e) {
            ok = false;
        }
        if (!ok) {
            out.add(new FieldProblem(path, "is not a file of this exam that you may use"));
        }
    }

    /** Staff see their own drafts and reviewed content (find); a published English9x question used from an organisation is seen through the learner view. */
    private void embeddedQuestion(String examCode, String path, String value, List<FieldProblem> out) {
        UUID id = UUID.fromString(value);
        Optional<String> exam = questions.find(id).map(q -> q.examCode());
        if (exam.isEmpty()) {
            exam = questions.examView(id).map(q -> q.examCode());
        }
        if (exam.isEmpty() || !exam.get().equals(examCode)) {
            out.add(new FieldProblem(path, "is not a question of this exam that you may use"));
        }
    }

    private static FieldProblem toProblem(String at, ValidationMessage m) {
        String type = m.getType() == null ? "" : m.getType();
        String loc = m.getInstanceLocation().toString();
        String path = at + (loc.length() > 1 ? loc.substring(1) : "");
        if ((type.equals("required") || type.equals("additionalProperties")) && m.getProperty() != null && !m.getProperty().isBlank()) {
            path = path + "." + m.getProperty();
        }
        String message = m.getMessage();
        String prefix = loc + ": ";
        if (message.startsWith(prefix)) {
            message = message.substring(prefix.length());
        }
        return new FieldProblem(path, message);
    }
}
