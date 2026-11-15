package dev.aod.mcmcp.agent.navigation;

/** One move's cumulative budget. Healing never refunds committed or observed damage. */
public final class FallDamageBudget {
    private static final double EPSILON = 1.0E-5;
    private final double limit;
    private double completed, predicted, observed, totalObserved;
    private boolean pending;

    public FallDamageBudget(double limit) {
        if (!Double.isFinite(limit) || limit < 0) throw new IllegalArgumentException("invalid damage limit");
        this.limit = limit;
    }
    public double spent() { return completed + Math.max(predicted, observed); }
    public double remaining() { return Math.max(0, limit - spent()); }
    public double observed() { return totalObserved; }
    public boolean begin(double estimate) {
        if (pending || !Double.isFinite(estimate) || estimate < 0 || estimate > remaining() + EPSILON) return false;
        predicted = estimate; observed = 0; pending = true;
        return true;
    }
    public boolean record(double loss, boolean confirmedFall) {
        if (!Double.isFinite(loss) || loss < 0) return false;
        if (loss == 0) return true;
        totalObserved += loss;
        observed += loss;
        return pending && confirmedFall && spent() <= limit + EPSILON;
    }
    public void finish() {
        completed = spent(); predicted = 0; observed = 0; pending = false;
    }
}
