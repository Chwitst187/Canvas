package org.bukkit.craftbukkit;

import com.google.common.base.Preconditions;
import net.minecraft.server.ServerTickRateManager;
import org.bukkit.ServerTickManager;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.Entity;

final class CraftServerTickManager implements ServerTickManager {

    private final ServerTickRateManager manager;

    CraftServerTickManager(ServerTickRateManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean isRunningNormally() {
        throw new UnsupportedOperationException("Not supported in region threading"); // Canvas - region threading
    }

    @Override
    public boolean isStepping() {
        throw new UnsupportedOperationException("Not supported in region threading"); // Canvas - region threading
    }

    @Override
    public boolean isSprinting() {
        throw new UnsupportedOperationException("Not supported in region threading"); // Canvas - region threading
    }

    @Override
    public boolean isFrozen() {
        throw new UnsupportedOperationException("Not supported in region threading"); // Canvas - region threading
    }

    @Override
    public float getTickRate() {
        throw new UnsupportedOperationException("Not supported in region threading"); // Canvas - region threading
    }

    @Override
    public void setTickRate(final float tickRate) {
        throw new UnsupportedOperationException("Not supported in region threading"); // Canvas - region threading
    }

    @Override
    public void setFrozen(final boolean frozen) {
        throw new UnsupportedOperationException("Not supported in region threading"); // Canvas - region threading
    }

    @Override
    public boolean stepGameIfFrozen(final int ticks) {
        throw new UnsupportedOperationException("Not supported in region threading"); // Canvas - region threading
    }

    @Override
    public boolean stopStepping() {
        throw new UnsupportedOperationException("Not supported in region threading"); // Canvas - region threading
    }

    @Override
    public boolean requestGameToSprint(final int ticks) {
        throw new UnsupportedOperationException("Not supported in region threading"); // Canvas - region threading
    }

    @Override
    public boolean stopSprinting() {
        throw new UnsupportedOperationException("Not supported in region threading"); // Canvas - region threading
    }

    @Override
    public boolean isFrozen(final Entity entity) {
        throw new UnsupportedOperationException("Not supported in region threading"); // Canvas - region threading
    }

    @Override
    public int getFrozenTicksToRun() {
        throw new UnsupportedOperationException("Not supported in region threading"); // Canvas - region threading
    }
}
