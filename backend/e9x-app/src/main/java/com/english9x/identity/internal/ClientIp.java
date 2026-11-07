package com.english9x.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The address the rate limiter counts against. {@code X-Forwarded-For} is only believed when the TCP peer is one of
 * the configured trusted proxies ({@code e9x.auth.trusted-proxies}, CIDRs; default none = header ignored). Then the
 * list is read from the right, skipping trusted proxies; the first other address is the client. Whatever a client
 * puts at the left of the header therefore never matters, and a client talking to the app directly cannot use it at all.
 * Caddy (the trusted proxy in the Compose stack) replaces a client-sent X-Forwarded-For with the real peer address.
 */
@Component
class ClientIp {

    private final List<Cidr> trusted;

    ClientIp(AuthProperties props) {
        List<Cidr> list = new ArrayList<>();
        for (String c : props.trustedProxies()) {
            if (!c.isBlank()) {
                list.add(Cidr.parse(c.trim()));
            }
        }
        this.trusted = List.copyOf(list);
    }

    String of(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (!isTrusted(peer)) {
            return peer;
        }
        String header = String.join(",", java.util.Collections.list(request.getHeaders("X-Forwarded-For")));
        if (header.isBlank()) {
            return peer;
        }
        String[] parts = header.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            String candidate = parts[i].trim();
            if (candidate.isEmpty()) {
                continue;
            }
            if (!isTrusted(candidate)) {
                return candidate;
            }
        }
        return peer;
    }

    private boolean isTrusted(String address) {
        for (Cidr c : trusted) {
            if (c.contains(address)) {
                return true;
            }
        }
        return false;
    }

    record Cidr(byte[] network, int prefix) {
        static Cidr parse(String text) {
            String[] p = text.split("/");
            try {
                byte[] addr = InetAddress.getByName(p[0]).getAddress();
                int prefix = p.length > 1 ? Integer.parseInt(p[1]) : addr.length * 8;
                if (prefix < 0 || prefix > addr.length * 8) {
                    throw new IllegalArgumentException("bad prefix in " + text);
                }
                return new Cidr(addr, prefix);
            } catch (UnknownHostException | NumberFormatException e) {
                throw new IllegalArgumentException("bad trusted proxy '" + text + "'", e);
            }
        }

        boolean contains(String address) {
            byte[] a;
            try {
                if (address == null || !address.matches("[0-9a-fA-F:.]+")) {
                    return false; // never resolve names
                }
                a = InetAddress.getByName(address).getAddress();
            } catch (UnknownHostException e) {
                return false;
            }
            if (a.length != network.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (a[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = 0xFF << (8 - rest);
            return (a[full] & mask) == (network[full] & mask);
        }
    }
}
