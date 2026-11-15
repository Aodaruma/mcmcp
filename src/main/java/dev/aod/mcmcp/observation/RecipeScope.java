package dev.aod.mcmcp.observation;

/** Lookup policy only; never changes unlock state or craft authorization. */
public enum RecipeScope {
    UNLOCKED("解放済みのレシピのみ"),
    ALL_CRAFTABLE("すべての作成可能なレシピ");

    private final String label;

    RecipeScope(String label) { this.label = label; }

    public String label() { return label; }
}
