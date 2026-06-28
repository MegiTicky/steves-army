package com.stevesarmy.squad;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

import java.util.*;

public class SquadManager extends SavedData {
    private final Map<UUID, SquadData> squadsByLeader = new HashMap<>();
    private final Map<UUID, SquadData> squadsByMember = new HashMap<>();
    private final Map<UUID, SquadData> squadsById = new HashMap<>();

    public SquadData createSquad(UUID leaderId) {
        if (squadsByLeader.containsKey(leaderId)) {
            return squadsByLeader.get(leaderId);
        }
        
        SquadData squad = new SquadData(leaderId);
        squadsByLeader.put(leaderId, squad);
        squadsById.put(squad.getSquadId(), squad);
        this.setDirty();
        return squad;
    }

    public Optional<SquadData> getSquadByLeader(UUID leaderId) {
        return Optional.ofNullable(squadsByLeader.get(leaderId));
    }

    public Optional<SquadData> getSquadByMember(UUID memberId) {
        return Optional.ofNullable(squadsByMember.get(memberId));
    }

    public Optional<SquadData> getSquadById(UUID squadId) {
        return Optional.ofNullable(squadsById.get(squadId));
    }

    public boolean addMemberToSquad(UUID squadId, UUID memberId) {
        SquadData squad = squadsById.get(squadId);
        if (squad != null && squad.addMember(memberId)) {
            squadsByMember.put(memberId, squad);
            this.setDirty();
            return true;
        }
        return false;
    }

    public boolean removeMemberFromSquad(UUID memberId) {
        SquadData squad = squadsByMember.remove(memberId);
        if (squad != null) {
            squad.removeMember(memberId);
            this.setDirty();
            return true;
        }
        return false;
    }

    public void disbandSquad(UUID leaderId) {
        SquadData squad = squadsByLeader.remove(leaderId);
        if (squad != null) {
            for (UUID memberId : squad.getMemberIds()) {
                squadsByMember.remove(memberId);
            }
            squadsById.remove(squad.getSquadId());
            this.setDirty();
        }
    }

    public static SquadManager get(ServerLevel level) {
        DimensionDataStorage storage = level.getDataStorage();
        return storage.computeIfAbsent(
            SquadManager::load,
            SquadManager::new,
            "steves_army_squads"
        );
    }

    public static SquadManager load(CompoundTag tag) {
        SquadManager manager = new SquadManager();
        ListTag squadsList = tag.getList("Squads", Tag.TAG_COMPOUND);
        
        for (int i = 0; i < squadsList.size(); i++) {
            CompoundTag squadTag = squadsList.getCompound(i);
            SquadData squad = SquadData.fromNBT(squadTag);
            manager.squadsById.put(squad.getSquadId(), squad);
            manager.squadsByLeader.put(squad.getLeaderId(), squad);
            for (UUID memberId : squad.getMemberIds()) {
                manager.squadsByMember.put(memberId, squad);
            }
        }
        
        return manager;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag squadsList = new ListTag();
        for (SquadData squad : squadsById.values()) {
            squadsList.add(squad.toNBT());
        }
        tag.put("Squads", squadsList);
        return tag;
    }
}