package com.bsxu.carlyrics.bridge;

public final class HelloMessage {

    public final String role;
    public final String deviceName;
    public final String versionName;

    public HelloMessage(String role, String deviceName, String versionName) {
        this.role = role == null ? "" : role;
        this.deviceName = deviceName == null ? "" : deviceName;
        this.versionName = versionName == null ? "" : versionName;
    }
}

