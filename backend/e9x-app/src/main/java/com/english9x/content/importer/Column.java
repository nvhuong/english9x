package com.english9x.content.importer;

import java.util.List;

/**
 * One column of the Excel of a question type, derived from its JSON Schema.
 *
 * @param header   the header cell ({@code stem}, {@code options.2.text}, {@code answer.slots.1}, {@code audio_media_file})
 * @param path     where the value goes: property names (String) and 0-based array positions (Integer), from the content or the answer key
 * @param list     the cell holds several values separated by {@code |} (the accepted variants of one blank)
 * @param allowed  the allowed values of an enum or boolean; empty = free
 * @param schema   the schema of ONE value of the column (its limits make the example row)
 */
record Column(String header, Side side, List<Object> path, Kind kind, boolean required, boolean list, List<String> allowed, String title, String constraint,
              com.fasterxml.jackson.databind.JsonNode schema) {

    enum Side { CONTENT, ANSWER }

    enum Kind { STRING, INTEGER, NUMBER, BOOLEAN, MEDIA }

    /** The kind of file a media column takes, from the property name ({@code audioMediaId} is audio); {@code null} when any kind is allowed. */
    String mediaKind() {
        if (kind != Kind.MEDIA) {
            return null;
        }
        String name = String.valueOf(path.get(path.size() - 1)).toLowerCase(java.util.Locale.ROOT);
        return name.startsWith("audio") ? "AUDIO" : name.startsWith("image") ? "IMAGE" : name.startsWith("video") ? "VIDEO" : null;
    }
}
