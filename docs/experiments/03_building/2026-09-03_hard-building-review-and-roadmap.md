# 高難易度建築コピー失敗レビューと採用ロードマップ（2026-09-03）

## 文書の状態

本書は、[2026-09-02の正式評価](./2026-09-02_hard-building-copy.md)を受けた設計レビューである。以下の「採用」は実装方針として採用したという意味である。個別項目の実装状態は末尾に明記し、方針採用だけで実装済みとは扱わない。

判断基準は単に今回のfixtureを通すことではなく、LLMが観測から目標を理解し、通常のplayer操作だけで未知のMinecraftタスクを安全に計画・実行・検証できるか、とした。Ponytailの観点では、既存の観測filter、座標transform、A*、設置予測・server ackを再利用し、症状ごとの例外追加ではなく一つの根因へ効く最小の責務分割を選ぶ。

## 先に訂正すべき「58%」

`171 / 294 = 58.16%`という全cell一致率だけを見ると半分以上を建てたように見える。しかし比較領域にはairが165 cellsあり、空のdestinationでも`165 / 294 = 56.12%`になる。

| 指標 | run07 | 解釈 |
|---|---:|---|
| 全cell完全BlockState一致 | 171 / 294（58.16%） | airを含むため進捗を過大評価する |
| 空destination baseline | 165 / 294（56.12%） | 何も建てない場合の得点 |
| baselineからの純増 | +6 cells（+2.04 pt） | 今回得られた実質改善 |
| 期待non-airの正解 | 10 / 129（7.75%） | 建築物としての主指標 |
| 期待airへの誤配置 | 4 cells | 開口・輪郭も悪化 |

したがって、今回の到達度は「58%完成」ではなく、**非air構造の7.75%を正しく設置し、空baseline比では2.04ポイント改善**と表現する。これは失敗を過小評価しないための評価契約上の修正であり、次回から全cell一致率を単独の進捗表示に使わない。

## 良かった点

- production公開5 Toolだけを使い、command、fixture、admin配置なしで通常のplayer操作を行えた。
- 約13分で見本把握、材料回収、最初のcraftまで進み、craft自体は約2分半で成立した。
- sourceの既存129 non-air blocksは破壊・置換しなかった。
- deadline時に未完了を申告し、Action、input owner、入力lockを正常に終了した。
- `MinecraftApplyBlockPlanPort`は`BlockPlaceContext`で設置結果を予測し、exact stateとなるhitだけを通常操作として送信し、直前supportとserver差分も検証する。この基盤は作り直す必要がない。

## 根因

### 1. 「覚えた設計情報」と「今操作してよい証拠」が同じ60秒TTLである

`DeliveredPolicyEvidenceStore`は実際にLLMへ配達した可視面だけを保持する点では正しいが、保持時間は60秒である。さらに現在のconstruction plannerは、設置ごとに見本側のexact `source_state`とplacement itemがこの証拠内にあることを要求する。

そのため、8分かけて見本を観察しても、そのBlockStateを後工程の設計知識として使い続けられない。run07で見本からdestinationへ階段を連鎖配置する「型材」が必要になったのは、LLMの理解不足というより、長期知識を短期の操作authorizationとして扱う契約上の問題である。TTLを数分へ伸ばすだけでは、大きい建築で同じ問題が再発する。

### 2. 公開constructionがstationary・1〜8件のvertical sliceに留まる

`apply_known_block_plan`は1 Action 1〜8 entry、`maxTravelBlocks=0`である。一方、内部には最大64 stepのphase requestと最大512 expected blocksの比較表現がすでにある。現在はLLMが観測、移動、視点合わせ、8件以下の設置、再観測を外側で何百回も組み合わせる必要があり、一段目外周だけで33分かかった。

単純に公開上限を8から256へ上げても、同じ姿勢から届かず、camera・support・経路条件でまとめて失敗する。欠けているのは大きい配列ではなく、施工中に作業姿勢を選び直す継続jobである。

### 3. 既知移動グラフに安全なedgeを捨てる不具合がある

`LocalObservationVolume`は移動先cellを一度発見すると、後から別方向で評価した同じcellへのedgeを記録せず捨てていた。その結果、返却済みの隣接`navigation_target`であっても、探索順によってA*から孤立し得る。run07の`NO_KNOWN_PATH` 39件には、同じfresh frame内の隣接1 cellさえ失敗する例が含まれた。

また、A*が許可する幾何経路長は最大32 blocksだが、実行予算は安全余裕として`geometric × 1.5 + vertical allowance`を使い、公開DSLの距離上限も32である。このため、返却・探索できるのに最大budgetでも受付不能な経路が存在する。いずれもLLMの再計画では回復できないAPI契約の問題である。

今回のP0修正では公開`max_distance_blocks=32`を維持し、A*、`RoutePlan`、planner、executorを同じ実行距離式へ統一した。A*はfirst waypointまでのcell内位置ずれと抽象pose誤差を`1.5 × √6` blocksとして先に予約し、残る約28.33 blocksだけをcenterline trajectoryと垂直edge余裕へ使う。したがって平坦な整数cardinal経路は最大18 edgesであり、垂直edgeを含むほど上限は短くなる。これにより、安全余裕を削らず、A*が返す経路は許可された境界poseでも公開最大budgetへ収まる。同じfresh local frameが公開する半径6 blocks以内の全`navigation_target`が最大budgetで受付可能なことも契約試験で固定した。

### 4. 必要なsupport faceを選んで受け取れない

内部frameは同じblockの複数面を保持するが、配信時はpositionごとに1面へ圧縮し、UP面を優先する。一方、constructionはsupportのexact faceを要求するため、実際には視認済みの側面が返却結果から落ち、LLMが位置替えと再観測を繰り返す場合がある。

既存filterはすでにblock ID、entity、item、crop、座標boundsを持つ。別のblock一覧APIを増やさず、同じdelivery-only filterへ`faces`を加え、代表面を選ぶ前に絞るのが最小かつ汎用的である。

### 5. 高低差を作る移動と仮設足場が閉じていない

既存navigationは、公開距離budget 32 blocksから開始pose reserveと垂直edge余裕を差し引いた範囲で観測済みの既知経路を通れるが、frontier探索、full-blockのstep-up/down edge生成、複数blockのpillaring、足場新設、縁でのsneak bridgeは未実装である。現在の平坦な整数cardinal経路上限は18 edgesである。run07では`NO_KNOWN_PATH`が39件、`TARGET_UNKNOWN`が30件、`pillar_up_known`は4回とも失敗した。

建築では、目的物と同じくらい「どこに立てば置けるか」「そこへどう安全に行くか」が本体になる。この責務を毎回LLMへ座標列として合成させると、任意規模・高所施工には伸びない。

### 6. 隣接で変わるBlockStateを各step直後に完全一致させている

stairsの`shape`、pane・fence・wallの接続は、隣接blockを置いた後に変化する。現在のexact postconditionは各child直後の全property一致なので、最終形では正しくても途中形が異なる並びを拒否し得る。run07の階段屋根では、方向、half、shape、支持、作業高さを同時に満たせず、約65分でexact copyを諦めた。

### 7. recoverable errorが次の一手を機械可読に返さない

planner内部には失敗理由があるが、公開errorは主に`code/message/recoverable`で、runtimeのplanning failure detailは空である。LLMは「何が足りないか」を調べるため再観測と再試行を重ねた。MCP応答待ちの合計は約211秒、全体の3.9%に過ぎず、時間の主因は呼出し間の再観測、位置取り、方針変更だった。

### 8. 完全一致を達成不能と判断した後も近似を継続した

今回の合格条件はexact copyなのに、65分時点で板材屋根、さらに平屋根へ近似した。これは成功条件を改善せず、検証・撤去に残すべき約24分を消費した。時間上限を延ばすだけでは解決しない。

### 9. 材料と仮設物の所有・収支が追跡されていない

1 oak log、すなわち4 planks相当の行先を保存stateから説明できず、source内2 cellsと領域外3 cellsにも仮設が残った。自律実行では、設置成功だけでなく、持ち出し、加工、仮設、破壊drop、回収、despawnを一つのjob収支として扱う必要がある。

## 検討案と判断

| 案 | 判断 | 理由 |
|---|---|---|
| TTL延長、camera上限緩和、entry上限拡大だけ | 単独では不採用 | 症状を遅らせるだけで、知識とauthorizationの混同、作業姿勢、隣接stateを解決しない |
| LLMが従来どおり1操作ずつ組み立てる | 不採用 | 汎用に見えるが、Minecraft固有の幾何・設置規則を毎回token上で再実装することになる |
| command、`/clone`、fixtureで完成させる | 不採用 | player操作による自律実行という評価対象を消してしまう |
| 画面座標ベースのcomputer-useを主経路にする | 不採用 | 解像度、GUI skin、遅延に弱く、server stateと同期した検証ができない。未知GUIのfallbackには残す |
| 観測由来blueprintとmovement-aware construction job | **採用** | LLMが目標・方針を決め、MCPが既存の安全な移動・設置機構でMinecraft固有の反復を遂行できる |

## 採用する責務分割

LLMは、何を見本にするか、どこへどのtransformで作るか、使ってよい材料・作業領域・近似可否を決める。MCMCPは、観測済み事実から施工可能な順序と作業姿勢を計算し、通常のplayer入力だけを用いて有限・中断可能なjobとして実行する。

```text
LLMの目標判断
  -> 観測由来 blueprint_ref（未知cellを明示）
  -> 材料表・不足品
  -> dependency / 作業姿勢 / 仮設計画
  -> 短い施工phaseを反復
  -> exact検証・仮設撤去・材料収支
```

採用するconstruction jobは次を満たす。

1. 配達済み可視情報だけから、session-scopedな`blueprint_ref`と`placement_state_ref`を発行する。未観測・遮蔽cellはunknownのままにし、worldの隠れたblockを読まない。
2. `placement_state_ref`は座標非依存の「観測して覚えた設置状態」とし、world session終了まで保持する。target、support、ray、player pose、hazardは短期かつ実行直前に再検証する。
3. 既存のserver-side rotation/mirror変換を使い、block座標とnavigation座標の変換をLLMへ戻さない。
4. 既存の`MinecraftApplyBlockPlanPort`、A*、server ack、内部64-step phase、512-block比較を再利用する。別の設置engineは作らない。
5. jobは一括不可分にせず、観測・移動・設置・検証の短いcheckpointへ分割する。停止、deadline、context compaction後もopaque job refから再開できる。
6. 仮設blockはjob所有として記録し、許可された作業領域だけへ置き、完了時に撤去・drop回収・残留検証を行う。sourceは自動rollbackせず、最初からmutation禁止にする。
7. exact必須時に近似へ自動降格しない。成立しない依存や経路が一定回数続いたら、blocked理由と必要能力を返して停止する。

これは「自動建築コマンド」ではない。LLMからMinecraftの意味判断を奪わず、プレイヤーが手足で行う反復的な照準・足場・設置を、検証可能なmotion controllerとしてMCP側へ閉じるものである。未知タスクにも使える汎用primitiveを作り、特定fixtureの座標や完成形は埋め込まない。

## 実装順

### P0: 評価と知識契約を正す

- 全cell一致と別に、non-air recall/precision、余計なblock、property一致、source保全、仮設残留、材料収支を必須出力にする。
- Dockerのsandbox `configWarning`をcontainer側で解消し、監査をallowlistで緩めない。
- 到達済みcellへの評価済みedgeも移動グラフへ保持し、探索queueへの再追加だけを抑える。対角移動の安全条件そのものは緩めない。
- 公開する全`navigation_target`について、同じfresh frameと公開最大budgetで実行可能であることを契約試験する。経路上限と実行cost式を同じ定数源へ揃える。
- 既存観測filterへ`faces`を追加し、すでにpolicy-visibleな面から必要なsupport faceだけを選べるようにする。観測範囲やhidden情報は増やさない。
- 配達済みsurfaceからboundedな`placement_state_ref`を発行し、座標依存の短期evidenceから分離する。単なるTTL延長はしない。
- 既存の`ObservationFilter`（block ID、entity、display item、crop、座標bounds）をそのまま入口に使う。別の検索文法は追加しない。
- recoverable errorへ、公開済み情報だけを使った`reason`、`needed_evidence`、推奨filter、既知の候補navigation target、必要budget下限、再観測すべきframeを追加する。
- Actionが途中でserver ACK済みの変更を行ってから失敗した場合、entry別`effects`と再開checkpointを返す。Action全体の`failed`を「world変更なし」と解釈させない。

### P1: movement-aware construction jobの最小縦切り

- `blueprint_ref`、unknown mask、layer/material summary、BOMを作る。
- target anchorと既存transformから依存DAGを作り、到達可能な作業姿勢ごとに短いphaseへ分ける。
- job ref、完了/残件数、現在phase、blocked reason、deadline reserveを永続的なsession stateとして返す。
- 最初はfull cubeの壁だけを対象にし、既存設置portへ接続する。最初から全block種へ一般化しない。
- camera 40度制限は、既存の実際の旋回・復元とtotal camera budgetで安全を証明できる範囲に限り撤廃または拡張する。

### P2: 建築に必要な垂直・方向付き能力

- Vanilla collision resolverを使うfull-block 1段step-up/downと短距離の局所再計画を追加する。
- 連続pillar、ladder/scaffolding新設、crouchを含む安全なedge bridgeをjob内部能力として追加する。
- 足場は安定AABB、head clearance、落下余地、support、毎tick入力leaseを再検証し、所有物だけを撤去する。
- BlockState propertyを「設置時に決まるもの」（例: `facing`、`half`、`axis`）と「近傍で決まるもの」（例: stairs `shape`、pane/fence/wall接続）に分類する。前者は各stepで検証し、後者は近傍phase完了後にexact検証する。
- 屋根列・corner・door・slab・paneを、順序探索と限定repairを含むcomponent gateで通す。

### P3: 規模・材料加工・MOD/GUIへ拡張

- checkpointを保ったまま最大256 changesへ拡張する。1 Actionを長大化するのではなく、既存64-step phaseを反復する。
- craft、smelt、storage、回収をBOMと材料ledgerへ接続し、加工前後と仮設dropを追跡する。
- MOD blockは名称で一括許可せず、`BlockItem`、単一cell、非fluid、非gravity、非block-entity等をruntimeで証明できる保守的経路と、version固定profileを組み合わせる。
- 未知MOD item/blockの生IDやslot番号をLLMに推測させず、観測・同期済み状態からopaque placement/menu refを発行する。
- GUIはserver同期済みslot/widgetを使う共通Menu engineを第一経路にする。screen fingerprintごとの宣言profileを追加し、画面操作は同期情報を得られない未知GUIだけのsandboxed fallbackとする。

## 短い能力gate

次のgateを順番に通し、直前gateが安定するまで90分のfull-buildingを再実行しない。

| Gate | 最小課題 | 合格条件 |
|---|---|---|
| A | 観測した階段stateを60秒超・context compaction後に再利用 | refは生存し、target/supportだけfreshに再検証 |
| B | 3×3、次に5×5のfull-cube壁 | non-air 100%、余計なblock 0、source無変更 |
| C | 1〜2段のpillar/step/edge bridge | 落下なし、入力解放、仮設100%撤去・回収 |
| D | stairs直線・内外corner | 最終全property 100%、途中の近傍変化を誤失敗しない |
| E | door、slab、paneを含む小屋 | 開口、multi-cell、接続stateを完全一致 |
| F | chest材料からcraft・smeltして施工 | 材料ledger一致、残留drop 0 |
| G | 今回の高難易度建築 | non-air/property/source/cleanup/収支をすべて満たす |

component gateはfixture専用Actionを作るためではなく、汎用能力を一つずつ隔離して失敗原因を短時間で再現するために用いる。

## 次回からの評価指標

合否は以下を別々に記録し、重み付きの総合値だけでは隠さない。

- expected non-air recall: 必要blockを何個正しく作れたか。
- placed precision: 置いたblockのうち正しい位置・stateはいくつか。
- BlockState property accuracy: block ID一致と、方向・half・shape・attachment等の一致を分離する。
- expected-air violations: 開口や外形を塞いだ数。
- source preservation: sourceの既存blockとairをともに不変に保ったか。
- temporary ownership/cleanup: job所有仮設の残数、未回収drop、despawn。
- resource ledger: chest、inventory、craft、smelt、place、break、collectの保存則。
- autonomy/safety: 公開Toolのみ、operator介入なし、commandなし、Action terminal、入力解放、監査合格。
- efficiency: wall-clockに加え、観測、計画、移動、設置、recovery、verification別の時間と、error code別再試行数。

## 時間・停止ポリシー

survey、材料、基礎、上部、屋根、cleanup/verifyへ予算を分け、最低15〜20%を検証と撤去のreserveとして保持する。exact経路が成立しない場合は、近似へ変えるのではなく次のcheckpointで停止する。ユーザーが明示的に近似を許した場合だけ、別のgoal revisionとして再開する。

90分上限そのものは今回の第一原因ではない。exact方針を65分で捨てた後に約24分近似したため、同じ仕組みのまま上限だけ増やしても完成確率は大きく上がらない。まず呼出し間の再計画をjobへ集約し、短いgateで改善を測る。

## 明示的にまだ完了していないもの

- session-scoped `placement_state_ref` / `blueprint_ref`
- movement-aware construction jobと永続checkpoint
- frontierを用いた段階的な観測移動
- full-block step-up/down、連続pillar、安全なedge bridge、job所有足場cleanup
- 近傍依存BlockStateの遅延検証と順序solver
- 256 changesの任意規模施工
- constructionとcraft/smelt/material ledgerの統合
- 一般MOD block capability判定と未知MOD GUI fallback

既存Phase番号が5まで進んでいても、これらが埋まるまでは「任意規模の高難易度建築コピーが完了済み」とは扱わない。

## 本レビュー時に先行反映したP0修正

方針のうち、安全境界を広げず独立に検証できる次の2件は本レビューと同時に実装した。

- `LocalObservationVolume`で、到達済みcellへの評価済みedgeも公開recordへ残し、BFS queueへの重複追加だけを抑えるよう修正した。対角のcorner安全条件は変更していない。
- 既存`agent_get_observation.filter`へ`faces`を追加した。代表面をposition単位へ圧縮する前に適用し、raw frame、観測半径、policy-visible範囲は変更していない。

既存のfocused unit/contract testと通常unit test suiteは合格済みである。今回さらに、成功配達したcopyable surfaceだけからbounded・world-session scopedな`placement_state_ref`を発行し、見本座標の60秒evidence TTLと設置identityを分離するvertical sliceを実装した。inline `source_state`+`item`とのexact one-of、ref解決後の既存SafeConstructionBlockPolicy、target/support/JIT検証は維持する。また、navigationの公開32 blocks、trajectory係数、垂直余裕、開始pose reserveを単一定義へ揃え、freshな公開targetとA*返却経路が最大budgetで受付不能になる契約不一致を解消した。追加分はremote Dockerでfocused 156件と`check`が成功し、最終P0修正後も関連focused suiteと`check`を再実行して成功した。実ワールドでは`navigation`、`faces-place`、`state-ref-ttl`の3 gateがfresh baselineとoffline oracle付きで合格した。途中で見つかったcontainer中心照準の遮蔽不具合も、配達済みvisible ray hitを内部adapterまで保持する修正後に再試験している。詳細は[construction capability gate記録](./2026-09-03_construction-capability-gate.md)を参照。Gate B、construction job、構造化error等は引き続き未実装である。
