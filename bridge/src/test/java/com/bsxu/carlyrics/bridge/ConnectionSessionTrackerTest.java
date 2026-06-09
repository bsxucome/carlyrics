package com.bsxu.carlyrics.bridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConnectionSessionTrackerTest {

    @Test
    public void tracksHandshakeKeepaliveAndIdleLifecycle() {
        ConnectionSessionTracker tracker = new ConnectionSessionTracker();

        tracker.begin(1000L);
        assertFalse(tracker.isHandshakeComplete());
        assertEquals(
                ConnectionMaintenancePolicy.Action.NONE,
                tracker.evaluate(6999L)
        );

        tracker.completeHandshake(7000L);
        assertTrue(tracker.isHandshakeComplete());
        assertEquals(7000L, tracker.getHandshakeCompletedElapsedMs());

        tracker.noteOutbound(8000L);
        tracker.noteInbound(9000L);
        assertEquals(
                ConnectionMaintenancePolicy.Action.SEND_KEEPALIVE,
                tracker.evaluate(12000L)
        );

        tracker.noteOutbound(12000L);
        assertEquals(
                ConnectionMaintenancePolicy.Action.IDLE_TIMEOUT,
                tracker.evaluate(24000L)
        );

        tracker.reset();
        assertFalse(tracker.isHandshakeComplete());
        assertEquals(
                ConnectionMaintenancePolicy.Action.NONE,
                tracker.evaluate(50000L)
        );
    }

    @Test
    public void incompleteHandshakeTimesOut() {
        ConnectionSessionTracker tracker = new ConnectionSessionTracker();
        tracker.begin(1000L);

        assertEquals(
                ConnectionMaintenancePolicy.Action.HANDSHAKE_TIMEOUT,
                tracker.evaluate(7000L)
        );
    }
}
