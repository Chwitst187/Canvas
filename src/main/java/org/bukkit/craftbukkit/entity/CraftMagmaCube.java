package org.bukkit.craftbukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.MagmaCube;

public class CraftMagmaCube extends CraftSlime implements MagmaCube {

    public CraftMagmaCube(CraftServer server, net.minecraft.world.entity.monster.MagmaCube entity) {
        super(server, entity);
    }

    @Override
    public net.minecraft.world.entity.monster.MagmaCube getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (net.minecraft.world.entity.monster.MagmaCube) this.entity;
    }
}
