package com.bsxu.carlyrics.phone.lyrics;

import com.bsxu.carlyrics.bridge.RemoteLyricLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PhoneLyricsResult {

    public final String trackKey;
    public final String sourceLabel;
    public final boolean synced;
    public final List<RemoteLyricLine> lines;

    public PhoneLyricsResult(String trackKey, String sourceLabel, boolean synced, List<RemoteLyricLine> lines) {
        this.trackKey = trackKey == null ? "" : trackKey;
        this.sourceLabel = sourceLabel == null ? "" : sourceLabel;
        this.synced = synced;
        this.lines = Collections.unmodifiableList(new ArrayList<RemoteLyricLine>(lines));
    }
}

