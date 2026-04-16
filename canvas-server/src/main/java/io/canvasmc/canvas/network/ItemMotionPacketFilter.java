package io.canvasmc.canvas.network;

public final class ItemMotionPacketFilter {
    /**
     * Small movement jitter threshold for items to avoid packet spam without causing visible snapping.
     * Kept intentionally conservative for visual stability.
     */
    private static final double MIN_DELTA_MOVEMENT_SQUARED = 0.0004D;
    /**
     * Force a sync every few ticks so clients stay visually smooth even if movement is below threshold.
     */
    private static final int MAX_PACKET_INTERVAL_TICKS = 10;

    private ItemMotionPacketFilter() {}

    public static boolean shouldSendItemMotionPacket(final double deltaMovementDistanceSqr, final boolean movedPosition, final int tickCount) {
        if (!movedPosition) return false;
        return deltaMovementDistanceSqr >= MIN_DELTA_MOVEMENT_SQUARED
            || tickCount % MAX_PACKET_INTERVAL_TICKS == 0;
    }
}
