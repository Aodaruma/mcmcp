package dev.aod.mcmcp.runtime;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import java.util.*;

/** Inbound riding membership; local prediction is not mount/dismount confirmation. */
public final class VehiclePassengerSignals {
    private static final VehiclePassengerSignals GLOBAL = new VehiclePassengerSignals();
    public static VehiclePassengerSignals global() { return GLOBAL; }
    public record Evidence(long revision, Set<Integer> passengers) { }
    private ClientLevel level;
    private long revision;
    private final LinkedHashMap<UUID, Evidence> vehicles = new LinkedHashMap<>();
    public synchronized void record(ClientLevel world, int vehicleId, int[] passengerIds) {
        if (world == null) return;
        if (level != world) { level = world; vehicles.clear(); }
        var vehicle = world.getEntity(vehicleId);
        if (vehicle == null) return;
        var passengers = new HashSet<Integer>();
        for (int id : passengerIds) passengers.add(id);
        vehicles.put(vehicle.getUUID(), new Evidence(++revision, Set.copyOf(passengers)));
        while (vehicles.size() > 64) vehicles.remove(vehicles.keySet().iterator().next());
    }
    public synchronized Evidence snapshot(Entity vehicle) {
        return vehicle != null && vehicle.level() == level ? vehicles.get(vehicle.getUUID()) : null;
    }
    public synchronized void clear(ClientLevel world) {
        if (world == level) { level = null; vehicles.clear(); }
    }
}
