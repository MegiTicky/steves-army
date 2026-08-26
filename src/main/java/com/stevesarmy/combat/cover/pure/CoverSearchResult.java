package com.stevesarmy.combat.cover.pure;

import net.minecraft.core.BlockPos;

import java.util.Comparator;
import java.util.List;

/** Deterministically ranked result from the pure evaluator. */
public record CoverSearchResult(List<RankedCandidate> rankedCandidates) {
    private static final Comparator<RankedCandidate> ORDER = Comparator
        .comparingDouble(RankedCandidate::score).reversed()
        .thenComparingLong(candidate -> candidate.candidate().position().asLong());

    public CoverSearchResult {
        rankedCandidates = rankedCandidates == null ? List.of() : rankedCandidates.stream()
            .sorted(ORDER)
            .toList();
    }

    public RankedCandidate top() {
        return rankedCandidates.isEmpty() ? null : rankedCandidates.get(0);
    }

    public List<BlockPos> positions() {
        return rankedCandidates.stream().map(candidate -> candidate.candidate().position()).toList();
    }

    public record RankedCandidate(CoverSearchInput.CoverCandidateSnapshot candidate, float score) {}
}
