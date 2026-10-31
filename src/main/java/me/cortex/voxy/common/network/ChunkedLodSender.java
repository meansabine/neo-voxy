package me.cortex.voxy.common.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import me.cortex.voxy.common.Logger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles bandwidth-limited chunked transfer of LOD section data to players.
 * <p>
 * Based on Distant Horizons' FullDataPayloadSender pattern. Large sections are
 * split into smaller chunks (64KB by default) and sent at a controlled rate
 * based on the player's bandwidth allocation.
 */
public class ChunkedLodSender implements AutoCloseable {

    /** Default chunk size: 64KB (smaller than DH's 1MB for lower latency) */
    public static final int CHUNK_SIZE = 65536;

    /** Tick rate for sending (20 ticks/sec = 50ms per tick) */
    private static final int TICK_RATE = 20;

    /** Timer for tick-based sending */
    private static final Timer SEND_TIMER = new Timer("VoxyChunkedLodSender", true);

    private final ServerPlayer player;
    private final SharedBandwidthLimit sharedBandwidthLimit;
    private final int perPlayerLimitKBps;

    private final ConcurrentLinkedQueue<PendingTransfer> transferQueue = new ConcurrentLinkedQueue<>();
    private final TimerTask tickTask;
    private final AtomicBoolean isActive = new AtomicBoolean(true);

    // Stats
    private long totalBytesSent = 0;
    private int sectionsQueued = 0;
    private int sectionsCompleted = 0;

    /**
     * Create a chunked sender for a specific player.
     */
    public ChunkedLodSender(ServerPlayer player, SharedBandwidthLimit sharedBandwidthLimit, int perPlayerLimitKBps) {
        this.player = player;
        this.sharedBandwidthLimit = sharedBandwidthLimit;
        this.perPlayerLimitKBps = perPlayerLimitKBps;

        this.tickTask = new TimerTask() {
            @Override
            public void run() {
                tick();
            }
        };

        SEND_TIMER.scheduleAtFixedRate(tickTask, 0, 1000 / TICK_RATE);
        sharedBandwidthLimit.setSenderActive(this, true);
    }

    /**
     * Queue a section for chunked transfer.
     * 
     * @param sectionData Serialized section data
     * @param sectionId   Unique ID for this section (for reassembly)
     * @param onComplete  Callback when transfer completes
     */
    public void queueSection(byte[] sectionData, int sectionId, Runnable onComplete) {
        if (!isActive.get()) {
            return;
        }

        transferQueue.add(new PendingTransfer(sectionData, sectionId, onComplete));
        sectionsQueued++;
    }

    /**
     * Queue a section without completion callback.
     */
    public void queueSection(byte[] sectionData, int sectionId) {
        queueSection(sectionData, sectionId, null);
    }

    /**
     * Called every tick to send pending data.
     */
    private void tick() {
        if (!isActive.get() || !player.isAlive()) {
            return;
        }

        // Calculate bytes we can send this tick
        int bytesRemaining = sharedBandwidthLimit.getBytesPerTick(perPlayerLimitKBps);

        while (bytesRemaining > 0) {
            PendingTransfer transfer = transferQueue.peek();
            if (transfer == null) {
                break;
            }

            // Calculate chunk size (min of remaining bytes, CHUNK_SIZE, and remaining data)
            int dataRemaining = transfer.buffer.readableBytes();
            int chunkSize = Math.min(Math.min(bytesRemaining, CHUNK_SIZE), dataRemaining);

            if (chunkSize <= 0) {
                break;
            }

            // Read chunk from buffer
            byte[] chunkData = new byte[chunkSize + 9]; // 1 byte type + 4 byte sectionId + 4 byte offset
            int offset = transfer.bytesSent;

            // Build chunk packet: [sectionId:4][offset:4][isLast:1][data:N]
            chunkData[0] = (byte) (transfer.sectionId >> 24);
            chunkData[1] = (byte) (transfer.sectionId >> 16);
            chunkData[2] = (byte) (transfer.sectionId >> 8);
            chunkData[3] = (byte) transfer.sectionId;
            chunkData[4] = (byte) (offset >> 24);
            chunkData[5] = (byte) (offset >> 16);
            chunkData[6] = (byte) (offset >> 8);
            chunkData[7] = (byte) offset;

            boolean isLast = (dataRemaining - chunkSize) <= 0;
            chunkData[8] = (byte) (isLast ? 1 : 0);

            // Copy actual data
            transfer.buffer.readBytes(chunkData, 9, chunkSize);
            transfer.bytesSent += chunkSize;

            // Send the chunk
            VoxyNetworkHandler.sendToPlayer(player, VoxyPacketPayload.chunk(chunkData));

            bytesRemaining -= chunkSize;
            totalBytesSent += chunkSize;

            // Check if transfer is complete
            if (transfer.buffer.readableBytes() == 0) {
                transferQueue.poll();
                sectionsCompleted++;

                if (transfer.onComplete != null) {
                    try {
                        transfer.onComplete.run();
                    } catch (Exception e) {
                        Logger.error("Error in transfer completion callback: " + e.getMessage());
                    }
                }
            }
        }

        // Update active status based on queue
        sharedBandwidthLimit.setSenderActive(this, !transferQueue.isEmpty());
    }

    /**
     * Get the number of pending transfers.
     */
    public int getPendingCount() {
        return transferQueue.size();
    }

    /**
     * Get total bytes sent.
     */
    public long getTotalBytesSent() {
        return totalBytesSent;
    }

    /**
     * Get stats string for debugging.
     */
    public String getStatsString() {
        return String.format("Queued: %d, Completed: %d, Pending: %d, Sent: %.2f MB",
                sectionsQueued, sectionsCompleted, transferQueue.size(),
                totalBytesSent / (1024.0 * 1024.0));
    }

    @Override
    public void close() {
        isActive.set(false);
        tickTask.cancel();
        sharedBandwidthLimit.setSenderActive(this, false);
        transferQueue.clear();
    }

    /**
     * Represents a pending section transfer.
     */
    private static class PendingTransfer {
        final ByteBuf buffer;
        final int sectionId;
        final Runnable onComplete;
        int bytesSent = 0;

        PendingTransfer(byte[] data, int sectionId, Runnable onComplete) {
            this.buffer = Unpooled.wrappedBuffer(data);
            this.sectionId = sectionId;
            this.onComplete = onComplete;
        }
    }
}
