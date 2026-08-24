package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.*;

public class SquadData {
    private static final long NO_GRENADE_THROW = Long.MIN_VALUE;

    private UUID squadId;
    private UUID leaderId;
    private final List<UUID> memberIds = new ArrayList<>();
    private SquadMode mode = SquadMode.FOLLOW;
    private boolean cqbMode = false;
    private SquadFormation formation = SquadFormation.NONE;
    private SquadThreatIntel threatIntel = new SquadThreatIntel();
    private long lastGrenadeTick = NO_GRENADE_THROW;
    @Nullable
    private UUID grenadeReservationOwner;
    private long grenadeReservationUntilTick = NO_GRENADE_THROW;
    private transient SquadCoverPeekabilityCache coverPeekabilityCache = new SquadCoverPeekabilityCache();

    public SquadData(UUID leaderId) {
        this.squadId = UUID.randomUUID();
        this.leaderId = leaderId;
    }

    public UUID getSquadId() {
        return squadId;
    }

    public UUID getLeaderId() {
        return leaderId;
    }

    public void setLeaderId(UUID leaderId) {
        this.leaderId = leaderId;
    }

    public List<UUID> getMemberIds() {
        return Collections.unmodifiableList(memberIds);
    }

    public boolean addMember(UUID memberId) {
        if (!memberIds.contains(memberId)) {
            memberIds.add(memberId);
            return true;
        }
        return false;
    }

    public boolean removeMember(UUID memberId) {
        return memberIds.remove(memberId);
    }

    public int getMemberCount() {
        return memberIds.size();
    }

    public boolean isFull() {
        return false;
    }

    public SquadMode getMode() {
        return mode;
    }

    public void setMode(SquadMode mode) {
        this.mode = mode;
    }

    public boolean isCQB() {
        return cqbMode;
    }

    public void setCQB(boolean cqbMode) {
        this.cqbMode = cqbMode;
    }

    public SquadFormation getFormation() {
        return formation;
    }

    public void setFormation(SquadFormation formation) {
        this.formation = formation;
    }

    public record GrenadeReservation(UUID owner, long expiresAtTick, long remainingTicks) {}

    public record GrenadeReservationResult(boolean acquired, String reason,
                                           @Nullable UUID owner, long remainingTicks,
                                           long expiresAtTick) {}

    public record GrenadeThrowResult(boolean committed, boolean reservationReleased,
                                     String reason) {}

    public synchronized GrenadeReservationResult tryReserveGrenade(UUID soldierId,
                                                                     long gameTime,
                                                                     long leaseTicks) {
        clearExpiredGrenadeReservation(gameTime);

        long cooldownRemaining = getGrenadeCooldownRemaining(gameTime);
        if (cooldownRemaining > 0) {
            return new GrenadeReservationResult(false, "squad cooldown", null,
                cooldownRemaining, NO_GRENADE_THROW);
        }

        if (grenadeReservationOwner != null) {
            if (grenadeReservationOwner.equals(soldierId)) {
                return new GrenadeReservationResult(true, "reservation already owned",
                    soldierId, grenadeReservationUntilTick - gameTime,
                    grenadeReservationUntilTick);
            }
            return new GrenadeReservationResult(false, "another reservation is active",
                grenadeReservationOwner, grenadeReservationUntilTick - gameTime,
                grenadeReservationUntilTick);
        }

        long safeLease = Math.max(1L, leaseTicks);
        long expiresAt = gameTime > Long.MAX_VALUE - safeLease
            ? Long.MAX_VALUE : gameTime + safeLease;
        grenadeReservationOwner = soldierId;
        grenadeReservationUntilTick = expiresAt;
        return new GrenadeReservationResult(true, "reservation acquired", soldierId,
            expiresAt - gameTime, expiresAt);
    }

    public synchronized boolean isGrenadeReservationOwner(UUID soldierId, long gameTime) {
        clearExpiredGrenadeReservation(gameTime);
        return grenadeReservationOwner != null && grenadeReservationOwner.equals(soldierId);
    }

    public synchronized void releaseGrenadeReservation(UUID soldierId) {
        if (grenadeReservationOwner != null && grenadeReservationOwner.equals(soldierId)) {
            clearGrenadeReservation();
        }
    }

    public synchronized GrenadeThrowResult completeGrenadeThrow(UUID soldierId,
                                                                  long gameTime,
                                                                  boolean nativeThrowSucceeded) {
        boolean ownsReservation = grenadeReservationOwner != null
            && grenadeReservationOwner.equals(soldierId)
            && gameTime < grenadeReservationUntilTick;
        if (!ownsReservation) {
            return new GrenadeThrowResult(false, false,
                "grenade reservation is no longer owned");
        }

        clearGrenadeReservation();
        if (!nativeThrowSucceeded) {
            return new GrenadeThrowResult(false, true,
                "native grenade throw failed");
        }

        lastGrenadeTick = gameTime;
        return new GrenadeThrowResult(true, true, "grenade throw committed");
    }

    public synchronized long getGrenadeCooldownRemaining(long gameTime) {
        long interval = StevesArmyConfig.getGrenadeSquadIntervalTicks();
        if (interval <= 0 || lastGrenadeTick == NO_GRENADE_THROW) return 0L;

        long elapsed = gameTime - lastGrenadeTick;
        if (elapsed < 0 || elapsed >= interval) return 0L;
        return interval - elapsed;
    }

    public synchronized long getLastGrenadeTick() {
        return lastGrenadeTick;
    }

    @Nullable
    public synchronized GrenadeReservation getGrenadeReservation(long gameTime) {
        clearExpiredGrenadeReservation(gameTime);
        if (grenadeReservationOwner == null) return null;
        return new GrenadeReservation(grenadeReservationOwner, grenadeReservationUntilTick,
            grenadeReservationUntilTick - gameTime);
    }

    private void clearExpiredGrenadeReservation(long gameTime) {
        if (grenadeReservationOwner != null && gameTime >= grenadeReservationUntilTick) {
            clearGrenadeReservation();
        }
    }

    private void clearGrenadeReservation() {
        grenadeReservationOwner = null;
        grenadeReservationUntilTick = NO_GRENADE_THROW;
    }

    public SquadThreatIntel getThreatIntel() {
        return threatIntel;
    }

    /** Runtime-only geometry cache shared by squad cover searches. */
    public SquadCoverPeekabilityCache getCoverPeekabilityCache() {
        if (coverPeekabilityCache == null) {
            coverPeekabilityCache = new SquadCoverPeekabilityCache();
        }
        return coverPeekabilityCache;
    }

    public void tickIntelCleanup(long currentGameTime) {
        threatIntel.tickCleanup(currentGameTime);
    }

    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("SquadId", squadId);
        tag.putUUID("LeaderId", leaderId);
        tag.putString("Mode", mode.name());
        tag.putBoolean("CQB", cqbMode);
        tag.putString("Formation", formation.name());
        tag.putLong("LastGrenadeTick", lastGrenadeTick);
        
        tag.put("ThreatIntel", threatIntel.toNBT());
        
        ListTag membersList = new ListTag();
        for (UUID memberId : memberIds) {
            CompoundTag memberTag = new CompoundTag();
            memberTag.putUUID("Id", memberId);
            membersList.add(memberTag);
        }
        tag.put("Members", membersList);
        
        return tag;
    }

    public static SquadData fromNBT(CompoundTag tag) {
        SquadData data = new SquadData(tag.getUUID("LeaderId"));
        data.squadId = tag.getUUID("SquadId");
        data.mode = SquadMode.valueOf(tag.getString("Mode"));
        if (tag.contains("CQB")) {
            data.cqbMode = tag.getBoolean("CQB");
        }
        if (tag.contains("Formation")) {
            data.formation = SquadFormation.valueOf(tag.getString("Formation"));
        }
        if (tag.contains("LastGrenadeTick")) {
            data.lastGrenadeTick = tag.getLong("LastGrenadeTick");
        }
        
        if (tag.contains("ThreatIntel")) {
            data.threatIntel = SquadThreatIntel.fromNBT(tag.getCompound("ThreatIntel"));
        }
        
        ListTag membersList = tag.getList("Members", Tag.TAG_COMPOUND);
        for (int i = 0; i < membersList.size(); i++) {
            CompoundTag memberTag = membersList.getCompound(i);
            data.memberIds.add(memberTag.getUUID("Id"));
        }
        
        return data;
    }

    public static final int MAX_MEMBERS_LEGACY = 8;
}
