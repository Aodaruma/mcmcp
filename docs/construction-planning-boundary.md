# 建築計画・Blueprint・決定論的実行の境界

- 状態: Stage 3/4 development prototype
- 初版: 2026-08-21
- 更新: 2026-08-24
- 対象: Phase 6完了後の建築自動化とCreative設計支援

## 結論

現時点では、独自の「建築パッケージ」、組込みLua VM、汎用workflow DSLは作りません。

まず必要なのは次の3点です。

1. 完成状態だけを保持する有限な`Blueprint`
2. 既存の`navigate_to`と`apply_block_plan`を、LLMなしで決められた順に呼ぶdevelopment runner
3. Creativeワールドの明示領域を`Blueprint`、材料集計、設計図画像用のgzip artifactへ変換する、world-read-onlyな非同期経路

作業地点、phase、checkpointは完成形の一部ではありません。初版では再利用artifactへ保存せず、実行時入力または現在のworld stateから派生させます。外部Lua等を使う場合も、Minecraftを直接操作するruntimeではなく、有限な相対cellを生成するpure generatorに限定します。

この判断は「決定論的toolなら必ず完成する」という意味ではありません。ここでの決定論性は、同じmanifestと同じ観測状態から同じ順序で動き、次のどちらかへ必ず収束することです。

- server-confirmedな完成
- 理由を伴うtyped safe stop

reach、FOV、衝突、Mob、流体、server同期があるため、一般のプレイヤー操作に無条件の完成保証はできません。

## なぜ方針を縮小したか

既存実装には、最大512セルの`BlockPlan`と、1phase最大64セルの`apply_block_plan`があります。この2つを使う前に、geometry primitive、dependency graph、work-pose compiler、package registryまで新設すると、実測していない問題へ先回りする設計になります。

| 案 | 現在の判断 | 理由 |
|---|---|---|
| 有限`Blueprint` | 採用 | Creative captureと完成比較の共通artifactになる |
| 外部の固定runner | 採用 | 建築開始後のLLM呼出しを0にして実測できる |
| Luaによるpure generator | 必要時に許可 | 規則形状を短く書けるが、world操作権限は持たせない |
| 独自の建築パッケージ/compiler | 保留 | 既存`BlockPlan`との重複が多い |
| 組込みLua VM / 汎用DSL | 不採用 | 安全runtime、再開、同期を二重実装することになる |

建築パッケージを再検討するのは、少なくとも複数の一般建築gateで、`Blueprint`だけでは表せない同じ不足が繰り返し確認された後です。

## 現状の把握

Phase 6までの境界は、LLMがroutineを選ぶouter loopと、Minecraft内で通常操作、同期、停止を担うinner loopに分かれています。

### 機能している部分

- `compare_block_plan`: current / last-known / unknownを区別した最大512セルの比較
- `apply_block_plan`: current-only precondition、完全BlockState、最大64セルの局所施工
- `navigate_to`: 有限bounds内の通常移動
- `completion_intent=continue_goal`: 最大16回の中間routine継続
- prediction ACK、server由来state、入力解放、failure lock

### アイアンゴーレムTT評価で分かった不足

- 3セルの局所施工は成功したが、広い床は作業位置と視点の組合せで停止した
- 移動は観測probeがcurrentにならず、安全側へ停止する場合があった
- fixture支援後にゴーレムspawnは確認したが、水流搬送、kill、drop回収は未達だった
- 建築、Mob条件、睡眠、流体、回収を一度に評価したため、原因が混ざった
- false successやblind retryは起きず、安全停止は機能した

したがってTT自体の修正は一旦止め、まずMobや流体を含まない一般建築でrunnerの限界を測ります。

## 問題の分割

建築は次の6層に分けます。

1. 要求解釈: 用途、寸法、外観、許容条件
2. 構造設計: 完成時の相対座標と完全BlockState
3. 施工設計: 支持、閉鎖前確認、仮設、材料
4. 作業計画: pose、経路、視点、64セル以内のphase
5. 実行: 通常input、prediction、server同期、有限retry
6. 受入確認: 完成状態と、必要なら時間発展

LLMは1と設計上の選択に使います。反復的な座標変換、phase loop、polling、同期確認はLLMの外へ出します。ただし、3と4を一般化するcompilerはまだ作らず、固定fixtureで必要性を測ります。

## 最小アーキテクチャ

```text
Creative local world ─ capture_creative_region ─┐
                                                ├─ finite Blueprint
LLM / optional pure generator ──────────────────┘
                                                        │
                                                        ├─ material list
                                                        ├─ layer SVG
                                                        └─ reviewed build manifest
                                                                  │
                                                                  v
                                                     development fixed runner
                                                     apply -> navigate -> apply
                                                                  │
                                                                  v
                                                     existing bounded routines
                                                                  │
                                                                  v
                                                     server-confirmed state
```

Blueprintからbuild manifestを自動生成する一般access plannerは、まだ存在しません。図の`reviewed build manifest`は、現段階ではfixture用に人間またはLLMが開始前に一度作る入力です。runner開始後はLLMを呼びません。

## Blueprint artifact v1

外側の`craftagent.creative-blueprint-artifact/v1`がcapture根拠・集計を保持し、その`blueprint`に`craftagent.blueprint-palette-rle/v1`を格納します。内側Blueprintは大領域でも扱える完成状態のportable表現です。

- anchor、dimensions、clipped chunk segmentから決定的に復元できる相対offset
- airを含む領域内の全cellを表すpalette＋run-length encoding（`chunk_z_x_then_y_z_x_within_clipped_chunk`順）
- 各palette entryのregistry IDと全BlockState property
- `y_z_x`順で計算し、anchorやdimensionに依存しない論理Blueprint SHA-256
- paletteとblock別count
- clone itemに基づく材料推定
- 自動再構築できない要素の`manual_setup`

次は保存しません。

- 任意コード、条件分岐、loop、tool名
- work pose、移動route、retry手順
- 完了フラグ
- BlockEntity NBT、container内容
- Entity UUID、health、AI、owner、equipment

再開時は保存済みフラグではなく、worldのcurrent exact stateと照合します。

### 材料集計の意味

材料数は`BlockState#getCloneItemStack(..., false)`を使う推定です。airとmulti-cell blockの副側は数えません。次は`manual_setup`へ分類し、完全な自動再現とは主張しません。

- BlockEntity
- fluid / waterlogged state
- door、bed等のmulti-cell
- clone itemを解決できないblock
- Entity
- 動的なredstoneの時間状態

## Creative観測profile

通常の`get_snapshot`とWorldMemoryの意味は変えません。Creativeだけ、明示的な別toolで観測範囲を広げます。

`capture_creative_region`の権限gateは次の4条件です。

- このクライアントが所有する非公開のintegrated single-player
- 対応するserver playerの実GameTypeがCreative
- cheatsが有効で、server playerがGM permissionを持つ
- 現在のworld sessionでCreative capture capabilityがlocal arm済み

playerからの距離、clientへの事前chunk load、512 cellは権限条件にしません。資源上限として、各辺256以下、volume 4,194,304以下、最大64 chunk column、同時1 job、同時に扱うchunkは1つ、artifact展開後64 MiBまでに制限します。現在dimension内の生成済みchunkをintegrated server threadで順次一時loadし、処理後に解放します。未生成chunkは生成せず、常設のforceloadも残しません。

captureは`start / status`による非同期jobです。MCP応答は`job_id`、進捗、terminal summary、gzip bytesの`artifact.sha256`、論理Blueprintの`summary.blueprint_hash`、相対artifact path等の小さな情報だけを返し、全cellはgzip artifactへ保存します。artifactは`started_server_tick / completed_server_tick`と`consistency=server_thread_chunk_sequence`を持ち、領域全体のatomic snapshotではありません。結果はObserverやWorldMemoryへ書かず、Survivalの`last_known`へ混ぜません。

`include_entities=true`でも、Entityは各chunkの処理tick時点の限定censusです。型、位置、回転、接地、vehicle/passengerフラグだけを扱い、UUID、NBT、自動再構築情報は保存しません。領域全体のatomicまたはserver-complete snapshotとは表現しません。

### Creative操作の現在境界

Creative操作はcaptureと分離した`edit_creative_world` capabilityです。任意command文字列は受けず、完全BlockStateの`set_block / fill`、固定allowlist・最大16体の`summon_entities`、明示head指定の`undo / redo`だけを公開します。blockは各辺64・最大4,096 cell、現在load済みchunk限定で、BlockEntity、流体、多cell block、TNT/fireをbefore/afterとも拒否します。履歴は現在world sessionのmemory内に最大32件・30分保持し、外部変更との不一致は`divergence`として停止します。

Entity undoは生成時UUIDだけを削除し、redoは記録したtype/poseから再生成します。任意NBT、selector、player、kill、既存Entityのteleport/deleteは対象外です。`gamerule / ban / kick / op / stop`を含むsystem command、Creative inventoryからのitem準備、clone、任意commandは公開しません。この閉じたtoolはSurvival capabilityへ追加されません。

## 設計図画像

`tools/export-blueprint-svg.ps1`はterminal statusが示す`.json.gz` artifactを読み、Y layerごとのSVGを標準PowerShell/.NETだけで生成します。

- Xを右、Zを下として表示
- airも明示
- 完全BlockState単位のpalette
- 相対Yと絶対Y、anchor、Blueprint hashを記載
- 最大4,194,304 cell、各辺256、最大64 chunk columnの完全な直方体だけを受理

画像生成はMinecraft MODへ組み込みません。local artifact処理なので、Java依存やworld書込み権限を増やす必要がないためです。

## Stage 3: development runner

`tools/run-build-gate.ps1`はclosed JSON manifestを検証し、次だけを順に呼びます。

- `navigate_to`
- `apply_block_plan`

runnerは次を固定します。

- 最大17 routine（中間`continue_goal`最大16＋最後の`finish_goal`）
- apply 1phase最大64セル
- 全step、phase、entry IDの一意性
- 有限region、duration、travel、破壊許可
- 非最終routineは`continue_goal`、最後だけ`finish_goal`
- terminalまで`get_routine`をbounded polling
- `SUCCEEDED`、`goal.verified=true`、`finalization.status=succeeded`をすべて要求
- 既知のactive routineはcancelし、どの失敗経路でも最後に`emergency_stop`を試す
- 最終step後は`active_routine=null`と`goal_finished` lockを確認
- Bearer tokenを表示しない

これはproduction用の新routineではなく、LLMを施工loopから外した効果を測るdevelopment toolです。manifestに自由なpredicate、script、任意tool callはありません。

## Stage 4: generic live gate

最初のgateは意図的に小さくします。10×10床や2層小屋へ先に一般化せず、次を1回のrunner起動で確認します。

1. 開始poseから1つ目のwork poseへ通常移動し、2段cobblestone柱を施工
2. 2つ目のwork poseへ通常移動し、反対側の2段柱を施工
3. 最後だけgoalをfinishし、全4セルをcurrent exactで確認

`navigate_to`は直線方向の次cellだけを逐次検査します。次のfeet/head/floorがfirst-person viewでCURRENTになるまでmovement keyをneutralにし、視線を1 tick最大8度だけ回して再観測します。可視なmob/playerが次cellを占有した場合もneutralで待ちます。各windowは40 client tick、再dispatchは最大2回で、解消しなければ入力と視線を復元してREPLANへ停止します。危険block、敵対mob、被ダメージはこの待機対象ではありません。

fixtureは`build_runner` modeで、air→cobblestone 4セル、材料8個、2つのwork poseを固定します。1本目の完成後、tag付きNoAI cowが2本目へのlaneを20 server tickだけ塞ぎ、有限待機からの回復を確認します。他playerも同じ可視occupant判定を通りますが、private singleplayer fixtureでは未検証です。一般path findingや迂回はまだ実装せず、障害が残る場合は同じ有限routeを再観測して停止します。

次の拡張は、この小gateがliveで安定してから行います。

1. 10×10床＋中央2×2穴
2. 階段、slab、hopperを含む小構造
3. 中断・再開、資材不足、外部divergence、到達不能
4. Creative capture → Blueprint → Survival replay
5. fluid、multi-cell、BlockEntity
6. 準備済みEntity handoff
7. アイアンゴーレムTT統合

## one-shotの定義

one-shotは1 packetや1 Minecraft actionではありません。次の契約です。

1. ユーザーまたはLLMがBlueprint、anchor、bounds、材料を開始前に確定する
2. manifestを副作用なしで検証する
3. runnerを1回開始する
4. 施工中のLLM呼出しを0にする
5. 完成をpositive evidenceで確認するか、再計画材料を伴って安全停止する

セル数が増えたときにLLM tokenが増えないことは、巨大なartifactを毎回MCP応答へ再掲しないことで確認します。セル詳細はlocal gzip artifactへ置き、LLMへは`job_id / progress / terminal summary / artifact.sha256 / summary.blueprint_hash / relative path / server tick範囲`だけを戻します。

## 残る不足

現段階で未実装なのは次です。

- Blueprintからwork poseとphaseを自動導出する一般access planner
- staging containerからhotbarまでの確実な資材補充
- Creative inventoryからのbounded item準備
- Creativeでの能動block/Entity操作profile
- 足場、jump、飛行、落下、複雑な経路
- fluid、BlockEntity、multi-cell、動的回路の再現
- Entityの生成、捕獲、搬送、配置
- process再起動をまたぐcheckpoint
- TTの水流、panic、kill、drop回収の時間発展probe

これらを一度にDSLへ入れません。generic gateで同じ不足が繰り返された機能だけを、型付きの小さなadapterとして追加します。

## 判断

ComputerCraft風の短い記述から学ぶべき点は、LLMに反復操作をさせないことです。一方、プレイヤーにはreach、FOV、衝突、Mob、inventory、server同期があるため、Lua実行機そのものを移植しても問題は消えません。

したがって当面の境界は、`Blueprint`を交換形式、既存routineを実行単位、外部固定runnerを反復主体とします。豊富な建築パッケージは仮説のまま実装せず、この最小構成のlive結果から必要性を判断します。
