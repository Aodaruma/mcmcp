# MCMCP Phase 1–5 test fixture

This source set is a destructive development fixture, not part of the production mod. It is
enabled only by `-Dmcmcp.testHarness=true` (the Gradle `harnessClient` run supplies this).
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

- `/mcmcp_fixture load` — clears/rebuilds the fixed arena, teleports the player, and resets
  inventory/status.
- `/mcmcp_fixture status` — prints public BlockState/light/player/inventory checkpoints.
- `/mcmcp_fixture random_ticks status` — prints the current fixed random-tick fixture state.
- `/mcmcp_fixture random_ticks accelerate` — saves the world's current value once and sets the
  fixed harness-only `random_tick_speed` value to 30.
- `/mcmcp_fixture random_ticks restore` — restores the saved value. Normal server shutdown also
  restores it before the world is saved; no command accepts an arbitrary value.
- `/mcmcp_fixture expose_hidden` — opens the opaque box so the gold/diamond cell is visible.
- `/mcmcp_fixture conceal_hidden` — seals the box; observations should return remembered data.
- `/mcmcp_fixture mutate_hidden` — seals the box and changes gold to diamond behind the wall.
  The observer must not learn this until a later `expose_hidden` plus observation.
- `/mcmcp_fixture oracle` — explicit manual-test-only ground truth for the hidden cell.
- `/mcmcp_fixture reset_player` — restores the deterministic survival status and inventory.
- `/mcmcp_fixture phase2 regen` — places an overhead stone target and restores it after
  8 server ticks; use a cobblestone goal of 34 to force two confirmed breaks.
- `/mcmcp_fixture phase2 no_regen` — leaves the first confirmed break as air for the
  `TARGET_NOT_REGENERATED` gate.
- `/mcmcp_fixture phase2 slow` — uses obsidian with the fixed iron pickaxe for timeout and
  cancellation/input-release tests.
- `/mcmcp_fixture phase2 status|off` — prints server break/regeneration counters or stops
  the scenario. The tick handler reauthorizes the private integrated-server boundary every tick.
- `/mcmcp_fixture phase3 navigate` — clears a flat short lane and places the player at its
  deterministic start, facing the fixed destination.
- `/mcmcp_fixture phase3 break|place|lever` — prepares one stone break, cobblestone placement,
  or unpowered floor-lever target with matching inventory selection and player pose.
- `/mcmcp_fixture phase3 cow` — spawns one fixture-tagged NoAI persistent cow, selects a
  bucket, and places the player within normal visible interaction range.
- `/mcmcp_fixture phase3 reset` — removes fixture cows, clears the Phase 3 lane, and restores
  the original deterministic player state.
- `/mcmcp_fixture phase4 all_satisfied` — prepares three exact verify-only cells already in
  their requested stone/air/cobblestone states; no child action should be dispatched.
- `/mcmcp_fixture phase4 mutations` — prepares one stone→air break, one air→cobblestone
  placement, and one dirt→cobblestone replace in a single local three-cell phase.
- `/mcmcp_fixture phase4 waterlogged` — prepares a level-0 source-water target and a smooth
  stone slab whose exact result is `type=bottom,waterlogged=true`.
- `/mcmcp_fixture phase4 directional_stairs|hopper` — prepares a single supported placement
  with exact east/bottom/straight/non-waterlogged stair state or down-facing enabled hopper state.
- `/mcmcp_fixture phase4 shortage` — exposes two cobblestone placement cells but supplies
  exactly one cobblestone, so resource preflight must fail before preparation.
- `/mcmcp_fixture phase4 divergence` — prepares a diamond-pickaxe obsidian break plus a verified
  dirt guard cell. One-shot autorun changes the guard to gold on the integrated-server thread after
  three consecutive owned-break ticks; the next global reconcile must stop on the change. The
  bounded manual `phase4 introduce_divergence` command remains available for interactive diagnosis.
- `/mcmcp_fixture phase4 hidden` — seals a gold verify-only cell in a fully opaque box so
  required-current preflight fails closed. `phase4 reveal_hidden|conceal_hidden` opens or reseals
  its fixed west aperture without accepting arbitrary coordinates.
- `/mcmcp_fixture phase4 build_runner` — prepares two air-backed, two-block cobblestone
  columns with separate work poses and eight cobblestone selected in slot 1. After the first column
  becomes server-authoritative cobblestone, the fixture places one tagged NoAI cow on the second
  route for exactly 20 server ticks, then removes it. This exercises neutral occupant wait and
  recovery; another player uses the same production occupant check but is not covered by this
  private-singleplayer fixture.
- `/mcmcp_fixture phase5 brew` — prepares an empty brewing stand plus three water bottles, one
  nether wart, and one blaze powder for a production `brew_known_recipe` smoke test.
- `/mcmcp_fixture phase5 smelt` — prepares an empty furnace plus one raw iron and one coal for a
  production smelting smoke test.
- `/mcmcp_fixture phase5 redstone` — prepares supported air cells and supplies one redstone lamp
  and one lever for the bounded identity truth-table smoke test.
- `/mcmcp_fixture phase5 generalization` — rebuilds one interference-free arena containing a
  four-rung vanilla ladder and a separate four-block exact vanilla scaffolding column, each between
  floor-backed lower/upper landings; supported air cells for two-lamp fan-out; two removable glass
  blocks for clear-then-apply; and a vanilla double chest (`generic_9x6`) with deterministic
  contents in both halves. A separate floor-level
  three-cell glass-supported line supplies the lamp, redstone dust, and lever wire-identity layout.
  The player starts on the lower ladder landing with three lamps, two levers, one dust, two glass,
  and one smooth-stone block reserved for the one-step pillar smoke test.
- `/mcmcp_fixture phase5 combined_wheat` — prepares the production-prompt wheat E2E. The player
  starts in Survival with a completely empty inventory, facing a visible and normally reachable
  single chest containing one damage-37 vanilla iron hoe and 64 wheat seeds. A closed oak fence
  gate is the only entrance to a fenced, hazard-free nine-plot farm. Every plot starts as dirt with
  air above it, so the hoe use, planting, maturation, harvesting, collection, and replanting paths
  are all required gameplay rather than fixture mutations.
- `/mcmcp_fixture phase5 combined_wheat_status` — prints the bounded test oracle: wheat count,
  farmland/replanted counts, remaining chest supplies, gate state, completion, and random-tick
  lease state. It also reports the wall-clock lease time remaining and the current/saved/fixed
  omnidirectional rays-per-tick values.
- `/mcmcp_fixture phase5 combined_wheat_rollback` — ends only the active combined scenario and
  restores the saved `random_tick_speed` and observation rate. `/mcmcp_fixture load`, `phase5
  reset`, replacement by any Phase 2–5 scenario, a failed private-singleplayer reauthorization,
  lease expiry, and normal server shutdown also restore both.

The combined mode saves the world's current `random_tick_speed` and effective observation rate,
then changes them to the fixed harness values 3000 and 512 rays per active client tick only after all
layout, inventory, chest, and pose setup has succeeded. The observation override is process-local
and is installed through a class packaged only in the fixture JAR. Its production-side bridge also
requires both `-Dmcmcp.testHarness=true` and the actually loaded `mcmcp_test_fixture` mod, so JVM
properties cannot activate it in the production JAR alone; it does not rewrite
`mcmcp-client.toml`. The random-tick lease
is owner-bound, so the standalone `random_ticks restore` command cannot partially disable a running
combined scenario. It restores both saved effective values automatically when the player has at
least 64 wheat and all nine plots are farmland with wheat replanted.

Each combined run has an absolute, non-renewable 20-minute lease measured with monotonic elapsed
time. This covers the fresh evaluator's 17-minute turn deadline plus setup/preflight margin while
remaining bounded, and operating-system clock corrections cannot extend it. Once the deadline has
passed, the next integrated-server pre-tick callback rolls the scenario back before that world tick
can run with
accelerated settings; server stopping/stopped hooks are the fallback when a world is closed instead.
This is deliberately elapsed-time based rather than a game-tick budget, so pausing or lag cannot
renew the lease (restoration occurs at the next safe server lifecycle callback). The fixture never
grows, tills, plants, harvests, moves drops, or edits inventory for the player.

For the persistent Prism profile `MCMCP-Validation` and save `tester (1)`, either run the manual
command above after `/mcmcp_fixture load`, or temporarily add the JVM argument
`-Dmcmcp.fixture.phase5.mode=combined_wheat` to reuse the existing one-shot Phase 5 autorun. Remove
the argument after the run. Autorun rebuilds the bounded arena and prepares the same state but does
not arm MCMCP; local authorization remains one explicit user UI action before the production
prompt begins.

For the combined generalization smoke arena, run:

```powershell
.\gradlew.bat runHarnessClient -PmcmcpFixturePhase5Mode=generalization
```

This passes only the closed `mcmcp.fixture.phase5.mode` value. After server-authoritative setup and
client slot synchronization, autorun stops in setup-only state. Open any Screen and press the MCMCP
status button once before the live test; that explicit UI action is the local authorization.

The autorun-only `creative_capture` mode keeps the existing gallery layout, changes the owner to
Creative, and places them more than 32 blocks from the fixed 1,024-cell capture region
`192,199,192` through `207,202,207`.

For a repeatable one-shot Phase 3 or Phase 4 live test, keep a disposable singleplayer world named
`New World` and run, for example:

```powershell
.\gradlew.bat runHarnessClient -PmcmcpFixturePhase3Mode=navigate
.\gradlew.bat runHarnessClient -PmcmcpFixturePhase3Mode=mutations
.\gradlew.bat runHarnessClient -PmcmcpFixturePhase3Mode=build_runner
.\gradlew.bat runHarnessClient -PmcmcpFixturePhase3Mode=creative_capture
```

Accepted modes are `navigate`, `break`, `place`, `lever`, `cow`, `reset`, `all_satisfied`,
`mutations`, `waterlogged`, `directional_stairs`, `hopper`, `shortage`, `divergence`, `hidden`, and
`build_runner`, plus the dedicated `creative_capture` mode.
Only when this Gradle
property is present, `runHarnessClient` adds Quick Play for `New World` and passes
`mcmcp.fixture.phase3.mode`. After the private integrated-server boundary is checked on its server
thread, the autorun rebuilds the entire fixed arena, prepares that mode, and stops in setup-only
state. Open any Screen and press the MCMCP status button once before the live test. The autorun
temporarily disables pause-on-lost-focus so an external MCP driver can run; the original option is
restored when the client stops. With no mode property, all autorun listeners and option changes
remain disabled.

The legacy Gradle property and system-property names still contain `Phase3`; Phase 4 modes reuse the
same bounded autorun transport and do not widen its security checks. The Phase 4 fixture supports an
implementation that is currently under final acceptance; fixture availability does not mean the
development-live or production-Prism gates have completed.

The gallery includes age 0/7 wheat, hydrated farmland, an east-facing upper-half stair,
both halves of an open hinged door, a powered lit lamp plus a block-light sample, a stable source
water source in a one-cell glass basin at `194,200,200`, a bottom smooth-stone slab at
`196,200,200`, a directly visible emerald block, a lapis target behind glass, and a fully opaque
remembered/unknown-state scenario. `/mcmcp_fixture status` prints both new samples and their
complete state (`fluid_source=true, fluid_amount=8`; `type=bottom, waterlogged=false`).

`./gradlew runGameTestServer` also runs `phase1_block_states`, which verifies the crop, stair, door,
lamp, water, slab, and block-light assumptions against the actual dedicated GameTest server APIs.
For water it checks the exact BlockState plus source/amount FluidState; for the slab it checks every
named property and exact BlockState equality. It never calls the absolute-coordinate interactive
arena and does not expose its mutation commands.

The `passage_shapes_fixture` GameTest separately verifies a wooden door and wooden trapdoor change
from a blocking VoxelShape to a passable open shape. It also verifies that a powered wooden
pressure plate has no collision, emits a signal, opens both halves of an adjacent wooden door, and
that releasing it closes both halves again. These passage variants are intentionally not all placed
on the combined E2E route.

The additional `phase4_block_plan_fixture` GameTest verifies every mode's exact full before-state,
unique ids/targets, arena bounds, mutation operation ordering, shortage resource count, opaque box,
level-0 source water, waterlogged slab, directional stairs, and hopper state. Each representative
after-state is installed on the GameTest server and compared with full `BlockState.equals`, not a
property subset.

Phase 4 destructive targets use only members of the production safe-break allowlist: stone and dirt
in `mutations`, and obsidian in `divergence`. Live acceptance also verifies target-cell outcomes;
ordinary vanilla neighbor updates and game events outside those declared targets are not asserted to
remain unchanged.
