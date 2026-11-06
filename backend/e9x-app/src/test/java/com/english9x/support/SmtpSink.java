package com.english9x.support;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A minimal SMTP server that keeps what it receives, so tests exercise the real mail path (JavaMail over a socket). */
public final class SmtpSink implements AutoCloseable {

    public record Mail(String to, String subject, String body) {
        /** The first http(s) link in the body. */
        public String link() {
            Matcher m = Pattern.compile("https?://[^\\s\"'<>)]+").matcher(body);
            if (!m.find()) {
                throw new AssertionError("no link in mail: " + body);
            }
            return m.group().replaceAll("[.,;]+$", "");
        }

        public String linkParam(String name) {
            Matcher m = Pattern.compile("[?&]" + name + "=([^&\\s]+)").matcher(link());
            if (!m.find()) {
                throw new AssertionError("no " + name + " in " + link());
            }
            return java.net.URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
        }
    }

    private final ServerSocket server;
    private final List<Mail> mails = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;
    private volatile boolean down;

    /** While down, every connection is answered with 421 and closed (the mail server is unavailable). */
    public void setDown(boolean down) {
        this.down = down;
    }

    public SmtpSink() {
        try {
            server = new ServerSocket(0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Thread t = new Thread(this::accept, "smtp-sink");
        t.setDaemon(true);
        t.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    public List<Mail> to(String address) {
        List<Mail> out = new ArrayList<>();
        for (Mail m : mails) {
            if (m.to().equalsIgnoreCase(address)) {
                out.add(m);
            }
        }
        return out;
    }

    /** Waits for the (n+1)-th mail to {@code address}. */
    public Mail await(String address, int alreadyHave) {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < end) {
            List<Mail> now = to(address);
            if (now.size() > alreadyHave) {
                return now.get(alreadyHave);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("no mail #" + (alreadyHave + 1) + " to " + address + " within 10 s; have " + to(address).size());
    }

    /** Waits for a mail to {@code address} whose subject contains {@code subjectPart} (mail is sent asynchronously, so order is not guaranteed). */
    public Mail awaitSubject(String address, String subjectPart) {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < end) {
            for (Mail m : to(address)) {
                if (m.subject() != null && m.subject().contains(subjectPart)) {
                    return m;
                }
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("no mail to " + address + " with subject containing '" + subjectPart + "'; have " + to(address));
    }

    public Mail await(String address) {
        return await(address, 0);
    }

    /** Gives the app time to (not) send, then reports how many mails went to {@code address}. */
    public int countAfterSettling(String address) {
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return to(address).size();
    }

    private void accept() {
        while (running) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> handle(s), "smtp-sink-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
             Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1)) {
            if (down) {
                reply(out, "421 service not available");
                return;
            }
            reply(out, "220 sink ESMTP");
            List<String> rcpt = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null) {
                String u = line.toUpperCase(Locale.ROOT);
                if (u.startsWith("EHLO")) {
                    reply(out, "250-sink\r\n250 8BITMIME");
                } else if (u.startsWith("HELO") || u.startsWith("NOOP")) {
                    reply(out, "250 ok");
                } else if (u.startsWith("MAIL FROM")) {
                    rcpt.clear();
                    reply(out, "250 ok");
                } else if (u.startsWith("RCPT TO")) {
                    Matcher m = Pattern.compile("<([^>]*)>").matcher(line);
                    if (m.find()) {
                        rcpt.add(m.group(1));
                    }
                    reply(out, "250 ok");
                } else if (u.startsWith("RSET")) {
                    rcpt.clear();
                    reply(out, "250 ok");
                } else if (u.startsWith("DATA")) {
                    reply(out, "354 go");
                    StringBuilder data = new StringBuilder();
                    while ((line = in.readLine()) != null && !line.equals(".")) {
                        data.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
                    }
                    store(rcpt, data.toString());
                    reply(out, "250 queued");
                } else if (u.startsWith("QUIT")) {
                    reply(out, "221 bye");
                    return;
                } else {
                    reply(out, "502 unsupported");
                }
            }
        } catch (IOException ignored) {
            // client went away
        }
    }

    private void store(List<String> recipients, String raw) {
        try {
            MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()),
                    new ByteArrayInputStream(raw.getBytes(StandardCharsets.ISO_8859_1)));
            String body = String.valueOf(msg.getContent());
            for (String r : recipients) {
                mails.add(new Mail(r, msg.getSubject(), body));
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reply(Writer out, String text) throws IOException {
        out.write(text + "\r\n");
        out.flush();
    }

    @Override
    public void close() throws IOException {
        running = false;
        server.close();
    }
}
