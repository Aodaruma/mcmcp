# 自動化runtimeと回復

## 役割分担

LLMの応答時間をMinecraftの20 TPS制御へ持ち込みません。

```text
LLM outer loop
  目標分解 -> 観測 -> routine選択 -> 結果確認 -> 再計画
                              |
                              v
Client MOD inner loop (20 TPS)
  PRECHECK -> EXECUTE -> WAIT_SERVER_SYNC -> VERIFY
```

LLMは高水準の方針を選び、MODは宣言済みの範囲、期限、対象に限って決定論的に実行します。MCPクライアントがpollしていない間も、開始済みroutineは期限内で進められますが、新しい高水準作戦を勝手に選びません。

## one-shot contract（Phase 6、実装済み）

one-shotは、1回のユーザー依頼からLLMが複数のMCP呼び出しを組み合わせて完遂を目指すUXです。自由文goal、任意条件式、if/loopを含むworkflow DSLをMODへ渡す意味ではありません。

全13 routineは省略可能な`completion_intent`として`finish_goal | continue_goal`を受理し、省略時は`finish_goal`です。中間routineの`continue_goal`はroutine-local cleanup、Voice Chat復元、安全なstay checkpointまで完了してlocal armingを維持し、最後の`finish_goal`がユーザーgoalを閉じます。outer loopはLLM/MCP clientが担い、MODへ自由文goalやworkflow DSLは追加しません。

継続は1回のlocal armかつ同じworld session内で`continue_goal`最大16回、unlockから最大15分です。失敗、cancel、emergency stop、world session変更ではchainを破棄してlockします。

成功条件:

- domain goalの必須postconditionがすべて`confirmed`
- unknownを必須条件へ残さない
- 固定`stay` policyの安全checkpointを満たす
- 全入力とautomation-owned screenを解放する

完遂できない場合は、未完了を成功扱いせず、構造化された理由と再計画材料を返します。

## 実行envelopeと期限

routineはVALIDATING時に、MCPで指定されたwork region、dimension、travel、duration、break許可を固定`execution_envelope`にします。Phase 6 v1はsafe anchorや動的transit corridorを暗黙に加えず、実行中に権限を広げません。

開始admissionでは`max_duration_seconds`に5秒のFINALIZING reserveを加えた時間がunlock expiry以前に収まることを要求します。この5秒は独立したfinalization timerではありません。停止は既存routine deadlineとactive arming fenceに従い、緊急停止、切断等では移動を試みず即座に入力を解放します。

## finite actionの実行契約

block/entityの有限actionは次の順で動きます。

```text
PRECHECK
  -> already satisfiedなら成功
  -> EXECUTE
  -> WAIT_SERVER_SYNC
  -> VERIFY
  -> SUCCEEDED
```

`navigate_to`は`PRECHECK -> EXECUTE -> MOVE -> SETTLE -> VERIFY`で動き、destination toleranceへ入った時点で移動入力を解放します。Phase 3 v1は回転を固定したforward/back/strafeによる短い平坦路だけを扱い、jump/sprint、段差越え、pathfinding、block破壊は行いません。loadedな足元・頭上空間、安定床、fluid/hazard不在、bounds/travel上限を毎tick確認します。positiveなserver ACKはないため、入力停止後10 client tick連続でtolerance内、安定床上、低速、位置drift上限内を満たし、dispatch後にposition/rotation/motion correctionがない場合だけ`server-reconciled`とします。

失敗時はfresh observationを取り、同じpostconditionへ収束することが安全だとaction実装が明示した場合だけbounded retryを行います。資材方針、blueprint、Entity同定、許可regionを勝手に変更しません。Phase 3の`interact_entity`は結果の重複を安全に否定できないためretryしません。

postconditionはaction実装へ固定し、LLMが任意predicateを注入する機能は作りません。

例:

- `place_block`: prediction ACK後のサーバー由来の完全なBlockStateが、実際の設置予測stateおよび要求した`expected_after`と一致した
- `break_block`: prediction ACK後のサーバー由来の完全なBlockStateが`expected_after`（Phase 3 v1ではair）と一致した
- `navigate_to`: 入力解放後の10 client tick連続安定と、position/rotation/motion correction不在により`server-reconciled`となった
- `interact_block`: allowlist対象への1回の通常useについて、prediction ACK後のサーバー由来の完全なBlockStateが`expected_after`と一致した
- `craft_items`: サーバー同期後のinventoryが目標countを満たす
- `transfer_items`: sourceとdestination双方の同期後countが目標を満たす
- `interact_entity`: dispatch後のfreshなselected-slot inventory syncと、絶対目標count以上の`minecraft:milk_bucket`を確認した
- `apply_block_plan`: 対象phaseの必須expected座標がすべてcurrentかつ一致し、必須集合のunknownが0

単にpacketやclickを送れたことは成功にしません。`server-confirmed`という表現はpositive ACKとサーバーstateを持つblock actionに使い、navigationは上記の根拠を伴う`server-reconciled`と区別します。

Phase 4の`apply_block_plan`（実装・受入完了）は、外部で分割した1 phaseを1 callで実行する、移動なしの局所routineです。1 phaseは1〜64 cellで、各cellはruntime registryに存在する完全な`expected_before`と`expected_after`、および`verify_only / break_to_air / place / replace`のいずれかを持ちます。offsetとBlockStateはmirrorを適用してからY軸時計回りに0/90/180/270度回転します。

preflightでは全cellをcurrent-onlyで同じframeに収集し、unknown、before mismatch、資材不足、eligible hotbarにないitem、準備不能なtargetがあれば最初のdispatch前に失敗します。設置資材の開始baselineは現在のclient inventoryです。各place/replace-placeの直前にも残量・eligible hotbar・current stateを再確認し、実行後はcovering prediction ACK、targetの完全なserver state、およびfreshなinbound selected-slot inventory syncを待ちます。最終判定は全target cellを同じclient tickにcurrentとして再収集し、完全state一致かつunknown 0である場合だけ成功します。last-knownや別tickの寄せ集めは完成根拠にしません。

このpostconditionは要求されたtarget cellだけを確認します。vanillaが通常発生させる隣接block更新やgame eventは抑止せず、target外がすべて無変化だったという保証は返しません。計画側は影響を受け得るcellを必要に応じて同じphaseの`verify_only`へ含めます。

## routine状態

公開状態は次に限定します。

| state | 意味 |
|---|---|
| `QUEUED` | 受理済みで、クライアントスレッド実行待ち |
| `VALIDATING` | world、bounds、対象、local policyを確認中 |
| `RUNNING` | 入力または画面操作を所有して実行中 |
| `WAITING` | 入力を解放し、期限付きで成長・時刻・同期等を待機中 |
| `FINALIZING` | 結果検証、後片付け、安全化を実行中 |
| `SUCCEEDED` | domain goalと必須finalizationを確認済み |
| `FAILED` | 安全に停止した未完了、またはfinalization未完了 |
| `CANCELLED` | ユーザー、緊急停止、明示取消により終了 |

細かな状態は`phase`で表し、公開enumを増やしません。`FAILED`でも`goal.verified=true`となる場合があります。これは建築自体は完成したが、安全場所へ戻れない等、必須finalizationが未完了であることを意味します。

`WAITING`中もSafety controllerとdeadlineは動作し続けます。各checkpointは、入力を解放できる安定床上で、危険な中間GUIや未確定actionを残していない境界です。失敗、cancel、emergency stop、死亡、切断等では帰還を試みず、routine-local cleanupと即時releaseを優先してchainを破棄・lockします。

## 失敗分類

MCP tool invocation errorと、開始済みroutineのoutcome failureを分けます。

Routine failureの共通形:

```json
{
  "category": "divergence",
  "code": "POSTCONDITION_MISMATCH",
  "retryable": false,
  "recovery": "replan",
  "scope": "step",
  "attempts": 3,
  "expected": {},
  "observed": {},
  "evidence": {},
  "suggested_snapshot_scopes": ["visible_blocks", "inventory"],
  "requires_user": false
}
```

| category | 例 | 基本動作 |
|---|---|---|
| `transient` | server lag、cooldown、一時的なpath変化 | 再観測して局所retry |
| `precondition` | 資材不足、inventory満杯、bed未登録 | LLMが前提を整えて再start |
| `divergence` | block不一致、Entity消失、world変更 | 入力解放、snapshot/diff後に再計画 |
| `safety` | 低体力、落下危険、敵対Mob、危険なdimension | local safety policyで退避または停止 |
| `external` | 切断、保護領域、server拒否、互換性異常 | 安全停止し、必要ならユーザーへhandoff |

`recovery`は`retry / replan / user / none`から選びます。

高水準の再計画が必要な場合は`needs_replan` eventを発行し、fresh snapshot/diffの推奨scopeとfailureを保持して`FAILED`へ終端します。LLMは待ち続けず、再観測後に新しいroutineを開始します。

## progressとevent

`get_routine`は生tick telemetryを返さず、意味のあるeventだけをring bufferへ残します。

- `phase_started`
- `step_verified`
- `retrying`
- `checkpoint`
- `maintenance_started` / `maintenance_finished`
- `needs_replan`
- `finalization_started`
- `goal_verified`
- `succeeded` / `failed` / `cancelled`

各eventは単調増加`seq`、`client_tick`、`observation_revision`を持ちます。`after_event_seq`による差分pollを基準とし、MCP notificationはクライアント互換性を確認できた後の補助に留めます。

## checkpointとreconcile

checkpointはpostconditionをサーバー同期後に確認した境界でのみ進めます。保持する最小情報は次です。

- `plan_hash`
- `anchor`とtransform
- `phase`
- `checkpoint_seq`
- `observation_revision`

再開時はworldを真実源としてsnapshotと`compare_block_plan`を再取得し、既に目標状態の箇所はskipします。transaction rollback、全完了座標DB、汎用job databaseは作りません。

`craft_items`と`transfer_items`も「追加でN個」ではなく「destinationに少なくともN個」等の目標状態で実行し、blind retryによる重複を防ぎます。

初版のcheckpointはメモリ内で十分です。プロセス再起動越しの継続が必要になった時点で、world別の小さなjournalを検討します。

checkpointを作る前に、playerが安定床上にあり、全inputを解放でき、未確定のscreen clickやblock/entity actionがないことを確認します。

## 建築

建築planは巨大な命令DSLではなく、anchorからの相対座標とBlockStateで表します。読み取り専用`compare_block_plan`は明示したpropertyによる部分比較を許しますが、能動`apply_block_plan`は省略のない完全なBlockStateだけを受けます。

1. `get_snapshot`で現場、inventory、既知memoryを確認
2. `compare_block_plan`でphaseの差分とunknownを取得
3. LLMが現在の資材とreachから、移動なし・最大64 cellのphaseへ外部分割
4. `apply_block_plan`が通常操作で1 phaseを施工
5. 各blockのserver-confirmed postcondition後にcheckpoint
6. phase終了時に同じtickのcurrent exact-state集合を確認
7. 内部機構確認後に外装phaseを閉じる

recipe列挙、採取、craft、transferによる資材準備はPhase 5として実装済みです。読み取り専用`get_recipes`はクライアント既知の`RecipeDisplayEntry`だけをopaque ref化して返し、`coverage.source = client_known_recipe_displays`、`complete = false`を固定します。全`RecipeManager`、未解除recipe、recipe IDは公開しません。Phase 4の`apply_block_plan`は引き続き、開始時点でeligible hotbarにある資材だけを使います。

施設固有の`build_iron_golem_farm`は作りません。LLMが設計を選び、汎用block plan、資材、Entity handoff、稼働観測を組み合わせます。

## 農林業

`tend_crop_area`と`harvest_tree_area`は、入力で座標と完全stateを宣言した指定regionだけで動くPhase 5 routineとして実装済みです。

- crop ID/tagと全BlockStateを確認する
- 成熟条件をversion/runtime dataに基づいて判断する
- 収穫、drop回収、植え直し後に状態を確認する
- sapling、宣言済みlog、支持面、成長空間をcurrent exact-stateで確認する
- 成長待ちは`WAITING`で入力を解放し、deadlineを持つ
- 未観測領域、自然地形全体、隣接・隠れた原木を直接検索しない。tree成功は宣言済みcurrent log cellの完了だけを意味し、木全体の完全伐採を保証しない

## クラフトとコンテナ

GUI全面禁止では、craftとtransferを実現できません。画面操作は次に限定します。

- routine自身が開いた、allowlist済みscreen/menuだけ
- routineは指定container block/refへ通常interactionし、自分で開いたscreenだけをadoptする
- screen identity、menu/sync ID、slot revisionを毎操作前後に確認
- 予期しないscreen遷移、手動input、slot desyncで即停止
- inventoryを直接書き換えず、通常のclick pathとserver同期を使う
- container内容は通常画面を開いて確認できた間だけcurrentとして扱う
- container clickにはpositive ACKがないため、click送信だけで成功にしない。automation-owned screenを閉じ、同じ宣言済みcontainerを通常interactionで再度開き、container/player全slotのfull readback後にsource/destinationの絶対目標countを確認する

## 睡眠とsurvival maintenance

standaloneの`sleep_at_bed`だけを公開します。Phase 6 v1でも長時間routineへ食事・睡眠を自動挿入せず、必要ならLLM/MCP clientが明示的な`sleep_at_bed`を中間routineとして実行します。

ローカルpolicy例:

```text
sleep = disabled | prefer | require
bed_anchor = dimension付きの登録座標
auto_eat = disabled | allowlisted_food
```

睡眠は次の順で行います。

```text
CHECKPOINT
  -> NAVIGATE_BED
  -> USE_BED
  -> VERIFY_SLEEP_AND_WAKE
  -> RETURN
  -> RECONCILE
  -> RUNNING
```

ベッドはユーザー登録済み、または現在のtaskで明示的に観測・設置確認したものに限ります。bedが危険なdimensionではfail closedにします。占有、時間外、近くのmonster、破壊、server timeoutは構造化failureとして返します。`prefer`では睡眠不能のeventを残し、現在のsurvival条件が許せば作業を続行します。`require`では睡眠を確認できなければ`FAILED`としてuser/LLMへ返します。

睡眠によるrespawn point変更はvanilla副作用としてlocal UIへ明示しますが、routine resultで`confirmed` effectにするのは当該bed actionに対応する受信済みvanilla respawn設定signalがある場合だけです。睡眠開始、起床、位置変化だけからrespawn変更を推定しません。standaloneの`sleep_at_bed`は開始時safe checkpointへ戻り、必ずworld差分を再取得してから終了します。

Phase 2〜5で追加した全13 public routineが、Phase 6では省略可能な`completion_intent = finish_goal | continue_goal`を共通に受理します。複数routineのouter loopはLLM/MCP clientが担い、survival maintenanceを暗黙には挿入しません。

## Entity interactionとuser handoff

Phase 3 v1の`interact_entity`は搾乳1種類だけです。current world session/dimensionで現在可視な短寿命opaque `entity_ref`が指すadult cowを、crosshair、LOS、通常reach、declared bounds内で再解決し、main handのbucketで通常interactionを1回だけ送ります。成功はdispatch後に届いたfreshなselected-slot inventory syncと、absolute goal count以上の`minecraft:milk_bucket`で確認します。retry、取引、餌やり、毛刈り、騎乗、移動、捕獲、押し込み、攻撃、投射物、釣り竿は含めません。

Phase 3 v1の`interact_block`も汎用右clickではありません。empty main handかつnon-sneakで、現在crosshair・通常reach内にあるlever、fence gate、vanillaのwooden trapdoorだけを許可します。door、button、container、未知MOD blockは除外します。`expected_after`は現在の完全なBlockStateからleverの`powered`またはgate/trapdoorの`open`だけを反転した完全stateと一致しなければpacket送信前に拒否し、そのstateをprediction ACKとサーバー由来の完全なBlockStateで検証します。

万能な`transport_entity`は初版で公開しません。村人や敵対MobはAI、衝突、遅延、地形により成功率が低いためです。

後期experimentalでは、ユーザーが対象をボート、トロッコ、封鎖水路等へ収容し、routeとdestination cageを完成させた後に限り、`operate_prepared_transfer`を検討します。自動捕獲と餌・POI・aggro誘導はさらに別の明示opt-in試験とし、既定OFFにします。釣り竿pullを評価する場合もlocal test harness内部だけに限定し、MCP catalogへ公開しません。

開始条件が不足する場合は新しいpause/resume機構を増やさず、次を返して終了します。

```json
{
  "code": "NEEDS_USER_HANDOFF",
  "requires_user": true,
  "staging_region": {},
  "accepted_containment": [
    "boat_with_passenger",
    "minecart_with_passenger",
    "sealed_cell"
  ],
  "checks": [
    "target_ref_current",
    "containment_matches",
    "passenger_matches_if_vehicle",
    "route_observed",
    "route_sealed",
    "destination_closed",
    "gate_interlock_available"
  ]
}
```

ユーザー準備後、LLMがsnapshotで確認し、新しいroutineを開始します。

`operate_prepared_transfer`の成功には、targetがdestination region内で連続したserver updateにより安定確認され、destinationとsource/routeの開口が閉じ、routine所有のtemporary water/rail powerが停止し、playerがhazard cell外へ出て全input/item-useを解放したことが必要です。passenger入りboat/cartをcleanup目的で攻撃破壊せず、target survivalやdespawn防止が確認不能なら`unknown`を返します。

## 完了後の安全化（Phase 6、実装済み）

domain goal確認後も、プレイヤーを危険な状態でreleaseしないため`FINALIZING`を実行します。Phase 6 v1のlocal completion policyは固定`stay`です。

1. block、inventory、Entity、output等の必須goal postconditionを確認
2. routine-local cleanupを完了
3. 全入力、item-use、automation-owned screenを解放
4. Voice Chat状態を所有権規則に従って復元
5. safe-stay checkpointを確認

safe-stay checkpointは、worldとplayerが存在し、alive、on-ground、非passenger、health 6以上、水平速度の二乗0.01以下、item-useなし、画面なし、screen ownershipがidle、かつ現在可視hostileがいない瞬間に限ります。これは以後の無期限guardを意味しません。

`continue_goal`成功時はこのcheckpoint後もlocal armingを維持します。`finish_goal`成功時は`goal_finished`でlockします。安全条件を満たせないfinalizationはdomain goalの確認状態と分けて失敗とし、失敗、cancel、emergency stopはいずれもcontinuation chainを破棄してlockします。

safe anchorへの自動帰還、local `ask`、自動切断、自動food/sleep maintenanceはv1に実装しません。必要ならLLM/MCP clientが`navigate_to`や`sleep_at_bed`を`continue_goal`の明示的な中間routineとして実行してから、最後を`finish_goal`で閉じます。自動再接続・自動ログインも行いません。

周囲へblockを置く自己防衛は、無制限に行うと建築物の破壊や閉じ込めにつながります。後段で実装する場合だけ、明示されたbuild region、allowlist素材、固定のtemporary shelter template、時間上限を持つlocal policyとして扱います。

自己防衛は退避を優先します。将来`defend_and_retreat`を追加する場合も、LOS内で実際に攻撃・追跡しているhostile mobだけを対象とし、player、passive、tamed mobを常に除外します。追跡やloot目的の戦闘、無期限guardは実装しません。

## 明示的に作らないもの

- 自由文`run_goal`
- JSON workflow/if/loop DSL
- 任意postcondition式
- transaction rollback engine
- 永続job DB
- raw tick event stream
- notification/webhook必須の制御
- 無期限guard、任意Mob戦闘
- 自動ログイン、自動再接続
