package com.english9x.grading.ai.api;

/** What the learner gave: text (Writing) or audio (Speaking: bytes and MIME type). */
public sealed interface Response {

    record Text(String text) implements Response {
        @Override
        public String toString() {
            return "Text[]"; // a learner's words are never logged by accident
        }
    }

    record Audio(byte[] bytes, String mime) implements Response {
        @Override
        public String toString() {
            return "Audio[" + mime + ", " + bytes.length + " bytes]";
        }
    }
}
