package com.bsxu.carlyrics.bridge;

public final class ControlMessage {

    public final String action;

    public ControlMessage(String action) {
        this.action = action == null ? "" : action;
    }
}

