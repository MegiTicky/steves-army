package com.stevesarmy.client;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.SetSelectedFireTeamPacket;
import com.stevesarmy.squad.FireTeam;

public class FireTeamScopeState {
    public static final FireTeamScopeState INSTANCE = new FireTeamScopeState();

    private FireTeam currentScope = FireTeam.ALL;
    private int teamCount = 2;

    private FireTeamScopeState() {}

    public FireTeam getCurrentScope() {
        return currentScope;
    }

    public void setCurrentScope(FireTeam scope) {
        setCurrentScope(scope, "unspecified");
    }

    public void setCurrentScope(FireTeam scope, String source) {
        if (scope == null || scope == currentScope) return;
        StevesArmyMod.LOGGER.info("[FireTeamScope] {} -> {} ({})", currentScope, scope, source);
        this.currentScope = scope;
        if (scope != FireTeam.ALL) {
            NetworkHandler.INSTANCE.sendToServer(new SetSelectedFireTeamPacket(scope));
        }
    }

    public int getTeamCount() {
        return teamCount;
    }

    public void setTeamCount(int count) {
        setTeamCount(count, "unspecified");
    }

    public void setTeamCount(int count, String source) {
        this.teamCount = Math.max(1, Math.min(4, count));
        if (currentScope != FireTeam.ALL && currentScope.ordinal() > teamCount) {
            setCurrentScope(FireTeam.ALL, source + ": selected team is no longer active");
        }
    }
}
