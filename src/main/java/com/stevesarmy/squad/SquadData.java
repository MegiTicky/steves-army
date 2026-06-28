package com.stevesarmy.squad;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;

import java.util.*;

public class SquadData {
    private UUID squadId;
    private UUID leaderId;
    private final List<UUID> memberIds = new ArrayList<>();
    private SquadMode mode = SquadMode.FOLLOW;

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
        if (memberIds.size() < MAX_MEMBERS && !memberIds.contains(memberId)) {
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
        return memberIds.size() >= MAX_MEMBERS;
    }

    public SquadMode getMode() {
        return mode;
    }

    public void setMode(SquadMode mode) {
        this.mode = mode;
    }

    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("SquadId", squadId);
        tag.putUUID("LeaderId", leaderId);
        tag.putString("Mode", mode.name());
        
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
        
        ListTag membersList = tag.getList("Members", Tag.TAG_COMPOUND);
        for (int i = 0; i < membersList.size(); i++) {
            CompoundTag memberTag = membersList.getCompound(i);
            data.memberIds.add(memberTag.getUUID("Id"));
        }
        
        return data;
    }

    public static final int MAX_MEMBERS = 8;
}