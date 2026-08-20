# CraftAgent Phase 1–4 test fixture

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
- `/craftagent_fixture phase2 regen` — places an overhead stone target and restores it after
  8 server ticks; use a cobblestone goal of 34 to force two confirmed breaks.
- `/craftagent_fixture phase2 no_regen` — leaves the first confirmed break as air for the
  `TARGET_NOT_REGENERATED` gate.
- `/craftagent_fixture phase2 slow` — uses obsidian with the fixed iron pickaxe for timeout and
  cancellation/input-release tests.
- `/craftagent_fixture phase2 status|off` — prints server break/regeneration counters or stops
  the scenario. The tick handler reauthorizes the private integrated-server boundary every tick.
- `/craftagent_fixture phase3 navigate` — clears a flat short lane and places the player at its
  deterministic start, facing the fixed destination.
- `/craftagent_fixture phase3 break|place|lever` — prepares one stone break, cobblestone placement,
  or unpowered floor-lever target with matching inventory selection and player pose.
- `/craftagent_fixture phase3 cow` — spawns one fixture-tagged NoAI persistent cow, selects a
  bucket, and places the player within normal visible interaction range.
- `/craftagent_fixture phase3 reset` — removes fixture cows, clears the Phase 3 lane, and restores
  the original deterministic player state.
- `/craftagent_fixture phase4 all_satisfied` — prepares three exact verify-only cells already in
  their requested stone/air/cobblestone states; no child action should be dispatched.
- `/craftagent_fixture phase4 mutations` — prepares one stone→air break, one air→cobblestone
  placement, and one dirt→cobblestone replace in a single local three-cell phase.
- `/craftagent_fixture phase4 waterlogged` — prepares a level-0 source-water target and a smooth
  stone slab whose exact result is `type=bottom,waterlogged=true`.
- `/craftagent_fixture phase4 directional_stairs|hopper` — prepares a single supported placement
  with exact east/bottom/straight/non-waterlogged stair state or down-facing enabled hopper state.
- `/craftagent_fixture phase4 shortage` — exposes two cobblestone placement cells but supplies
  exactly one cobblestone, so resource preflight must fail before preparation.
- `/craftagent_fixture phase4 divergence` — prepares a diamond-pickaxe obsidian break plus a verified
  dirt guard cell. One-shot autorun changes the guard to gold on the integrated-server thread after
  three consecutive owned-break ticks; the next global reconcile must stop on the change. The
  bounded manual `phase4 introduce_divergence` command remains available for interactive diagnosis.
- `/craftagent_fixture phase4 hidden` — seals a gold verify-only cell in a fully opaque box so
  required-current preflight fails closed. `phase4 reveal_hidden|conceal_hidden` opens or reseals
  its fixed west aperture without accepting arbitrary coordinates.

For a repeatable one-shot Phase 3 or Phase 4 live test, keep a disposable singleplayer world named
`New World` and run, for example:

```powershell
.\gradlew.bat runHarnessClient -PcraftagentFixturePhase3Mode=navigate
.\gradlew.bat runHarnessClient -PcraftagentFixturePhase3Mode=mutations
```

Accepted modes are `navigate`, `break`, `place`, `lever`, `cow`, `reset`, `all_satisfied`,
`mutations`, `waterlogged`, `directional_stairs`, `hopper`, `shortage`, `divergence`, and `hidden`.
Only when this Gradle
property is present, `runHarnessClient` adds Quick Play for `New World` and passes
`craftagent.fixture.phase3.mode`; modes other than `reset` also pass
`craftagent.fixture.phase3.autoArm=true`. After the private integrated-server boundary is checked on
its server thread, the autorun rebuilds the entire fixed arena, prepares that mode, waits 20 client
ticks, then clicks the registered `key.craftagent.toggle_lock` mapping exactly once. It never arms
after a setup/security failure, and `reset` never auto-arms. The autorun temporarily disables
pause-on-lost-focus so an external MCP driver can run; the original option is restored when the
client stops. With no mode property, all autorun listeners and option changes remain disabled.

The legacy Gradle property and system-property names still contain `Phase3`; Phase 4 modes reuse the
same bounded autorun transport and do not widen its security checks. The Phase 4 fixture supports an
implementation that is currently under final acceptance; fixture availability does not mean the
development-live or production-Prism gates have completed.

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

The additional `phase4_block_plan_fixture` GameTest verifies every mode's exact full before-state,
unique ids/targets, arena bounds, mutation operation ordering, shortage resource count, opaque box,
level-0 source water, waterlogged slab, directional stairs, and hopper state. Each representative
after-state is installed on the GameTest server and compared with full `BlockState.equals`, not a
property subset.

Phase 4 destructive targets use only members of the production safe-break allowlist: stone and dirt
in `mutations`, and obsidian in `divergence`. Live acceptance also verifies target-cell outcomes;
ordinary vanilla neighbor updates and game events outside those declared targets are not asserted to
remain unchanged.
