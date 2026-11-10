package com.english9x.content.question.internal;

import com.english9x.content.media.api.MediaAssets;
import com.english9x.content.media.api.MediaKind;
import com.english9x.content.question.internal.QuestionProblems.FieldProblem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Checks shared by questions and groups that a JSON Schema cannot make: the size of a document, and the files it points at. A field whose name ends in
 * {@code MediaId} holds the id of a stored file; it must be a file the author may use for THIS exam ({@link MediaAssets#find} answers through row-level
 * security: the exam's author or reviewer, in the owner's context), and the kind must fit the name ({@code audioMediaId} an audio file, {@code imageMediaId}
 * an image, {@code videoMediaId} a video). One message for "not there", "not yours" and "another exam's": nothing says which.
 */
final class ContentChecks {

    private ContentChecks() {}

    static void size(ObjectMapper json, String field, JsonNode node, long maxBytes, List<FieldProblem> out) {
        if (node == null || node.isNull()) {
            return;
        }
        try {
            if (json.writeValueAsBytes(node).length > maxBytes) {
                out.add(new FieldProblem(field, "is larger than " + maxBytes + " bytes"));
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            out.add(new FieldProblem(field, "cannot be read"));
        }
    }

    static void media(MediaAssets media, String examCode, String root, JsonNode node, List<FieldProblem> out) {
        if (node != null && !node.isNull()) {
            walk(media, examCode, root, node, out);
        }
    }

    private static void walk(MediaAssets media, String examCode, String path, JsonNode node, List<FieldProblem> out) {
        if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> e = it.next();
                String child = path + "." + e.getKey();
                if (e.getKey().endsWith("MediaId") && e.getValue().isTextual()) {
                    checkOne(media, examCode, child, e.getKey(), e.getValue().asText(), out);
                } else {
                    walk(media, examCode, child, e.getValue(), out);
                }
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(media, examCode, path + "[" + i + "]", node.get(i), out);
            }
        }
    }

    private static void checkOne(MediaAssets media, String examCode, String path, String name, String value, List<FieldProblem> out) {
        UUID id;
        try {
            id = UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            out.add(new FieldProblem(path, "is not a file id"));
            return;
        }
        MediaKind wanted = name.toLowerCase(Locale.ROOT).startsWith("audio") ? MediaKind.AUDIO
                : name.toLowerCase(Locale.ROOT).startsWith("image") ? MediaKind.IMAGE
                : name.toLowerCase(Locale.ROOT).startsWith("video") ? MediaKind.VIDEO : null;
        boolean ok = media.find(id).filter(m -> m.examCode().equals(examCode)).filter(m -> wanted == null || m.kind() == wanted).isPresent();
        if (!ok) {
            out.add(new FieldProblem(path, "is not a file of this exam that you may use"));
        }
    }
}
