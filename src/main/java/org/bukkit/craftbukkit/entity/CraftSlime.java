package org.bukkit.craftbukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Slime;

public class CraftSlime extends CraftMob implements Slime, CraftEnemy {

    public CraftSlime(CraftServer server, net.minecraft.world.entity.monster.Slime entity) {
        super(server, entity);
    }

    @Override
    public net.minecraft.world.entity.monster.Slime getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (net.minecraft.world.entity.monster.Slime) this.entity;
    }

    @Override
    public int getSize() {
        return this.getHandle().getSize();
    }

    @Override
    public void setSize(int size) {
        this.getHandle().setSize(size, /* true */ getHandle().isAlive()); // Paper - fix dead slime setSize invincibility
    }

    @Override
    public boolean canWander() {
        return this.getHandle().canWander();
    }

    @Override
    public void setWander(boolean canWander) {
        this.getHandle().setWander(canWander);
    }
}
