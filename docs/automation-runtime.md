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

## one-shot contract

one-shotは、1回のユーザー依頼からLLMが複数のMCP呼び出しを組み合わせて完遂を目指すUXです。自由文goal、任意条件式、if/loopを含むworkflow DSLをMODへ渡す意味ではありません。

複数routineの中間段階では`completion_intent=continue_goal`、ユーザーgoalを閉じる最後のroutineでは`finish_goal`を使います。省略時は安全側の`finish_goal`です。`continue_goal`はroutine-local cleanupとstable checkpointまでで、帰宅・問い合わせ・切断を先送りできますが、local armingの回数・総時間・expiry上限を越えられません。

成功条件:

- domain goalの必須postconditionがすべて`confirmed`
- unknownを必須条件へ残さない
- 指定された完了後policyを満たす
- 全入力とautomation-owned screenを解放する

完遂できない場合は、未完了を成功扱いせず、構造化された理由と再計画材料を返します。

## 実行envelopeと期限

routineはVALIDATING時に、MCPで指定されたwork regionと、local UIで事前承認されたbed/safe anchorおよびtransit corridorを同じdimensionの固定`execution_envelope`へまとめます。maintenance、局所回復、finalizationもこのenvelopeを越えず、実行中に権限を広げません。

公開する最大時間の内側に、maintenanceとFINALIZINGのための内部reserveを確保します。work soft deadlineでは新しい作業stepを始めずFINALIZINGへ移り、reserveを含むhard deadline、緊急停止、切断等では移動を試みず即座に入力を解放します。

## finite actionの実行契約

すべての意味的actionは次の順で動きます。

```text
PRECHECK
  -> already satisfiedなら成功
  -> EXECUTE
  -> WAIT_SERVER_SYNC
  -> VERIFY
  -> SUCCEEDED
```

失敗時はfresh observationを取り、同じpostconditionへ収束する局所retryだけを通常2〜3回行います。再照準、別の設置面、再接近、hotbar再選択、許可範囲内のrepathは可能です。資材方針、blueprint、Entity同定、許可regionを勝手に変更しません。

postconditionはaction実装へ固定し、LLMが任意predicateを注入する機能は作りません。

例:

- `place_block`: 指定座標が期待block IDと必要propertyになった
- `break_block`: 対象がなくなった、または指定replacementになった
- `navigate_to`: tolerance内、安定床上、危険状態なし
- `craft_items`: サーバー同期後のinventoryが目標countを満たす
- `transfer_items`: sourceとdestination双方の同期後countが目標を満たす
- `interact_entity`: routineごとの観測可能な結果を確認した
- `apply_block_plan`: 対象phaseの必須expected座標がすべてcurrentかつ一致し、必須集合のunknownが0

単にpacketやclickを送れたことは成功にしません。

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

`WAITING`中もSafety controller、survival policy、soft/hard deadlineは動作し続けます。各checkpointは、入力を解放できる安定床上で、危険な中間GUIや未確定actionを残していない境界です。非緊急failureでは固定envelope内の直近safe checkpointまたはsafe anchorへbounded returnしてから`FAILED`とし、緊急停止、cancel、死亡、切断等では帰還を試みず即時releaseを優先します。

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

建築planは巨大な命令DSLではなく、anchorからの相対座標、block ID、必要なBlockState propertyで表します。

1. `get_snapshot`で現場、inventory、既知memoryを確認
2. `compare_block_plan`でphaseの差分とunknownを取得
3. 必要なら`get_recipes`、採取、craft、transferを実行
4. `apply_block_plan`が通常操作で施工
5. 各blockのserver-confirmed postcondition後にcheckpoint
6. phase終了時に再比較
7. 内部機構確認後に外装phaseを閉じる

施設固有の`build_iron_golem_farm`は作りません。LLMが設計を選び、汎用block plan、資材、Entity handoff、稼働観測を組み合わせます。

## 農林業

`tend_crop_area`と`harvest_tree_area`は、可視・記憶済みの指定regionだけで動くroutineです。

- crop ID/tagと全BlockStateを確認する
- 成熟条件をversion/runtime dataに基づいて判断する
- 収穫、drop回収、植え直し後に状態を確認する
- sapling、log、leaves、支持面、成長空間を確認する
- 成長待ちは`WAITING`で入力を解放し、deadlineを持つ
- 未観測領域、自然地形全体、隠れた原木を直接検索しない

## クラフトとコンテナ

GUI全面禁止では、craftとtransferを実現できません。画面操作は次に限定します。

- routine自身が開いた、allowlist済みscreen/menuだけ
- routineは指定container block/refへ通常interactionし、自分で開いたscreenだけをadoptする
- screen identity、menu/sync ID、slot revisionを毎操作前後に確認
- 予期しないscreen遷移、手動input、slot desyncで即停止
- inventoryを直接書き換えず、通常のclick pathとserver同期を使う
- container内容は通常画面を開いて確認できた間だけcurrentとして扱う

## 睡眠とsurvival maintenance

長時間routineは、安全なcheckpointで食事・睡眠を挟めます。これは汎用workflowではなく、固定のsurvival maintenanceです。

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

睡眠によるrespawn point変更はvanilla副作用としてlocal UIへ明示し、routine resultにもeffectを記録します。standaloneの`sleep_at_bed`は開始時safe checkpointへ、maintenanceは中断したcheckpointへ戻り、必ずworld差分を再取得してから作業を再開します。

## Entity interactionとuser handoff

初版は可視・通常reach・LOS内への有限`interact_entity`だけを対象にします。取引、餌やり、搾乳、毛刈り、騎乗など、通常の右click相当です。移動、捕獲、押し込み、攻撃、投射物、釣り竿は含めません。

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

## 完了後の安全化

domain goal確認後も、プレイヤーを危険な場所へ放置しないため`FINALIZING`を実行します。

1. block、inventory、Entity、output等の必須goal postconditionを確認
2. 当該routineが作成・変更し、server-confirmed ownership recordを持つcontainer、gate、temporary water、item-use等だけを安全な状態へ戻す
3. local policyで指定されたsafe anchorへ移動
4. 必要なら食事・睡眠
5. 全入力とautomation-owned screenを解放
6. Voice Chat状態を所有権規則に従って復元
7. local policyが許可する場合だけ通常の切断経路を使う

`completion_intent=continue_goal`では1、2、5、6とstable checkpoint確認までを必須とし、3、4、7および`after_completion`はまだ実行しません。`finish_goal`だけが全手順を実行します。これにより資材準備や建築phaseごとに帰宅・切断せず、LLM outer loopが次の型付きroutineへ進めます。

切断は公開MCP toolにせず、ローカルUIで次のようなpolicyを設定します。

```text
after_completion = ask | stay | return_to_safe_anchor | disconnect
on_unrecoverable_danger = stop_and_notify | retreat | disconnect
```

`ask`は全inputを解放した期限付き`WAITING`でlocal UIに問い合わせ、設定済みtimeout後は固定fallbackへ進みます。timeoutとfallbackはhard deadline、unlock expiry、FINALIZING reserve内に収まる場合だけ開始できます。MCP notificationへの応答を必須にしません。`stay`はその場で安定床、既知hazardなし、現在可視hostileなしを確認してreleaseし、その後の無期限guardは行いません。`stop_and_notify`の通知先もlocal UIとevent/audit logであり、push deliveryを成功条件にしません。

cleanup対象のownershipを証明できない場合はworldへ触れず、`goal.verified=true`とfinalization failureを分けて返します。自動再接続・自動ログインは行いません。

safe anchorは絶対安全の保証ではなく、登録座標への到達、安定床、既知hazardなし、現在可視の敵対Mobなし、体力・空腹閾値を満たした瞬間的な確認です。必要policyなのに未登録なら長時間routineを開始しません。

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
