package com.stevesarmy.client;

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
        this.currentScope = scope;
    }

    public int getTeamCount() {
        return teamCount;
    }

    public void setTeamCount(int count) {
        this.teamCount = Math.max(1, Math.min(4, count));
        if (currentScope != FireTeam.ALL && currentScope.ordinal() > teamCount) {
            currentScope = FireTeam.ALL;
        }
    }
}