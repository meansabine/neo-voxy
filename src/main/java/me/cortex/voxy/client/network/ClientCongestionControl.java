package me.cortex.voxy.client.network;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.network.VoxyNetworkHandler;
import me.cortex.voxy.common.network.VoxyPacketPayload;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Client-side AIMD (Additive Increase / Multiplicative Decrease) congestion
 * control.
 * <p>
 * Based on Distant Horizons' ClientCongestionControl. Monitors actual
 * throughput
 * and adjusts the desired rate to find the optimal bandwidth without causing
 * congestion.
 */
public class ClientCongestionControl {

    /** Rate adjustment interval in milliseconds */
    private static final long INTERVAL_MS = 1000;

    /** Additive increase amount (50 KB/s per interval when successful) */
    private static final double ADDITIVE_INCREASE = 50_000;

    /** Minimum rate to maintain even under heavy congestion */
    private static final double MIN_RATE = 1000; // 1 KB/s

    private final AtomicLong bytesReceived = new AtomicLong(0);
    private double desiredRate = ADDITIVE_INCREASE;
    private long lastAdjustTime = System.currentTimeMillis();
    private final Runnable rateUpdateHandler;

    /**
     * Create congestion control that notifies on rate changes.
     */
    public ClientCongestionControl(Runnable rateUpdateHandler) {
        this.rateUpdateHandler = rateUpdateHandler;
    }

    /**
     * Create congestion control without notification handler.
     */
    public ClientCongestionControl() {
        this(() -> {
        });
    }

    /**
     * Reset to initial state (e.g., on connect).
     */
    public void reset() {
        desiredRate = ADDITIVE_INCREASE;
        lastAdjustTime = System.currentTimeMillis();
        bytesReceived.set(0);
    }

    /**
     * Called when payload data is received from the server.
     */
    public void onPayloadReceived(int bytes) {
        bytesReceived.addAndGet(bytes);

        long now = System.currentTimeMillis();
        if (now - lastAdjustTime >= INTERVAL_MS) {
            adjustRate(now);
        }
    }

    /**
     * Called when a LOD chunk packet is received.
     */
    public void onChunkReceived(VoxyPacketPayload payload) {
        if (payload.messageType() == VoxyPacketPayload.MSG_LOD_CHUNK ||
                payload.messageType() == VoxyPacketPayload.MSG_LOD_SECTION) {
            onPayloadReceived(payload.data().length);
        }
    }

    /**
     * Adjust the rate based on observed throughput.
     */
    private void adjustRate(long now) {
        double throughput = bytesReceived.getAndSet(0);

        if (throughput >= desiredRate) {
            // Good throughput achieved, increase rate (additive increase)
            desiredRate += ADDITIVE_INCREASE;
        } else {
            // Not hitting target, back off (multiplicative decrease)
            // Use a gentler back-off than traditional AIMD
            desiredRate = Math.max(throughput - ADDITIVE_INCREASE / 2, MIN_RATE);
        }

        lastAdjustTime = now;

        // Notify handler and optionally send update to server
        rateUpdateHandler.run();
    }

    /**
     * Send current desired rate to server.
     * Only useful if server supports rate adaptation.
     */
    public void sendRateUpdate() {
        if (VoxyNetworkHandler.shouldEnableStreaming()) {
            int rateKBps = getDesiredRateKBps();
            VoxyNetworkHandler.sendToServer(VoxyPacketPayload.rateUpdate(rateKBps));
        }
    }

    /**
     * Get the current desired rate in KB/s.
     */
    public int getDesiredRateKBps() {
        return (int) (desiredRate / 1000);
    }

    /**
     * Get the raw desired rate in bytes/s.
     */
    public double getDesiredRateBps() {
        return desiredRate;
    }

    /**
     * Get total bytes received since last reset.
     */
    public long getTotalBytesReceived() {
        return bytesReceived.get();
    }
}
