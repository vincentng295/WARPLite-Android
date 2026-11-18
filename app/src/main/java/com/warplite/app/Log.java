package com.warplite.app;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** In-memory ring buffer shown on the Diagnostics screen. */
final class Log {
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private Log() {
    }

    static synchronized void add(String s) {
        if (LINES.size() >= 200) LINES.removeFirst();
        LINES.addLast(FMT.format(new Date()) + "  " + s);
    }

    static synchronized String text() {
        StringBuilder sb = new StringBuilder();
        for (String l : LINES) sb.append(l).append('\n');
        return sb.toString();
    }
}
