package com.vibemusic.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps the machine-readable recommendation block out of the visible text stream. */
public final class AgentStreamParser {
    private static final Pattern BLOCK_START = Pattern.compile("(?i)(?:<|&lt;|\\\\<)\\s*vibe-songs\\b");
    private static final Pattern IDS = Pattern.compile("(?s)<vibe-songs>\\s*(\\[[^]]*])\\s*</vibe-songs>");
    private static final Pattern URL = Pattern.compile("(?i)https?://\\S+");
    private final ObjectMapper mapper;
    private final Consumer<String> textSink;
    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder hidden = new StringBuilder();
    private boolean inBlock;

    public AgentStreamParser(ObjectMapper mapper, Consumer<String> textSink) {
        this.mapper = mapper;
        this.textSink = textSink;
    }

    public synchronized void accept(String delta) {
        if (delta == null || delta.isEmpty()) return;
        if (inBlock) { hidden.append(delta); return; }
        pending.append(delta);
        Matcher start = BLOCK_START.matcher(pending);
        if (start.find()) {
            emitLines(pending.substring(0, start.start()));
            hidden.append(pending.substring(start.start()));
            pending.setLength(0);
            inBlock = true;
            return;
        }
        // Hold the last line and a possible partial marker. Nothing unfinished reaches the browser.
        int lastLine = pending.lastIndexOf("\n");
        if (lastLine >= 0) {
            int flushEnd = lastLine + 1;
            String completed = pending.substring(0, flushEnd);
            int possible = Math.max(completed.lastIndexOf("<"), completed.lastIndexOf("&lt;"));
            if (possible >= 0 && completed.substring(possible).toLowerCase().matches("(?s)(?:<|&lt;)\\s*vibe[^>]*")) flushEnd = possible;
            String visible = pending.substring(0, flushEnd);
            pending.delete(0, flushEnd);
            emitLines(visible);
        }
    }

    public synchronized List<Long> finish() {
        if (!inBlock) emitLines(pending.toString());
        pending.setLength(0);
        Matcher match = IDS.matcher(hidden);
        if (!match.find()) return List.of();
        try {
            List<Long> ids = mapper.readValue(match.group(1), new TypeReference<>() {});
            return ids == null ? List.of() : ids.stream().filter(id -> id != null && id > 0).distinct().limit(5).toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void emitLines(String text) {
        String safe = URL.matcher(text).replaceAll("[链接已隐藏]");
        safe = safe.replaceAll("(?m)^\\s*(?:```(?:json)?|\\[\\s*\\{|\\{\\s*\\\"(?:songId|coverUrl|audioUrl)).*(?:\\R|$)", "");
        if (!safe.isEmpty()) textSink.accept(safe);
    }
}
