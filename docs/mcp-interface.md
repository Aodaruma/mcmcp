# MCPインターフェース

## 方針

MCPへ公開するのは、読み取りtool、制御tool、型付きroutineの開始だけです。生のキーhold、任意packet、自由文workflow、任意Java呼び出しは公開しません。

- 短時間の観測と比較は同期tool。広域Creative captureだけは非同期artifact export
- 能動操作と長時間処理は`start_routine`で開始し、`routine_id`で追跡
- 毎tickのfeedback loopはMOD内部
- `tools/list`はMCP tool一覧、`list_routines`はdomain routine kind一覧
- 全input/output schemaは`additionalProperties: false`、文字列長、件数、座標範囲、deadlineを制限
- 副作用のないread-only toolには`readOnlyHint: true`。Creative captureはworldを変更しませんがlocal fileを作るため`readOnlyHint: false / destructiveHint: false`
- 能動toolはmutating/destructiveとして扱い、MCP clientの自動承認を前提にしない

## 公開tool一覧

| tool | 種別 | 目的 |
|---|---|---|
| `get_status` | read | 接続、lock、互換性、安全policy、能動routine |
| `get_snapshot` | read | 必須scopeで同一tickの状態を観測 |
| `compare_block_plan` | read | 期待block planと観測・記憶の差分 |
| `list_routines` | read | routine kind、phase、schema、postcondition、experimental flag |
| `get_routine` | read | 状態、進捗、event差分、failure |
| `start_routine` | write | 型付きroutineを非同期開始 |
| `cancel_routine` | write | 指定routineを安全に取消 |
| `emergency_stop` | write | 全入力解放、pending開始破棄、lock |
| `get_recipes` | read | クライアント既知のrecipe displayを限定列挙 |
| `capture_creative_region` | artifact write | ローカルCreative領域を非同期captureし、gzip Blueprint artifactを生成 |

現在の固定surfaceは上記10 toolです。Phase 6までの既存9 toolの順序とinput/output shapeは変えず、Creative専用のworld-read-onlyな非同期artifact export toolを末尾へ追加しています。routine kindは13のままです。

スクリーンショットは未知MODの診断や見た目確認に有効ですが、structured observationとは性質が異なります。必要性を実測した段階で、明示的な読み取り専用`capture_view`として追加します。

## `get_status`

引数なし。次を返します。

- MCP/MOD/Minecraft/NeoForge/adapter version
- world接続、dimension、`world_session_id`
- local lock、unlock expiry、利用可能capability profile
- Voice Chat状態
- 有効なlocal survival/completion policyの非機密enum
- 現在の能動routine IDとstate
- memory件数、`retention_policy / evicted_count / oldest_retained_tick`、retention warning

server address、Bearer token、Microsoft認証情報、音声device名は返しません。

## `get_snapshot`

`scopes`は必須です。

```json
{
  "scopes": [
    "player",
    "inventory",
    "target",
    "visible_blocks",
    "visible_entities",
    "world"
  ],
  "options": {
    "visible_blocks": {
      "source": "live_and_memory",
      "query": {
        "kind": "positions",
        "positions": [
          {"x": 120, "y": 65, "z": -31}
        ]
      }
    },
    "visible_entities": {
      "source": "live_and_memory",
      "max_distance": 24,
      "types": ["minecraft:zombie"],
      "threat_relation": "currently_hostile_to_player"
    }
  }
}
```

### scopes

| scope | 内容 |
|---|---|
| `player` | health、hunger、effects、position、rotation、velocity、reach、selected slot |
| `inventory` | slot、item ID、count、durability、enchantment、tag、対応block、gameplay-relevant components |
| `target` | block/entity、hit face、hit position、distance、reach、replaceability、支持面 |
| `visible_blocks` | 現在観測またはmemory上のblock、全BlockState、light、shape、鮮度、出所 |
| `visible_entities` | 可視Entityまたはlast-known、type、位置、motion、通常同期状態、opaque ref |
| `world` | dimension、time、weather、biome、現在地点のlight、world session |
| `screen` | automation-owned/現在screenの種別と、許可されたmenu/slot状態。chat本文は含めない |

応答envelopeとlive sampleは同じclient tickで確定し、`world_session_id`、`client_tick`、`observation_revision`を返します。memory値は現在tickで再観測したように扱わず、record固有の観測tick、経過tick、出所を保持します。`observation_revision`はクライアント内の観測更新番号であり、server/world全体のrevisionや実行許可tokenではありません。

`visible_blocks`がblockを返す場合、registry IDと全BlockState propertyを常に含めます。流体はBlockStateとは別に、観測時のfluid ID、source判定、amountも含めます。`source`は`live / memory / live_and_memory`です。queryは初版で`viewport`と明示`positions`だけを許可し、denseな任意AABB全block取得は許可しません。`positions`は現在dimension固定で、要求した各座標に`current / last_known / not_currently_observable / unknown`のいずれかを返します。`not_currently_observable`はlive queryで今は見えないという結果、`unknown`はmemoryにも根拠がない状態で`reason = never_observed | evicted | unavailable`を持ちます。いずれも省略で表現しません。

`visible_entities`はplayer識別子を返しません。遮蔽後は現在座標を更新せず、`last_known`と観測tickを返します。任意UUIDではなく短寿命のopaque `entity_ref`を使います。`types`にはruntimeで存在するregistry ID/tagだけを受け、敵対性はtypeだけで断定せず、現在可視の挙動とversion/MOD対応classifierによる`threat_relation`として別に返します。

詳細な境界は[観測・記憶モデル](observation-model.md)に従います。

## `capture_creative_region`（Creative prototype）

```json
{
  "operation": "start",
  "region": {
    "dimension": "minecraft:overworld",
    "min": {"x": 192, "y": 199, "z": 192},
    "max": {"x": 207, "y": 202, "z": 207}
  },
  "include_entities": true,
  "idempotency_key": "f47ac10b-58cc-4372-a567-0e02b2c3d479"
}
```

開始応答の`job_id`は、同じtoolのstatus操作で追跡します。

```json
{
  "operation": "status",
  "job_id": "9e69f17b-c38e-48ce-9ac7-7d02a4cc5fe2"
}
```

通常の`get_snapshot`を緩めず、Creative設計取込みだけを独立したprofileにします。権限gateは次の4条件だけです。

- このclientが所有する非公開integrated single-player
- 対応するserver playerの実GameTypeがCreative
- cheats有効かつserver playerがGM permissionを持つ
- 現在のworld sessionでCreative capture capabilityがlocal arm済み

player距離、clientへの事前chunk load、512 cellは権限条件ではありません。処理量は各辺256、volume 4,194,304、最大64 chunk column、同時1 job、同時1 chunk、artifact展開後64 MiBまでに制限します。現在dimensionの生成済みchunkは順次一時loadできますが、未生成chunkは生成しません。

start/statusのMCP応答には全cellを含めません。成功statusはgzip bytesの`artifact.sha256`、論理Blueprintの`summary.blueprint_hash`、block/material summary、相対artifact path、`started_server_tick / completed_server_tick`を返します。gzip artifactは外側の`craftagent.creative-blueprint-artifact/v1`内に、airを含む全cellを表す`craftagent.blueprint-palette-rle/v1`のpalette＋RLEを保持します。`consistency=server_thread_chunk_sequence`であり、複数chunkを同時刻に凍結したatomic snapshotではありません。

`include_entities=true`の場合、region内のaliveな非player Entityをchunk処理時点で限定集計します。UUID、health、AI、owner、equipment、NBTは保存せず、領域全体のatomicまたはserver-complete一覧とは扱いません。BlockEntity、fluid、multi-cell、clone item未解決はmanual再現情報へ分類します。

このtoolはWorldMemoryへ書かず、world mutationや常設forceloadを行いません。直接`setBlock`、command、Creative item生成、任意NBT、summon/kill/teleportも公開しません。設計図SVGはlocalの`tools/export-blueprint-svg.ps1`でterminal statusが示すgzip artifactから生成します。

## `compare_block_plan`

`prepare_build`と`inspect_build`は分けません。同じ読み取り専用toolを建築前・途中・後に使います。

```json
{
  "anchor": {
    "dimension": "minecraft:overworld",
    "x": 120,
    "y": 64,
    "z": -32
  },
  "transform": {
    "rotation": 90,
    "mirror": "none"
  },
  "expected": [
    {
      "id": "foundation-001",
      "offset": {"x": 0, "y": 0, "z": 0},
      "state": {"block": "minecraft:stone_bricks"},
      "required": true
    },
    {
      "id": "bed-foot",
      "offset": {"x": 2, "y": 1, "z": 4},
      "state": {
        "block": "minecraft:red_bed",
        "properties": {"facing": "south", "part": "foot"}
      }
    },
    {
      "id": "spawn-clearance-001",
      "offset": {"x": 1, "y": 3, "z": 1},
      "state": {"block": "minecraft:air"}
    }
  ],
  "include_matches": false
}
```

観測結果は全BlockStateを含みますが、この読み取り専用toolでの期待状態比較は`properties`へ明示したpropertyだけを必須にします。これにより向きやdoor halfを検査しながら、動的なcrop ageやpowered状態を必要に応じて期待条件から外せます。`required`の既定値は`true`で、`false`のdiagnostic項目は完成判定集合へ含めません。`compare_block_plan`自体に厳密一致が必要になった場合だけ`exact_state`の追加を検討します。能動`apply_block_plan`は後述のとおり常に完全state一致です。

応答例:

```json
{
  "plan_hash": "sha256:...",
  "basis": {
    "world_session_id": "uuid",
    "dimension": "minecraft:overworld",
    "client_tick": 184220,
    "observation_revision": 9321
  },
  "coverage": {"requested": 3, "current": 1, "last_known": 1, "unknown": 1},
  "summary": {
    "match_current": 1,
    "mismatch_current": 0,
    "match_last_known": 0,
    "mismatch_last_known": 1,
    "unknown": 1
  },
  "differences": [
    {
      "id": "spawn-clearance-001",
      "result": "mismatch_last_known",
      "world_position": {"x": 121, "y": 67, "z": -31},
      "expected": {"block": "minecraft:air"},
      "actual": {
        "position": {
          "dimension": "minecraft:overworld",
          "x": 121,
          "y": 67,
          "z": -31
        },
        "state": {"block": "minecraft:cobblestone", "properties": {}},
        "observed_context": {"block_light_at_observation": 0},
        "knowledge": {
          "currentness": "last_known",
          "provenance": "line_of_sight_observation",
          "observed_at_client_tick": 183360,
          "age_ticks": 860
        },
        "world_session_id": "uuid"
      }
    },
    {
      "id": "bed-foot",
      "result": "unknown",
      "world_position": {"x": 122, "y": 65, "z": -28},
      "expected": {
        "block": "minecraft:red_bed",
        "properties": {"facing": "south", "part": "foot"}
      },
      "reason": "never_observed"
    }
  ]
}
```

1回の座標数は初版で512のschema上限とし、超過時は`invalid_argument`です。ページングで別tickを混ぜず、基礎・機構・外装などphaseへ分割します。`request_too_large`はschema検証後にruntimeで確定するresponse/cost上限超過へ使います。

この結果は実行許可tokenではありません。`apply_block_plan`は各操作直前にlive preconditionを取り直し、操作後にserver同期を確認します。

## `apply_block_plan`（Phase 4、実装・受入完了）

`start_routine`の`kind=apply_block_plan`は、外部で分割済みの1 phaseだけを現在の安全な立ち位置から施工します。

```json
{
  "kind": "apply_block_plan",
  "parameters": {
    "anchor": {
      "dimension": "minecraft:overworld",
      "x": 120,
      "y": 64,
      "z": -32
    },
    "transform": {"rotation": 90, "mirror": "x"},
    "phase": {"id": "foundation-1", "index": 1, "total": 3},
    "entries": [
      {
        "id": "clear-1",
        "offset": {"x": 0, "y": 0, "z": 0},
        "operation": "break_to_air",
        "expected_before": {"block": "minecraft:stone", "properties": {}},
        "expected_after": {"block": "minecraft:air", "properties": {}}
      },
      {
        "id": "stair-1",
        "offset": {"x": 1, "y": 0, "z": 0},
        "operation": "place",
        "expected_before": {"block": "minecraft:air", "properties": {}},
        "expected_after": {
          "block": "minecraft:oak_stairs",
          "properties": {
            "facing": "east",
            "half": "bottom",
            "shape": "straight",
            "waterlogged": "false"
          }
        },
        "item": "minecraft:oak_stairs"
      }
    ]
  },
  "bounds": {
    "dimension": "minecraft:overworld",
    "region": {
      "min": {"x": 118, "y": 63, "z": -34},
      "max": {"x": 122, "y": 66, "z": -30}
    },
    "max_travel_blocks": 0,
    "max_duration_seconds": 60,
    "allow_break": true
  },
  "completion_intent": "finish_goal",
  "idempotency_key": "7f7809c5-eae4-48a6-9fea-d50b600d5642"
}
```

固定契約:

- 1 callは1 phase、1〜64個の一意なtarget cellだけを扱い、移動を所有しません。phase間の移動と分割はMCP client側が行います
- operationは`verify_only / break_to_air / place / replace`の4種です。`replace`は確認済みのbreakとplaceという2つの内部childに分かれます
- `expected_before / expected_after`は、propertyのないblockでも`properties: {}`を含むruntime registry上の完全なBlockStateです。property省略や存在しないstateは開始前に拒否します
- offsetとBlockStateにはmirror（`none / x / z`）を先に、Y軸時計回りrotation（`0 / 90 / 180 / 270`）を後に適用します
- preflight、各操作直前、操作後、final verificationはcurrent-onlyです。last-known memoryでunknownや不一致を埋めません
- 設置資材は開始時の現在client inventoryとeligible hotbarをbaselineに不足を拒否します。各place後はcovering prediction ACK、完全なserver state、freshなinbound selected-slot inventory syncを確認してから次へ進みます
- 設置supportは`minecraft:cobblestone / dirt / grass_block / obsidian / smooth_stone / stone`の6 IDだけを許可し、canonicalなvanilla block、BlockEntityなし、空のFluidStateをcandidate選定時とpacket直前に再確認します
- 最後に全targetを同じclient tickでcurrentとして収集し、完全な`expected_after`一致かつunknown 0の場合だけ成功します
- `break_to_air`と`replace`には`allow_break=true`が必要です。破壊元は`minecraft:cobblestone / stone / dirt / obsidian / grass_block`の5 IDだけで、canonicalなvanilla block、BlockEntityなし、空のFluidStateを開始時とpacket直前に再確認します
- 成功確認は要求されたtarget cellが対象です。通常vanilla処理による隣接block更新やgame eventを抑止せず、target外のworld stateがすべて無変化であることは保証しません

## `get_recipes`（Phase 5、実装済み）

```json
{
  "query": {
    "kind": "result_item",
    "item": "minecraft:hopper"
  },
  "max_results": 16
}
```

queryは`result_item`または`result_tag`のclosed unionです。返すのは現在のクライアントが既知の`RecipeDisplayEntry`だけで、全`RecipeManager`、未解除recipe、server内部recipeを列挙しません。coverageは必ず`source = client_known_recipe_displays`、`complete = false`とし、`known / matched / returned / truncated`を返します。

各結果は24文字の短寿命opaque `recipe_ref`と`fingerprint`、`display_kind`、`required_screen`、support可否、解析できたresult/ingredient/shapeを返します。recipe IDは公開・入力せず、`craft_items`は同じworld sessionとrecipe-book revisionで`recipe_ref`とfingerprintを再解決します。unsupported/context-dependent displayは理由と解析できた範囲だけを返し、空のresult alternatives、ingredients、`shape = null`を許します。再帰recipe solver、最適素材調達planner、JEI固有APIは初版に含めません。

## `list_routines`

Phase 2の`stationary_break`、Phase 3の5 action、Phase 4の`apply_block_plan`、Phase 5の6 routineを合わせた13 kindと、各kind固有のclosed input schema、bounds、postcondition、experimental flagを返します。Phase 6でも公開kindは増やさず、catalog versionだけを`phase-6`へ更新します。

MCPのprotocol capabilityとは別のdomain catalogです。tool一覧には`tools/list`を使い、`get_capabilities`は作りません。

## `get_routine`

```json
{
  "routine_id": "874911aa-194c-4ba1-bc51-d5b279e2aa24",
  "after_event_seq": 41,
  "max_events": 32
}
```

```json
{
  "routine_id": "874911aa-194c-4ba1-bc51-d5b279e2aa24",
  "kind": "place_block",
  "state": "RUNNING",
  "phase": "wait_server_sync",
  "goal": {"verified": false},
  "progress": {"completed": 0, "total": 1, "unit": "blocks"},
  "current_step": {
    "kind": "place_block",
    "target": {
      "dimension": "minecraft:overworld",
      "x": 1,
      "y": 64,
      "z": 2
    },
    "expected_after": {
      "block": "minecraft:cobblestone",
      "properties": {}
    }
  },
  "checkpoint": {"seq": 0, "observation_revision": 9321},
  "verification": {"confirmed": 0, "expected": 1, "unknown": 1},
  "effects": [],
  "safety": {"mode": "normal", "last_check_client_tick": 123456},
  "wait": null,
  "finalization": {
    "required": true,
    "status": "pending",
    "phase": null,
    "failure": null
  },
  "events": [
    {
      "seq": 42,
      "type": "phase_started",
      "client_tick": 123456,
      "observation_revision": 9321,
      "details": {"phase": "wait_server_sync"}
    }
  ],
  "failure": null,
  "next_poll_after_ms": 1000,
  "events_truncated": false
}
```

event cursorがring bufferより古い場合は`events_truncated=true`としますが、現在state、progress、failureは常に完全に返します。`effects`は`{type, observed_before, observed_after, verification}`の配列で、睡眠によるrespawn point変更等の確認済み副作用を記録します。`WAITING`では`wait = {reason, deadline_client_tick, wake_condition}`、`FINALIZING`以降では`finalization`に必須policy、進捗phase、失敗点を返すため、過去eventが欠けても現在位置を解釈できます。

`apply_block_plan`の`progress.completed`は、一度skipまたはserver-confirmされた処理済みcellのmonotonic checkpoint数です。その後のworld divergenceで現在状態が無効になったかは、`verification.confirmed / unknown`、`goal.verified`、`failure`を見て判断します。

## `start_routine`

共通envelope:

```json
{
  "kind": "navigate_to",
  "parameters": {
    "target": {
      "dimension": "minecraft:overworld",
      "x": 120,
      "y": 64,
      "z": -32
    },
    "horizontal_tolerance_blocks": 0.5
  },
  "bounds": {
    "dimension": "minecraft:overworld",
    "region": {
      "min": {"x": 112, "y": 63, "z": -33},
      "max": {"x": 121, "y": 66, "z": -31}
    },
    "max_travel_blocks": 16,
    "max_duration_seconds": 30,
    "allow_break": false
  },
  "completion_intent": "finish_goal",
  "idempotency_key": "7f7809c5-eae4-48a6-9fea-d50b600d5641"
}
```

開始条件:

- local UIでcurrent world sessionがunlock済み
- requested kindがlocal capability profileで有効
- world、dimension、bounds、health、screen、Voice Chatが安全
- 同時に能動routineなし
- kind固有schemaとpreconditionに合格
- `max_duration_seconds + 5秒のFINALIZING reserve`がunlock expiry以前に収まる

`bounds.region`はwork regionです。Phase 3〜5 routineは指定dimension・region・travel・duration・break許可を実行中に広げません。`apply_block_plan`は`max_travel_blocks=0`固定です。Phase 5の全public座標はdimension付きで、runtimeがcurrent/bounds dimensionとの一致を再検証します。Phase 6 v1もsafe anchorや動的transit corridorを有限routineへ暗黙に追加しません。

有限routineのwork deadlineは`max_duration_seconds`から固定し、開始admissionではさらに5秒のFINALIZING reserveを加えた時間がunlock window内に収まることを要求します。この5秒は独立したtimerではなく、停止は既存routine deadlineとactive arming fenceに従います。複数routineにまたがるfood/sleep maintenanceは行いません。

全13 kindのschemaで`completion_intent`は省略可能な`finish_goal | continue_goal`です。省略時は`finish_goal`です。`continue_goal`成功はroutine-local cleanup、Voice Chat復元、安全なstay checkpoint後もlocal armingを維持し、`finish_goal`成功は`goal_finished`でlockします。outer loopはLLM/MCP clientが担います。

成功時はすぐに`routine_id`を返します。HTTP request timeout後に遅延開始しないよう、queue command自体にもdeadlineを持たせます。

Idempotency規則:

- 同じkeyと同じcanonical argumentsは同じ`routine_id`を返す
- 同じkeyと異なるargumentsは`idempotency_conflict`
- check-and-reserveはatomic
- terminal記録は件数/TTL上限付きで保持
- target-state型routineは再start時にも既達成部分をskip

## routine catalog

現在の公開契約は次の13 kindで、Phase 6まで受入を完了しています。routine自身のphase番号は追加時の2〜5を維持します。

| kind | phase | 概要 |
|---|---:|---|
| `stationary_break` | 2 | その場で再生成blockを期限・数量まで採掘 |
| `navigate_to` | 3 | 通常移動後、10 tick安定と補正不在で`server-reconciled`を確認 |
| `break_block` | 3 | 1 blockを通常採掘しprediction ACKとサーバーBlockStateを確認 |
| `place_block` | 3 | 単一cell blockを1回設置しprediction ACKとサーバーBlockStateを確認 |
| `interact_block` | 3 | allowlist blockをempty hand・non-sneakで1回use |
| `interact_entity` | 3 | current-visibleなadult cowをbucketで1回搾乳 |
| `apply_block_plan` | 4 | 移動なし・最大64 cellの1 phaseをcurrent exact-stateで施工 |
| `craft_items` | 5 | client-known recipe refを再解決し目標inventory countへcraft |
| `transfer_items` | 5 | 指定containerをroutine自身が開き目標countへ収束 |
| `tend_crop_area` | 5 | 宣言済み畑cellの成熟作物を収穫・植え直し |
| `harvest_tree_area` | 5 | 宣言済みcurrent tree cellだけを伐採・回収・再植林 |
| `sleep_at_bed` | 5 | 明示bedで睡眠し開始時checkpointへ帰還 |
| `survey_area` | 5 | 宣言済みwaypoint/sampleを通常移動・視点操作で調査 |

Phase 3 actionの境界は次のとおりです。

- `navigate_to`は回転を固定したforward/back/strafeによる短い平坦路だけを扱い、jump/sprint、段差越え、pathfinding、block破壊を行いません。loadedな足元・頭上空間、安定床、fluid/hazard不在、bounds/travel上限を毎tick確認します。positiveなserver ACKはないため、入力停止後、tolerance内、安定床上、低速、位置drift上限内を10 client tick連続で満たし、dispatch後のposition/rotation/motion correctionがない場合だけ`server-reconciled`とします
- `break_block`、`place_block`、`interact_block`は、実際のcrosshair/hit、通常reach、liveな`expected_before`を操作直前に再確認し、vanilla prediction ACKとサーバー由来の完全なBlockStateが要求した`expected_after`と一致して初めて成功します。Phase 3 v1の`break_block.expected_after`はプロパティなしの`minecraft:air`固定です
- `place_block`はmain handの単一cell `BlockItem`だけを扱い、bed/double-height itemを除外します。設置supportは上記と同じclosed 6-ID allowlistに限定し、candidate判定時とpacket直前に再確認します
- `interact_block`はlever、fence gate、vanillaのwooden trapdoorだけを許可します。empty main handかつnon-sneakを要求し、door、button、container、未知MOD blockは除外します。`expected_after`にはleverの`powered`またはgate/trapdoorの`open`だけを反転し、他の全propertyを維持した完全な同一block stateが必要です
- `interact_entity`はcurrent world session/dimensionで現在可視な短寿命opaque `entity_ref`が指すadult cow、main handの`minecraft:bucket`、目標`minecraft:milk_bucket`だけを許可します。crosshair、LOS、通常reach、boundsをdispatch直前に再確認し、1回だけ通常interactionを送ります。成功にはdispatch後のfreshなselected-slot inventory syncと絶対目標countが必要で、retryしません
- `apply_block_plan`の完全な契約は上記専用節に従います。Phase 2〜4の破壊操作は共通の5 ID safe-break allowlistを使います

Phase 5の6 kindはclosed schemaで実装済みです。特に次の境界を広げません。

- `craft_items`はopaque `recipe_ref`とfingerprintを再解決し、明示したvanilla crafting tableだけを使います。2x2 inventory craftingは初版ではserver full-readbackを強制できないため拒否します
- `transfer_items`は対象containerを通常interactionで自ら開き、userが開いたscreenをadoptしません。container clickにpositive ACKはないため、click後に閉じ、同じ対象を再度開き、container/player全slotのfull readbackで目標countを確認します
- `tend_crop_area`は最大64個の宣言済みplotとclosed vanilla crop adapterだけを扱います
- `harvest_tree_area`は入力に列挙され、操作直前にもcurrent exact-stateを確認できた最大64 log cellだけを扱います。隣接・hidden logを探索せず、成功も「宣言済みcell完了」であって木全体の完全伐採を意味しません
- `survey_area`のspawn surface評価はclient-visible block/lightからの`predicted`であり、hidden洞窟・壁裏を含む完全coverageを主張しません
- `sleep_at_bed`のrespawn point変更は、当該bed actionに対応する受信済みvanilla respawn設定signalがある場合だけconfirmed effectにします。睡眠開始、起床、位置変化だけから推定しません

6 branchは共通top-levelの`kind / parameters / bounds / idempotency_key`を必須、`completion_intent`を省略可能とし、全objectをclosedにします。parametersの独立positionはすべて`{dimension,x,y,z}`、boundsは既存互換の`dimension + region.min/max{x,y,z} + max_travel_blocks + max_duration_seconds + allow_break`です。

| kind | parametersの固定境界 | bounds上限 |
|---|---|---|
| `craft_items` | 24文字`recipe_ref`、sha256 fingerprint、default-components-onlyの絶対inventory goal、明示crafting table、最大64 craft | travel 32、120秒、break不可 |
| `transfer_items` | container exact state、方向、default-components-only item、destination絶対count、最大2304 transfer | travel 32、120秒、break不可 |
| `tend_crop_area` | 4種のvanilla crop adapter、1〜64宣言済みplot、replant/collect固定true、bounded wait policy | travel 128、600秒、break可 |
| `harvest_tree_area` | 1〜8 tree、宣言済みlog/support/sapling/clearance、collect固定true。全tree合計logはruntime admissionで最大64 | travel 128、600秒、break可 |
| `sleep_at_bed` | foot/headのpositionと完全state、return policyは`start_checkpoint`固定 | travel 128、600秒、break不可 |
| `survey_area` | 1〜32 waypoint、1〜256 sample、絶対observed goal、coverage-onlyまたはspawn prediction | travel 128、600秒、break不可 |

`operate_prepared_transfer`はPhase 7 experimentalで、公開catalogには含めません。Phase 6は既存13 kindへ共通の`continue_goal`と完了処理を追加し、toolやroutine kindは増やしていません。

`operate_prepared_transfer`を有効化する場合も、targetのdestination region内でのserver-confirmed安定、destination閉鎖、source/route開口閉鎖、routine所有のtemporary water/rail power停止、playerのhazard cell外退避、全input/item-use解放をkind固有postconditionにします。passenger入りvehicleをcleanup目的で攻撃破壊せず、target survival/despawnが確認不能なら`unknown`を保持します。

汎用`transport_entity`と自動捕獲はcatalogへ出しません。釣り竿pullは後期に成功率を測る場合もlocal test harness内部だけとし、MCP catalogへ公開しません。

## `cancel_routine`

```json
{"routine_id": "874911aa-194c-4ba1-bc51-d5b279e2aa24", "reason": "user requested"}
```

冪等です。既にterminalなら現在結果を返します。成功応答は、client threadで当該routineの入力/item-use/screen ownershipを解放し、pending stepを無効化したことを意味します。

`reason`は長さとcontrol characterを検証して監査log injectionを防ぎます。

## `emergency_stop`

```json
{"reason": "operator stop"}
```

引数でlockを弱められません。常に次を行います。

1. client tickの最優先stop flagを立てる
2. pending startを破棄する
3. 全input、item-use、screen ownershipを解放する
4. current routineを`CANCELLED`へする
5. current world sessionをlocal lockする

`locked / busy / incompatible`状態でも受理します。unlockはlocal UI/keyだけで行います。

## Invocation error

| code | 意味 |
|---|---|
| `locked` | local UIで能動操作が許可されていない |
| `unsafe_state` | health、screen、target、Voice Chat等の開始条件不成立 |
| `busy` | 別routine実行中またはqueue上限 |
| `incompatible` | version/adapter検査失敗 |
| `invalid_argument` | schema、range、allowlist違反 |
| `request_too_large` | schema検証後に判明したresponse、runtime costの固定上限超過 |
| `idempotency_conflict` | 同じkeyへ異なる引数 |
| `timeout` | client thread処理前のdeadline、または短時間tool timeout |

開始済みroutineの失敗はMCP invocation errorにせず、`get_routine.failure`へ返します。内部stack trace、local path、tokenは返さず、短いevent IDだけを監査参照に使います。
