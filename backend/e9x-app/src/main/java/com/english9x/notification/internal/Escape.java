package com.english9x.notification.internal;

/** Output escaping. Every user-controlled string (display name, event data) passes through here before it is written into markup. */
final class Escape {

    private Escape() {}

    /** HTML text and attribute values: also valid for Telegram's HTML parse mode, which knows &amp; &lt; &gt; &quot;. */
    static String html(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Telegram's HTML subset has no &#39;: it needs only the four. */
    static String telegram(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
