package com.english9x.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.Map;

/** A minimal Telegram Bot API for tests: records {@code POST /bot<token>/sendMessage}; can be told to fail. */
public final class FakeTelegram implements AutoCloseable {

    public record Sent(String token, JsonNode body) {
        public String chatId() {
            return body.path("chat_id").asText();
        }

        public String text() {
            return body.path("text").asText();
        }

        public String parseMode() {
            return body.path("parse_mode").asText();
        }
    }

    public static final String BOT_TOKEN = "123456:TEST-bot-token-never-log-me";

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpServer server;
    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private volatile int failWith;
    private final Map<String, Long> delays = new ConcurrentHashMap<>();

    public FakeTelegram() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", ex -> {
            byte[] req = ex.getRequestBody().readAllBytes();
            Long delay = delays.get(JSON.readTree(req).path("chat_id").asText());
            if (delay != null) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            String path = ex.getRequestURI().getPath();
            int status = 200;
            String reply = "{\"ok\":true,\"result\":{}}";
            if (failWith != 0) {
                status = failWith;
                reply = "{\"ok\":false,\"error_code\":" + failWith + ",\"description\":\"fake failure\"}";
            } else if (path.matches("/bot[^/]+/sendMessage")) {
                String token = path.substring(4, path.indexOf("/sendMessage"));
                sent.add(new Sent(token, JSON.readTree(req)));
            } else {
                status = 404;
                reply = "{\"ok\":false}";
            }
            if (failWith != 0 && path.matches("/bot[^/]+/sendMessage")) {
                // record attempts even when failing
                sent.add(new Sent(path.substring(4, path.indexOf("/sendMessage")), JSON.readTree(req)));
            }
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-telegram");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Every sendMessage seen for the chat, failed attempts included. */
    public List<Sent> to(String chatId) {
        List<Sent> out = new ArrayList<>();
        for (Sent s : sent) {
            if (s.chatId().equals(chatId)) {
                out.add(s);
            }
        }
        return out;
    }

    /** Every call for this chat is answered only after the delay (a slow chat must not hold up the others). */
    public void delayChat(String chatId, long millis) {
        delays.put(chatId, millis);
    }

    /** 0 = work; otherwise answer every call with this HTTP status. */
    public void failWith(int status) {
        this.failWith = status;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
