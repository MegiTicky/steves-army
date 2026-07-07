package com.stevesarmy.squad;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.*;

public class SquadData {
    private UUID squadId;
    private UUID leaderId;
    private final List<UUID> memberIds = new ArrayList<>();
    private SquadMode mode = SquadMode.FOLLOW;
    private SquadFormation formation = SquadFormation.NONE;
    private boolean cqbMode = false;

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

    public SquadFormation getFormation() {
        return formation;
    }

    public void setFormation(SquadFormation formation) {
        this.formation = formation;
    }

    public boolean isCQB() {
        return cqbMode;
    }

    public void setCQB(boolean cqbMode) {
        this.cqbMode = cqbMode;
    }

    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("SquadId", squadId);
        tag.putUUID("LeaderId", leaderId);
        tag.putString("Mode", mode.name());
        tag.putString("Formation", formation.name());
        tag.putBoolean("CQB", cqbMode);
        
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
        if (tag.contains("Formation")) {
            data.formation = SquadFormation.valueOf(tag.getString("Formation"));
        } else {
            data.formation = SquadFormation.NONE;
        }
        if (tag.contains("CQB")) {
            data.cqbMode = tag.getBoolean("CQB");
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