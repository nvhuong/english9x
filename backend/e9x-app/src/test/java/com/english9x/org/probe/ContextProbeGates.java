package com.english9x.org.probe;

/** The test's handle on the gates of {@link ContextProbe}. */
public final class ContextProbeGates {

    private ContextProbeGates() {}

    public static void open(String id, int parties) {
        ContextProbe.GATES.put(id, new ContextProbe.Gate(parties));
    }

    public static ContextProbe.Gate get(String id) {
        return ContextProbe.GATES.get(id);
    }

    public static void close(String id) {
        ContextProbe.GATES.remove(id);
    }
}
