# CraftAgent Phase 1 test fixture

This source set is a destructive development fixture, not part of the production mod. It is
enabled only by `-Dcraftagent.testHarness=true` (the Gradle `harnessClient` run supplies this).
Its common entrypoint only registers a server-side GameTest; the command entrypoint is physically
client-only.

Hard safety boundary:

- physical client and its current integrated server only;
- the singleplayer owner must be the only connected player;
- worlds opened to LAN, dedicated servers, remote multiplayer, non-Overworld dimensions, and
  off-server-thread calls are rejected;
- block writes are limited to the absolute inclusive box `192,199,192` through `207,207,207`;
- commands accept no coordinates, block identifiers, item identifiers, or counts;
- all implementation is under the `harness` source set. Production `main` cannot import it.

Run `./gradlew runHarnessClient`, create or open a disposable singleplayer world, then use:

- `/craftagent_fixture load` — clears/rebuilds the fixed arena, teleports the player, and resets
  inventory/status.
- `/craftagent_fixture status` — prints public BlockState/light/player/inventory checkpoints.
- `/craftagent_fixture expose_hidden` — opens the opaque box so the gold/diamond cell is visible.
- `/craftagent_fixture conceal_hidden` — seals the box; observations should return remembered data.
- `/craftagent_fixture mutate_hidden` — seals the box and changes gold to diamond behind the wall.
  The observer must not learn this until a later `expose_hidden` plus observation.
- `/craftagent_fixture oracle` — explicit manual-test-only ground truth for the hidden cell.
- `/craftagent_fixture reset_player` — restores the deterministic survival status and inventory.

The gallery includes age 0/7 wheat, hydrated farmland, an east-facing upper-half stair,
both halves of an open hinged door, a powered lit lamp plus a block-light sample, a stable source
water source in a one-cell glass basin at `194,200,200`, a bottom smooth-stone slab at
`196,200,200`, a directly visible emerald block, a lapis target behind glass, and a fully opaque
remembered/unknown-state scenario. `/craftagent_fixture status` prints both new samples and their
complete state (`fluid_source=true, fluid_amount=8`; `type=bottom, waterlogged=false`).

`./gradlew runGameTestServer` also runs `phase1_block_states`, which verifies the crop, stair, door,
lamp, water, slab, and block-light assumptions against the actual dedicated GameTest server APIs.
For water it checks the exact BlockState plus source/amount FluidState; for the slab it checks every
named property and exact BlockState equality. It never calls the absolute-coordinate interactive
arena and does not expose its mutation commands.
