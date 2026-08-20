# MCPインターフェース

## 方針

MCPへ公開するのは、読み取りtool、制御tool、型付きroutineの開始だけです。生のキーhold、任意packet、自由文workflow、任意Java呼び出しは公開しません。

- 短時間の観測と比較は同期tool
- 能動操作と長時間処理は`start_routine`で開始し、`routine_id`で追跡
- 毎tickのfeedback loopはMOD内部
- `tools/list`はMCP tool一覧、`list_routines`はdomain routine kind一覧
- 全input/output schemaは`additionalProperties: false`、文字列長、件数、座標範囲、deadlineを制限
- read-only toolには`readOnlyHint: true`
- 能動toolはmutating/destructiveとして扱い、MCP clientの自動承認を前提にしない

## 公開tool一覧

| tool | 種別 | 目的 |
|---|---|---|
| `get_status` | read | 接続、lock、互換性、安全policy、能動routine |
| `get_snapshot` | read | 必須scopeで同一tickの状態を観測 |
| `compare_block_plan` | read | 期待block planと観測・記憶の差分 |
| `get_recipes` | read | 現在のruntimeで有効なrecipe取得 |
| `list_routines` | read | routine kind、schema、availability、limit |
| `get_routine` | read | 状態、進捗、event差分、failure |
| `start_routine` | write | 型付きroutineを非同期開始 |
| `cancel_routine` | write | 指定routineを安全に取消 |
| `emergency_stop` | write | 全入力解放、pending開始破棄、lock |

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

観測結果は全BlockStateを含みますが、期待状態の比較は`properties`へ明示したpropertyだけを必須にします。これにより向きやdoor halfを検査しながら、動的なcrop ageやpowered状態を必要に応じて期待条件から外せます。`required`の既定値は`true`で、`false`のdiagnostic項目は完成判定集合へ含めません。厳密一致が実需になった場合だけ`exact_state`を追加します。

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

## `get_recipes`

```json
{
  "item": "minecraft:hopper",
  "max_results": 16
}
```

現在接続中のruntime recipe managerが持つ有効recipeを返します。item/tag、recipe ID、ingredients、result、必要screenを含めます。再帰recipe solver、最適素材調達planner、JEI固有APIは初版に含めません。LLMがplanを作り、`craft_items`が選択済みrecipeを実行します。

## `list_routines`

現在のversion、local policy、実装phaseで利用可能なroutine kindと、kind固有input schema、bounds、postcondition、experimental flagを返します。

MCPのprotocol capabilityとは別のdomain catalogです。tool一覧には`tools/list`を使い、`get_capabilities`は作りません。

## `get_routine`

```json
{
  "routine_id": "uuid",
  "after_event_seq": 41,
  "max_events": 32
}
```

```json
{
  "routine_id": "uuid",
  "state": "RUNNING",
  "phase": "build.place",
  "goal": {"verified": false},
  "progress": {"completed": 38, "total": 120, "unit": "blocks"},
  "current_step": {
    "kind": "place_block",
    "target": {"x": 1, "y": 64, "z": 2}
  },
  "checkpoint": {"seq": 17, "observation_revision": 9321},
  "verification": {"confirmed": 38, "expected": 120, "unknown": 0},
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
      "type": "step_verified",
      "client_tick": 123456,
      "observation_revision": 9321
    }
  ],
  "failure": null,
  "next_poll_after_ms": 1000,
  "events_truncated": false
}
```

event cursorがring bufferより古い場合は`events_truncated=true`としますが、現在state、progress、failureは常に完全に返します。`effects`は`{type, observed_before, observed_after, verification}`の配列で、睡眠によるrespawn point変更等の確認済み副作用を記録します。`WAITING`では`wait = {reason, deadline_client_tick, wake_condition}`、`FINALIZING`以降では`finalization`に必須policy、進捗phase、失敗点を返すため、過去eventが欠けても現在位置を解釈できます。

## `start_routine`

共通envelope:

```json
{
  "kind": "apply_block_plan",
  "parameters": {},
  "bounds": {
    "dimension": "minecraft:overworld",
    "region": {
      "min": {"x": 100, "y": 60, "z": -50},
      "max": {"x": 150, "y": 90, "z": 0}
    },
    "max_travel_blocks": 64,
    "max_duration_seconds": 300,
    "allow_break": false
  },
  "completion_intent": "finish_goal",
  "idempotency_key": "client-generated-uuid"
}
```

開始条件:

- local UIでcurrent world sessionがunlock済み
- requested kindがlocal capability profileで有効
- world、dimension、bounds、health、screen、Voice Chatが安全
- 同時に能動routineなし
- kind固有schemaとpreconditionに合格
- routine hard deadlineがunlock expiry以前で、maintenance、`ask` timeout、fallback、FINALIZING reserveを収められる

`bounds.region`はwork regionです。VALIDATING時にこれと、local UIで事前承認されたbed/safe anchorおよび各transit corridorを同じdimensionの固定`execution_envelope`へまとめます。anchor利用policyなのに経路を含めて固定できない場合は開始しません。実行中にenvelopeを広げることはできません。

`max_duration_seconds`の内側にmaintenance/FINALIZING用の内部reserveを確保します。work soft deadlineでは新しい作業stepを始めずFINALIZINGへ移り、reserveを含むhard deadlineでは即座に入力を解放します。

`completion_intent`は`continue_goal | finish_goal`です。省略時は`finish_goal`とします。`continue_goal`はroutine自身のscreen/temporary stateを片付け、安定safe checkpointで全inputを解放しますが、帰宅、`ask`、切断は実行しません。local UIでone-shot orchestrationを許可したworld sessionだけ受理し、unlock expiry、連続回数、総時間の上限を越えて完了処理を延期できません。`finish_goal`だけがlocal `after_completion` policyを実行します。

成功時はすぐに`routine_id`を返します。HTTP request timeout後に遅延開始しないよう、queue command自体にもdeadlineを持たせます。

Idempotency規則:

- 同じkeyと同じcanonical argumentsは同じ`routine_id`を返す
- 同じkeyと異なるargumentsは`idempotency_conflict`
- check-and-reserveはatomic
- terminal記録は件数/TTL上限付きで保持
- target-state型routineは再start時にも既達成部分をskip

## routine catalog

| kind | phase | 概要 |
|---|---:|---|
| `stationary_break` | 2 | その場で再生成blockを期限・数量まで採掘 |
| `navigate_to` | 3 | 通常移動で観測済み座標/target regionへ到達 |
| `break_block` | 3 | 1 blockを通常採掘しpostcondition確認 |
| `place_block` | 3 | 1 blockを期待stateへ設置し確認 |
| `interact_block` | 3 | 通常右click相当の有限interaction |
| `interact_entity` | 3 | 可視・LOS・reach内Entityへの有限右click |
| `apply_block_plan` | 4 | phase分割された期待block stateへ施工 |
| `craft_items` | 5 | 選択recipeで目標inventory countへcraft |
| `transfer_items` | 5 | 指定containerをroutine自身が開き、目標countへ収束 |
| `tend_crop_area` | 5 | 指定畑の成熟作物を収穫・植え直し |
| `harvest_tree_area` | 5 | 指定植林区画を伐採・回収・再植林 |
| `sleep_at_bed` | 5 | 登録・確認済みbedへ移動して睡眠・帰還 |
| `survey_area` | 5 | 通常移動と視点操作で可視表面を調査・記憶 |
| `operate_prepared_transfer` | experimental | ユーザーが収容・封鎖済みEntity搬送系を操作 |

`transfer_items`は対象block/refを受け、routine自身が通常interactionでscreenを開きます。ユーザーが事前に開いたscreenをadoptしません。

`operate_prepared_transfer`を有効化する場合も、targetのdestination region内でのserver-confirmed安定、destination閉鎖、source/route開口閉鎖、routine所有のtemporary water/rail power停止、playerのhazard cell外退避、全input/item-use解放をkind固有postconditionにします。passenger入りvehicleをcleanup目的で攻撃破壊せず、target survival/despawnが確認不能なら`unknown`を保持します。

汎用`transport_entity`と自動捕獲はcatalogへ出しません。釣り竿pullは後期に成功率を測る場合もlocal test harness内部だけとし、MCP catalogへ公開しません。

## `cancel_routine`

```json
{"routine_id": "uuid", "reason": "user requested"}
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
