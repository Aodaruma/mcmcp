package dev.aod.mcmcp.fixture;

/** Pure completion boundary for the combined wheat production-prompt fixture. */
record FixtureCombinedWheatProgress(
        int wheatCount,
        int farmlandCount,
        int plantedCropCount,
        int plotCount) {
    FixtureCombinedWheatProgress {
        if (wheatCount < 0 || farmlandCount < 0 || plantedCropCount < 0 || plotCount < 1
                || farmlandCount > plotCount || plantedCropCount > plotCount) {
            throw new IllegalArgumentException("invalid combined wheat progress");
        }
    }

    boolean complete() {
        return wheatCount >= FixturePhase5Scenario.COMBINED_WHEAT_GOAL
                && farmlandCount == plotCount
                && plantedCropCount == plotCount;
    }
}
