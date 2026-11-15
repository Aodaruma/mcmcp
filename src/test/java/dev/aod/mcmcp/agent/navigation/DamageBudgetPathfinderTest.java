package dev.aod.mcmcp.agent.navigation;

import dev.aod.mcmcp.agent.safety.Locomotion;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class DamageBudgetPathfinderTest {
    private final UUID session = UUID.randomUUID();
    private NavCell cell(int x, int y, int z) { return new NavCell("minecraft:overworld", x, y, z); }
    private TraversabilityEdge edge(NavCell from, NavCell to, Locomotion mode) {
        return new TraversabilityEdge(session, new TraversabilityEdge.Key(from, to), TraversabilityEdge.Status.CONFIRMED,
                TraversabilityEdge.TargetSupport.CONFIRMED, TraversabilityEdge.Clearance.CONFIRMED,
                TraversabilityEdge.Transition.CONFIRMED, TraversabilityEdge.Fluid.NONE, TraversabilityEdge.Hazard.NONE,
                TraversabilityEdge.Provenance.LOCAL_VOLUME, from, 1, 0, mode);
    }
    @Test void cumulativeCostsRejectIndividuallyAffordableDrops() {
        var a = cell(0, 64, 0); var b = cell(1, 60, 0); var c = cell(2, 56, 0);
        var first = edge(a,b,Locomotion.DROP); var second = edge(b,c,Locomotion.DROP);
        var base = new KnownTraversabilitySnapshot(session,a.dimension(),0,Map.of());
        var snapshot = base.withGroundTransfers(List.of(first,second),Map.of(first.key(),1.0,second.key(),1.0),1);
        assertThat(new DeterministicAStar().findRoute(snapshot,a,c).found()).isFalse();
        assertThat(new DeterministicAStar().findRoute(base.withGroundTransfers(List.of(first,second),
                Map.of(first.key(),1.0,second.key(),1.0),2),a,c).found()).isTrue();
        assertThat(base.edges()).isEmpty();
    }
    @Test void keepsLongerLowDamageArrivalAtTheSameCell() {
        var start=cell(0,64,0); var junction=cell(1,60,0); var goal=cell(2,56,0);
        var b=cell(0,64,1); var c=cell(1,60,1);
        var shortDrop=edge(start,junction,Locomotion.DROP); var finalDrop=edge(junction,goal,Locomotion.DROP);
        var lowDrop=edge(b,c,Locomotion.DROP); var walk1=edge(start,b,Locomotion.GROUND); var walk2=edge(c,junction,Locomotion.GROUND);
        var map=new KnownTraversabilitySnapshot(session,start.dimension(),0,Map.of(walk1.key(),walk1,walk2.key(),walk2))
                .withGroundTransfers(List.of(shortDrop,finalDrop,lowDrop),Map.of(shortDrop.key(),1.0,finalDrop.key(),1.0,lowDrop.key(),0.0),1);
        assertThat(new DeterministicAStar().findRoute(map,start,goal).route().orElseThrow().cells())
                .containsExactly(start,b,c,junction,goal);
        assertThat(new DeterministicAStar().findRoute(map,start,goal,()->false,()->{}).failure())
                .contains(DeterministicAStar.FailureReason.SEARCH_CANCELLED);
    }
}
