package com.bsxu.carlyrics.bridge;

public final class HelloMessage {

    public final int protocolVersion;
    public final String appDeviceId;
    public final String role;
    public final String deviceName;
    public final String versionName;

    public HelloMessage(int protocolVersion, String appDeviceId, String role, String deviceName, String versionName) {
        this.protocolVersion = Math.max(protocolVersion, 0);
        this.appDeviceId = appDeviceId == null ? "" : appDeviceId;
        this.role = role == null ? "" : role;
        this.deviceName = deviceName == null ? "" : deviceName;
        this.versionName = versionName == null ? "" : versionName;
    }
}
