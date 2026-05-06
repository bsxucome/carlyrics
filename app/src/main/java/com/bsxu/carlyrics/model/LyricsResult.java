package com.bsxu.carlyrics.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LyricsResult {

    private final List<LyricLine> lines;
    private final String sourceLabel;
    private final boolean synced;
    private final String rawText;

    public LyricsResult(List<LyricLine> lines, String sourceLabel, boolean synced, String rawText) {
        this.lines = Collections.unmodifiableList(new ArrayList<LyricLine>(lines));
        this.sourceLabel = sourceLabel == null ? "" : sourceLabel;
        this.synced = synced;
        this.rawText = rawText == null ? "" : rawText;
    }

    public List<LyricLine> getLines() {
        return lines;
    }

    public String getSourceLabel() {
        return sourceLabel;
    }

    public boolean isSynced() {
        return synced;
    }

    public String getRawText() {
        return rawText;
    }

    public int findActiveLineIndex(long positionMs) {
        if (!synced || lines.isEmpty()) {
            return -1;
        }
        int activeIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            long currentTime = lines.get(i).getTimeMs();
            if (currentTime <= positionMs) {
                activeIndex = i;
            } else {
                break;
            }
        }
        return activeIndex;
    }
}

