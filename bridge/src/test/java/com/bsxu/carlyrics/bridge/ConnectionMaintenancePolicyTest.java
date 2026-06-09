package com.bsxu.carlyrics.bridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ConnectionMaintenancePolicyTest {

    @Test
    public void waitsForHandshakeUntilTimeout() {
        assertEquals(
                ConnectionMaintenancePolicy.Action.NONE,
                ConnectionMaintenancePolicy.evaluate(false, 1000L, 1000L, 0L, 6999L)
        );
        assertEquals(
                ConnectionMaintenancePolicy.Action.HANDSHAKE_TIMEOUT,
                ConnectionMaintenancePolicy.evaluate(false, 1000L, 1000L, 0L, 7000L)
        );
    }

    @Test
    public void idleTimeoutTakesPriorityOverKeepalive() {
        assertEquals(
                ConnectionMaintenancePolicy.Action.IDLE_TIMEOUT,
                ConnectionMaintenancePolicy.evaluate(true, 0L, 1000L, 1000L, 16000L)
        );
    }

    @Test
    public void requestsKeepaliveAfterOutboundSilence() {
        assertEquals(
                ConnectionMaintenancePolicy.Action.NONE,
                ConnectionMaintenancePolicy.evaluate(true, 0L, 1000L, 2000L, 5999L)
        );
        assertEquals(
                ConnectionMaintenancePolicy.Action.SEND_KEEPALIVE,
                ConnectionMaintenancePolicy.evaluate(true, 0L, 1000L, 2000L, 6000L)
        );
    }

    @Test
    public void zeroOutboundTimestampAllowsInitialKeepalive() {
        assertEquals(
                ConnectionMaintenancePolicy.Action.SEND_KEEPALIVE,
                ConnectionMaintenancePolicy.evaluate(true, 0L, 1000L, 0L, 4000L)
        );
    }

    @Test
    public void clockRollbackDoesNotTriggerMaintenance() {
        assertEquals(
                ConnectionMaintenancePolicy.Action.NONE,
                ConnectionMaintenancePolicy.evaluate(true, 0L, 5000L, 5000L, 4000L)
        );
    }

    @Test
    public void backoffDoublesAndCaps() {
        assertEquals(1200L, ConnectionMaintenancePolicy.computeBackoffDelay(1200L, 15000L, -1));
        assertEquals(1200L, ConnectionMaintenancePolicy.computeBackoffDelay(1200L, 15000L, 0));
        assertEquals(2400L, ConnectionMaintenancePolicy.computeBackoffDelay(1200L, 15000L, 1));
        assertEquals(9600L, ConnectionMaintenancePolicy.computeBackoffDelay(1200L, 15000L, 3));
        assertEquals(15000L, ConnectionMaintenancePolicy.computeBackoffDelay(1200L, 15000L, 4));
        assertEquals(15000L, ConnectionMaintenancePolicy.computeBackoffDelay(1200L, 15000L, 100));
    }

    @Test(expected = IllegalArgumentException.class)
    public void backoffRejectsInvalidBounds() {
        ConnectionMaintenancePolicy.computeBackoffDelay(0L, 15000L, 1);
    }
}
