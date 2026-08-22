package com.stevesarmy.entity.ai;

import com.stevesarmy.entity.MachineGunnerEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Computes a rear support position for machine gunners: behind the squad's
 * rifleman line relative to the active engagement area. The position is only a
 * search anchor; the cover machinery snaps it to an actual cover block.
 */
public final class SupportPositionFinder {
    private static final double SUPPORT_OFFSET_BLOCKS = 10.0;
    private static final double MIN_AWAY_DISTANCE_SQ = 0.01;

    private SupportPositionFinder() {}

    @Nullable
    public static BlockPos findSupportPosition(MachineGunnerEntity mg) {
        Vec3 threatPos = engagementCenter(mg);
        if (threatPos == null) {
            return null;
        }

        Vec3 anchor = lineAnchor(mg);
        if (anchor == null) {
            return null;
        }

        Vec3 awayDir = anchor.subtract(threatPos);
        if (awayDir.lengthSqr() < MIN_AWAY_DISTANCE_SQ) {
            return null;
        }
        awayDir = awayDir.normalize();

        Vec3 target = anchor.add(awayDir.scale(SUPPORT_OFFSET_BLOCKS));
        return snapToGround(mg.level(), BlockPos.containing(target));
    }

    @Nullable
    private static Vec3 engagementCenter(MachineGunnerEntity mg) {
        BlockPos center = mg.getSuppressionCenter();
        return center != null ? center.getCenter() : null;
    }

    /** Average position of the squad's riflemen; falls back to the owner. */
    @Nullable
    private static Vec3 lineAnchor(MachineGunnerEntity mg) {
        Level level = mg.level();
        UUID squadId = mg.getSquadId();
        if (squadId != null && level instanceof ServerLevel serverLevel) {
            SquadData squad = SquadManager.get(serverLevel).getSquadById(squadId).orElse(null);
            if (squad != null) {
                double x = 0, y = 0, z = 0;
                int count = 0;
                for (UUID memberId : squad.getMemberIds()) {
                    if (memberId.equals(mg.getUUID())) {
                        continue;
                    }
                    if (serverLevel.getEntity(memberId) instanceof SoldierEntity soldier
                        && !(soldier instanceof MachineGunnerEntity)) {
                        x += soldier.getX();
                        y += soldier.getY();
                        z += soldier.getZ();
                        count++;
                    }
                }
                if (count > 0) {
                    return new Vec3(x / count, y / count, z / count);
                }
            }
        }

        LivingEntity owner = mg.getOwner();
        return owner != null ? owner.position() : null;
    }

    private static BlockPos snapToGround(Level level, BlockPos pos) {
        int surfaceY = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, pos).getY();
        return new BlockPos(pos.getX(), surfaceY, pos.getZ());
    }
}
