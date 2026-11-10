package com.english9x.content.importer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The media zip of an import. Files are found by their BASE name (a folder in the zip does not matter); a name that occurs twice is ambiguous and refused when a
 * row uses it. Nothing is ever unpacked by the name an entry carries: an entry is copied to a temp file the importer names itself, so a hostile path in the zip
 * reaches nothing. The size is judged twice: by what the entries declare, and by the bytes actually read (a declared size can lie).
 */
final class ZipMedia implements AutoCloseable {

    /** Zip refused as a whole. */
    static final class Unusable extends Exception {
        Unusable(String message) {
            super(message);
        }
    }

    static final int MAX_ENTRIES = 10_000;

    private final ZipFile zip;
    private final Map<String, ZipEntry> byName = new HashMap<>();
    private final List<String> ambiguous = new ArrayList<>();
    private final long maxBytes;
    /** The bytes actually read per file (a second read of a file replaces its first): their sum is held to the limit. */
    private final Map<String, Long> actual = new HashMap<>();

    private ZipMedia(ZipFile zip, long maxBytes) {
        this.zip = zip;
        this.maxBytes = maxBytes;
    }

    static ZipMedia open(Path file, long maxBytes) throws Unusable, IOException {
        ZipFile zip;
        try {
            zip = new ZipFile(file.toFile());
        } catch (java.util.zip.ZipException e) {
            throw new Unusable("không đọc được file zip media");
        }
        ZipMedia m = new ZipMedia(zip, maxBytes);
        long declared = 0;
        int entries = 0;
        for (var en = zip.entries(); en.hasMoreElements();) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory() || e.getName().startsWith("__MACOSX/") || base(e.getName()).startsWith(".")) {
                continue;
            }
            if (++entries > MAX_ENTRIES) {
                zip.close();
                throw new Unusable("file zip media có hơn " + MAX_ENTRIES + " tệp");
            }
            declared += Math.max(0, e.getSize());
            if (declared > maxBytes) {
                zip.close();
                throw new Unusable("file zip media giải nén ra lớn hơn giới hạn của mỗi lô");
            }
            String name = base(e.getName());
            if (m.byName.putIfAbsent(name, e) != null && !m.ambiguous.contains(name)) {
                m.ambiguous.add(name);
            }
        }
        return m;
    }

    private static String base(String name) {
        return name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
    }

    boolean contains(String name) {
        return byName.containsKey(name);
    }

    boolean isAmbiguous(String name) {
        return ambiguous.contains(name);
    }

    /** Copies one file to {@code target}; the sum of everything copied by this object is held to the limit. @throws Unusable when the real bytes exceed it */
    void extract(String name, Path target) throws IOException, Unusable {
        ZipEntry e = byName.get(name);
        if (e == null) {
            throw new IOException("not in the zip");
        }
        long count = 0;
        try (InputStream in = zip.getInputStream(e); OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0;) {
                count += n;
                long others = actual.entrySet().stream().filter(x -> !x.getKey().equals(name)).mapToLong(Map.Entry::getValue).sum();
                if (others + count > maxBytes) {
                    throw new Unusable("file zip media giải nén ra lớn hơn giới hạn của mỗi lô");
                }
                out.write(buf, 0, n);
            }
        }
        actual.put(name, count);
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}
