package com.english9x.catalog.internal;

import com.english9x.catalog.api.FieldError;
import com.english9x.domain.scoring.InteractionKind;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * What a JSON Schema cannot say and a scorer needs: the correct option is one of the options, option keys are unique, a matching has
 * one answer per item, a completion one answer per blank. Runs only on content and answer key that already fit their schemas, so the
 * shapes are known. A message about the answer key never repeats the answer.
 */
final class Consistency {

    private static final Pattern BLANK = Pattern.compile("\\[\\[(\\d{1,3})]]");

    private Consistency() {}

    static List<FieldError> check(InteractionKind kind, JsonNode content, JsonNode key) {
        List<FieldError> out = new ArrayList<>();
        JsonNode slots = key.path("slots");
        switch (kind) {
            case MCQ_SINGLE, MCQ_MULTI, GAP_SELECT -> {
                Set<String> keys = uniqueKeys(content.path("options"), "content.options", out);
                variantsAmong(slots, keys, out);
                JsonNode wanted = content.path("selectCount");
                if (kind == InteractionKind.MCQ_MULTI && wanted.isInt() && slots.path(0).size() != wanted.asInt()) {
                    out.add(new FieldError("answerKey.slots[0]", "must list as many correct options as the question asks to choose"));
                }
            }
            case MATCHING -> {
                Set<String> keys = uniqueKeys(content.path("choices"), "content.choices", out);
                if (slots.size() != content.path("items").size()) {
                    out.add(new FieldError("answerKey.slots", "needs one answer per item"));
                }
                variantsAmong(slots, keys, out);
            }
            case GAP_TYPED -> {
                Set<Integer> blanks = new HashSet<>();
                Matcher m = BLANK.matcher(content.path("text").asText(""));
                while (m.find()) {
                    blanks.add(Integer.parseInt(m.group(1)));
                }
                if (blanks.isEmpty()) {
                    out.add(new FieldError("content.text", "has no blank: write each blank as [[1]], [[2]], ..."));
                } else if (!blanks.equals(IntStream.rangeClosed(1, blanks.size()).boxed().collect(Collectors.toSet()))) {
                    // slot n answers blank [[n]]: the numbers must be exactly 1..N or the mapping is ambiguous
                    out.add(new FieldError("content.text", "blanks must be numbered exactly 1, 2, 3, ... with no gap and no 0"));
                } else if (slots.size() != blanks.size()) {
                    out.add(new FieldError("answerKey.slots", "needs one answer per blank"));
                }
            }
            case LABEL_DIAGRAM -> {
                if (content.has("choices")) {
                    variantsAmong(slots, uniqueKeys(content.path("choices"), "content.choices", out), out);
                }
            }
            default -> {
                // TRUE_FALSE_NG and YES_NO_NG are closed enums in the schema; SHORT_ANSWER has nothing to cross-check;
                // ESSAY and SPEAKING_RESPONSE have no key to score
            }
        }
        return out;
    }

    private static Set<String> uniqueKeys(JsonNode options, String path, List<FieldError> out) {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < options.size(); i++) {
            String k = options.get(i).path("key").asText();
            if (!keys.add(k.toUpperCase(java.util.Locale.ROOT))) {
                out.add(new FieldError(path + "[" + i + "].key", "repeats an earlier key"));
            }
        }
        return keys;
    }

    private static void variantsAmong(JsonNode slots, Set<String> keys, List<FieldError> out) {
        for (int i = 0; i < slots.size(); i++) {
            JsonNode variants = slots.get(i);
            for (int j = 0; j < variants.size(); j++) {
                if (!keys.contains(variants.get(j).asText().toUpperCase(java.util.Locale.ROOT))) {
                    out.add(new FieldError("answerKey.slots[" + i + "][" + j + "]", "is not one of the options"));
                }
            }
        }
    }
}
