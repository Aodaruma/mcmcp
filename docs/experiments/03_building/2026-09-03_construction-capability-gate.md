# Construction capability gate準備・実行記録（2026-09-03）

## 状態と目的

本書は、[高難易度建築レビュー](./2026-09-03_hard-building-review-and-roadmap.md)後のP0修正を、90分の建築再試験より先に短い実ワールドgateで分離検証するための記録である。初期gateは`ssh aod-mimoid`上のDocker環境、2026-09-03の5×5再試験r1-r10はユーザー指定によりローカルPC上の物理Prism profile、r11以降は再び`aod-mimoid`上のDocker環境で実施した。local実施中は`aod-mimoid`へ接続していない。

remoteでは`navigation` r3、`faces-place` r7、`state-ref-ttl` r1の3 gateに加え、Gate Bの`wall-3x3` r7 / r8がfresh baselineから2回連続で完全合格した。3×3壁は通常player Actionだけで9 blockを設置し、観測由来の仮足場を設置・撤去・回収した後、offline oracleでも恒久9 cell以外の変更0を確認した。`wall-3x3` r1-r6は製品とrunnerの境界不良を一つずつ分離した不合格runとして残す。

5×5 Gate Bも、remote r25 / r27が独立したfresh baselineから2回完全合格した。両runとも通常player Actionだけで恒久25 blockを施工し、一時6 blockの3-2-1 staircaseを全撤去・全回収した。oak logは64→39、Actionはaccepted / terminalが110 / 110（108 succeeded / 2 failed。失敗したbounded navigationはfresh reslice後に回復）、offline oracleは恒久25 cellだけが`air`→`minecraft:oak_log[axis=y]`、不足0・未列挙変更0で一致した。r16-r24 / r26は、安全な高所移動、観測revision、drop回収の揺らぎを一つずつ分離した不合格runとしてartifactを保持する。現行runnerは可視dropの有無だけで決めず、inventory台帳とfresh `visible_entity`をboundedに照合し、遮蔽または`TARGET_UNKNOWN`ではcurrent safe traversabilityが配達した座標へだけ接近して全証拠を取り直す。core / runnerの内部testは通過済みである。これにより5×5 gateは完了したが、90分の高難易度建築コピー、方向性block、station系実ワールドgateは引き続き別の未完了対象である。

## 今回の変更

### route / budget contract

- `NavigationDistanceBudget`へ公開上限32 blocks、trajectory係数1.5、垂直edge余裕1.5、開始pose reserveを集約した。
- A*、`RoutePlan`、planner、executorが同じcost式と上限を使う。A*が返した経路をplannerが公開最大budgetで拒否する旧契約差をなくす。
- r14で`route_edge_changed`直後の再計画が即`BUDGET_EXCEEDED`になったため、coreへ固定20 tickの累積replan reserveを追加した。初回occurrenceがこの余裕を前払いし、rebind時には補充しない。安全距離上限やAction期限を無制限に延ばさず、予算化済みの範囲で経路変化を処理する。
- `NavigationDistanceBudgetContractTest`は境界poseとfresh公開target、`KnownTraversabilityNavigationTest`は更新後の探索境界を固定する。

### `placement_state_ref`

- 成功配達されたpolicy-visibleな`visible_surface.state + placement_item`だけから、推測困難なsession-local refを発行する。hidden worldは読まない。
- refは成功したresponse書き込み後のconfirmation完了までは解決できない。最大512 identityを古い順にevictし、座標surfaceの60秒TTLとは独立してworld session終了まで保持する。
- `apply_known_block_plan` entryは`placement_state_ref`または`source_state + item`のexact one-ofである。refは見本座標の再訪だけを不要にし、target、support、pose、revision、JIT、server ACK、`SafeConstructionBlockPolicy`は緩めない。
- planner受付時とconstruction実行直前にrefを二重解決する。compilerは有効refをordinary=1 / oak-door=2 cellsとして計上し、未知refは安全側の2 cellsで過小評価を防ぐ。
- 当該3 gate実施時点のref対応は`apply_known_block_plan`のみで、`pillar_up_known`は従来のinline identityだった（gate後に同じref storeを再利用する対応を追加）。

### remote gate / reset

- [`Invoke-McmcpConstructionCapabilityGate.ps1`](../../../tools/eval/Invoke-McmcpConstructionCapabilityGate.ps1)は公開5 Toolだけを許可し、長pollでAction terminalを待ち、終了時にAction terminalと`control.mode=ready`を再確認する。
- live HTTPはWindows PowerShell 5.1でも動く`System.Net.Http.HttpClient`を使い、proxyを無効化したうえでredirect、HTTP status、content type、JSONを検証する。PowerShell 7専用の`Invoke-RestMethod -NoProxy -ResponseHeadersVariable`には依存しない。
- `navigation`は配達された整数`navigation_target`を無変換で使い、`faces-place`は`faces=["up"]`とinline state/itemでoak logを1個置く。
- `state-ref-ttl`はsourceを1回だけ観測し、既定65秒の間Toolを呼ばず、その後sourceを再観測せずrefで1個置く。これは60秒TTL分離を検証するが、context compaction自体は検証しない。
- [`Reset-McmcpHardBuildingGateWorld.ps1`](../../../tools/eval/Reset-McmcpHardBuildingGateWorld.ps1)は、固定DataRoot、停止済みの固定containerと`/data` bind、baseline/player SHA-256、archive entryを検証してからstagingへ展開する。旧saveはbackupし、復元hashを確認してreceiptを残す。
- 各gateは`gate-events.jsonl`と`gate-result.json`を出す。offline region oracleはworldをSave and Quitしてcontainerを停止した後だけ実行し、公開MCP操作と混ぜない。

### visible container aimのP0修正

- `faces-place` r6では、chestへ到達するまでの8 navigation Actionは成功したが、`take`が401 tick後に`SERVER_DENIED_OR_DESYNC` / `container_deadline`で終了した。container interactionは0回、world oracle差分も0だった。
- 根因は、観測で配達済みのvisible ray hitをplannerが検証後に破棄し、inventory adapterがblock centerへ照準し直していたことだった。このchestではcenterが手前のblockに遮蔽され、UP面のray hitだけが到達可能だったため、`maintainAim`のtarget一致条件を満たせずinteraction送信前にdeadlineへ達した。
- P0は既存`MutationAim`へ配達済みray hitを保持し、runtimeのopaqueな内部`aim_point`を経て`MinecraftPhaseFiveInventoryPort`へ渡す3 production fileの修正である。adapterでdimension一致、finite、target block bounds内を再検証し、raycast target一致、deadline、JIT、安全境界は緩めていない。
- planner、runtime、inventory portの各1件、計3件の回帰testを追加した。修正後の最終jarはSHA-256 `c562f2eff14ea6dfb0331a278e9e78878731915236585cf25ad0da398381519e`である。

### Gate B 3×3・仮足場・動的drop回収

- 下2段は各3 entryの`apply_known_block_plan`、最上段は仮足場上から奥→手前へ`face_known_position`→1 entry配置→未施工supportのfresh再観測を3回行う。plannerの40°制約をrunnerへ複製せず、既存Action境界へ委ねる。
- 仮足場は同じfresh frameに配達されたwhite-wool UP面と、その直上へ一致する`traversability.navigation_target`から選ぶ。`pillar_up_known(placement_state_ref)`で1 block上がり、施工後は観測由来の安全な地上targetへ降り、完全state付きsurfaceを再観測して1 blockだけ撤去する。fixtureやcommandで壁・足場を置かない。
- 撤去前に近傍oak-log dropが0、撤去後は40 tickの入力なしwaitを挟む。post-settleではinventory `+1` / drop 0を受動回収、inventory差0 / fresh drop 1件を能動回収として扱う。差0 / drop 0は直ちに矛盾とせず、50 ms間隔・最大40 pollの異なるframeでboundedに待つ。`visible_entity.position_bounds`のclient側検証はAPIどおり`floor(position)`で行うが、collectへ渡す連続XYZは丸めない。
- bounded待機後もdropが遮蔽される場合は、current world revisionの安全な`traversability.navigation_target`だけから、撤去位置への距離を厳密に減らす地点を選んで通常移動する。`collect_visible_item`受付が構造化`TARGET_UNKNOWN(recoverable=true)`を返した場合も同じで、拒否されたActionはaccepted / terminalへ数えず、古いdrop / targetを再利用せず、接近後にinventory・drop・traversabilityをすべて取り直す。最大2接近で証拠が成立しなければfail closedする。
- 動くdropが旧pickup cellを外れた場合、runtimeは即`PATH_BLOCKED`にせず入力を解放し、同じ`displayed_item`かつ提出位置から0.75 block以内のfresh witness、既知安全pickup cell / route、実AABBを有効なoccurrence / Action期限内で再証明する。公開recordにentity UUIDはなく個体同一性は主張しない。期限は再失効で延長しない。
- gate eventはAction受付時のbody / budget、terminalのprogress / failure / trace、descent target、settle、post-settle drop座標を保存する。失敗runでも外側の`PATH_BLOCKED`だけで推測しない。

### Gate B 5×5・3-2-1仮足場（remoteで2回完全合格）

- 3×3専用の施工本体を、監査済みprofileだけを受ける共通`Invoke-WallGate`へまとめた。`wall-3x3`は3×3 / 1 blockの仮足場、`wall-5x5`は5×5 / 3-2-1形状の6 block仮足場に固定し、任意値やfixture専用Actionは公開しない。
- 5×5は全5段をfresh player pose基準の奥→手前順にし、各cellを`face`→最初のpost-face frameからfresh exact supportを再取得→1 entry Actionの境界で置く。下2段も5 entry batchにはしない。5幅全体はconstructionの同一admission headingから各supportまで40°以内に収まらず、batch化すると入力前に安全拒否されるためである。r10までの旧vertical pillarでは、仮足場2段目を1段目のoak-log UP面がfreshに再配達された場合だけ同じ`pillar_up_known(placement_state_ref)`で積んでいた。
- r2は材料取得後に31件のfoundation UP面を配達したが、端の面への一般approach後のplayer `(-14.62,56,8.64)`から5幅の反対端が4.5 block reach外となり、施工前に安全停止した。修正後は5幅だけ、観測済み5連続面と同じfresh frameのtraversabilityを結合し、壁列外かつ全5面が4.5以内となる配達targetへ移動してからfoundationを再観測する。固定staging座標は持たない。
- local r4では、staging navigationがtarget `(-19,56,11)`、tolerance `0.75`で成功し、実pose `(-18.501,56,11.240)`からx=-20のfreshな5 support rowを選択した。幾何中心`(-20,55,11)`へのfaceは成功したが、実行順先頭は奥→手前sort後の別supportだったため、5-entry `apply_known_block_plan`は入力前に`TARGET_UNKNOWN` / `Construction support requires a nearer admitted camera heading`で拒否された。placement 0、終了時input解放を確認した。先頭だけへ向いても同じposeに対する後続entryの40°証明が残るため、最終修正は下2段も各supportごとのface・fresh再証明・1-entry Actionへ分割した。
- local r5ではstaging、1件目のface、post-face fresh support、1-entry配置が成功し、`(-20,56,13)`へoak logを通常player Actionで設置した。直後の2件目faceがmutation前の`$supports[$entry]`を再利用したため、`TARGET_UNKNOWN` / `Face target is not current known evidence`で入力前拒否された。追加placement 0、終了時input解放を確認した。修正後の幅5下段loopは、各反復の冒頭でも対象1 supportをexact取得し、`pre-face fresh exact → face → post-face fresh exact → place`を必須にした。
- local r6では1件目の配置まで成功した後、次の`agent_get_state`とsurface取得を行ったにもかかわらず2件目faceが同じ`TARGET_UNKNOWN`で拒否された。tailを比較すると、mutation前後の`latest_frame_id`が同一であり、APIをもう一度呼ぶことは新しい観測frameの保証ではなかった。終了時input解放を確認した。修正後はpost-face supportを取得したframe idを配置直前に記録し、mutation terminal後に`latest_frame_id`が異なるまで50 ms間隔・最大40 pollで待つ。幅5下段だけでなく、幅3 batch、上段singleton、仮足場配置、撤去にも同じbarrierを適用し、未更新時は古い証拠で続行せず失敗する。PowerShell mockは各mutation後に同一frameを最低1回返してから更新し、barrier成功とtimeoutの両方を固定する。
- local r7（`C:\Users\aod\AppData\Local\Temp\mcmcp-hard-building-20260902\local-eval-artifacts\20260903-wall-5x5-r7`）では、1件目のfaceと通常player配置が成功し、oak logは64から63へ減った。配置後barrierも1 pollで別frameへ進んだが、続く`agent_get_state`と`agent_get_observation`後の2件目faceは`TARGET_UNKNOWN` / `Face target is not current known evidence`で入力前拒否された。終了時は`control_ready=true`かつ全Action terminalでinput解放を確認した。解析で、新frameにもmutation前`world_revision`のsurfaceが含まれるtemporal composite性を原因と確定した。製品側はcamera-only `face_known_position`だけ、同一session / dimension / TTL内の配達済み座標へ再照準できるようにし、future revisionを拒否する。設置・破壊・clearのcurrent revision fenceは緩めていない。runnerはexact surface自身のrevisionが現在world revisionへ一致するまで50 ms間隔・最大40 pollで待つ。
- local r8（`C:\Users\aod\AppData\Local\Temp\mcmcp-hard-building-20260902\local-eval-artifacts\20260903-wall-5x5-r8`）は恒久10 / 25 block、41 / 41 Action terminal、59.575 sまで進んだ。下2段は連続成功したが、仮足場へ通常移動用tolerance `0.75`で接近した結果、隣接cell側で停止し、`pillar_up_known`の真下中央条件により入力前拒否された。足場上昇だけtoleranceを`0.1`へ分離し、通常staging / descentの`0.75`は維持した。
- local r9（`C:\Users\aod\AppData\Local\Temp\mcmcp-hard-building-20260902\local-eval-artifacts\20260903-wall-5x5-r9`）は恒久20 / 25 block、仮足場2 block、63 / 63 Action terminal、85.944 sまで進んだ。2段pillaringと高所2段の施工は成功したが、最上段開始時に直前のsupportを見下ろしたまま5個のUP面を待ち、視野外のため40 pollで停止した。各高所段の前に、center cellのcurrent horizontal surfaceを取得して向き直り、その後UP面を取得するreorientationを追加した。
- local r10（`C:\Users\aod\AppData\Local\Temp\mcmcp-hard-building-20260902\local-eval-artifacts\20260903-wall-5x5-r10`）は恒久15 / 25 block、仮足場1 block、53 / 53 Action terminal、70.981 sまで進んだ。reorientationで高所1段は成功したが、その後に2段目足場を追加しようとしたため、間の5 block施工で1段目UP witnessのrevision barrierが進み、保持証拠をruntimeが安全拒否した。r10直後は2段足場を高所施工前に連続完成させ、centered playerが足下面を遮蔽する前提の保持証拠へworld mutationを挟まないよう修正した。これは当時のmockには合格したが、その後r12の実world結果を受けて3-2-1 staircaseへ置き換えた。
- r12の旧vertical pillarは恒久20 / 25と一時2 blockまでは成功したが、低いeyeから最上段supportのUP面が見えず、単純な柱では施工中の視認性と安全な降下を両立できなかった。このため、同じfresh frameに配達されたwhite-wool UP面とsafe traversabilityだけから、地上→low 1→medium 2→high 3の直線staircaseを選ぶ。fixture、command、固定座標は使わない。
- staircase選択時は、各足場列の上面がpolicy-visibleであり、地上からlow、lowからmedium、mediumからhighへ隣接移動できることを要求する。cleanupもhigh→medium→low→groundの各遷移を隣接かつ`|dy| <= 1`で証明する。撤去順はH3→M2→H2→L1→M1→H1（Y降順、同じYではcolumn height昇順）とし、各撤去・drop回収後に残存足場と地上だけで次の退避先までの経路を再証明する。
- mockの固定profileでは恒久25 cell、一時6 cell、wall placement 25 Action、pillar 6 Action、row face 28 Action、cleanup face 6 Action、clear / wait各6 Actionを固定する。99 Actionはmaterial接近やliveの追加navigation sliceを省いたmock内の基準値であり、実worldの固定合格値ではない。live r25 / r27はいずれも110 Actionで、dropの位置によりnavigation / collect内訳は異なったが、全Action terminal、施工前後のoak log収支64→39、恒久25 cellだけの変更、一時6 cellのair復元、source不変、未列挙変更0が一致した。
- bounded approachは安全候補の単純な移動距離最小ではなく、目的物までの`goal_distance`最小、すなわち1回ごとの最大進捗を優先する。同率時だけ進捗量と移動距離をtie-breakに使い、短い移動を繰り返して試行上限を使い切ったr13を固定する。
- 初期staircase探索は候補surfaceを三重に走査しており、noise surfaceが増えると`O(S^3 + ...)`相当になっていた。r15ではこの処理だけで約64.5秒を費やし、施工証拠が`FRAME_EXPIRED`になった。現行runnerは座標辞書indexと4方向の直線走査へ置き換え、全体を概ね`O(n log n)`へ抑える。256 noise surfaceを含む回帰testは5秒未満で完了する。
- PowerShell 5.1 mockは、恒久25 cell、全cellが1 entryのplacement 25 Action、3-2-1仮足場6 block、高所3段のhorizontal reorientation、各配置前のfaceとpost-face current-revision exact support、上記順序のcleanup、各downward navigation時に残存足場と地上だけを使うBFS、25-cell oracle、source観測1回・再観測0回、合計99 Actionを固定する。これはrunner契約の確認であり、実ワールド成功を意味しない。

## 自動テスト事実

remote Dockerで次のfocused集合を実行し、**156 / 156件成功**を確認した。

```bash
./gradlew test \
  --tests dev.aod.mcmcp.agent.navigation.NavigationDistanceBudgetContractTest \
  --tests dev.aod.mcmcp.agent.navigation.KnownTraversabilityNavigationTest \
  --tests dev.aod.mcmcp.agent.observation.DeliveredPolicyEvidenceStoreTest \
  --tests dev.aod.mcmcp.agent.observation.ObservationModelContractTest \
  --tests dev.aod.mcmcp.agent.dsl.ActionDslTest \
  --tests dev.aod.mcmcp.agent.action.AgentPrimitivePlannerTest \
  --tests dev.aod.mcmcp.runtime.McmcpRuntimeConstructionTest \
  --tests dev.aod.mcmcp.mcp.McpToolCatalogTest
```

同じremote環境で次も成功した。

```bash
./gradlew check
```

`check`は通常unit testに加え、`verifyHarnessIsolation`、`harnessTest`、`adminBridgeTest`を依存taskとして持つ。さらにr6のP0修正後、`AgentPrimitivePlannerTest`、`McmcpRuntimeMutationAimTest`、`MinecraftPhaseFiveInventoryPortTest`、`KnownContainerAttemptTest`のfocused suite、`./gradlew check`、[`Test-McmcpConstructionCapabilityGate.ps1`](../../../tools/eval/Test-McmcpConstructionCapabilityGate.ps1)と[`Test-McmcpHardBuildingGateWorldReset.ps1`](../../../tools/eval/Test-McmcpHardBuildingGateWorldReset.ps1)のrunner/reset mockもすべて成功した。P0後focused suiteの総件数は記録にないため、推測して補完しない。

local r8準備時点の最新版では`gradlew clean check` **983 / 983件**、PowerShell construction mock、local world reset contract、`git diff --check`が成功した。製品jarはSHA-256 `1261dcb2484f69ddb0a55da17f19ff1a82c853738dce762e43a262aecd1608f5`で、物理Prism profileへのcopy後hashも一致した。r10後の変更はrunnerとmockだけであり、PowerShell construction mockと`git diff --check`を再実行して成功している。

Gate B最終版v10/v11では、remote JDK 25 Dockerでconstruction、collect replan、pillar ref、smelt / brew aimを含むfocused 11 class **194 / 194件**、`./gradlew check` **943 / 943件**、remote Windows PowerShell 5.1 runner mockに成功した。built / installed jarのSHA-256は`ccf0fd9adce31f553a91e7665f830f52f4990778b5561bc08bc920308496da16`で一致した。tracked-only source archive v11は503件、SHA-256 `6ef38e06519b1ace1fc02445f7ec14bea4ea7e1ce73e7fb5754fe155b11d63cc`である。

文書・catalogを含む終了時の最終同期v5では、tracked-only 503件、archive SHA-256 `e34744610b69c6d1757cfb7c55491fa1e22b9783274ffe5ff4a24c7cc253a9c6`をremoteへ展開し、試験前後ともlocal / remoteのpath・content manifest一致を確認した。catalog raw SHA-256は`4d13589339212fe36e84acf97c9cc8aba5c5ef27a871fb03ce5602257877ddbc`、semantic tool surface SHA-256は`728cf22ecd1f1eb3e023644bc52a3d6ed00e2bb41e37671b74579d53889745ec`で、固定5 Toolのparse、focused **235 / 235件**、PowerShell 5.1 construction mock、PowerShell 7 EvalTrace self-test **63 / 63件**、`./gradlew check` **976 / 976件**（main 943、harness 12、admin 21）、buildにすべて成功した。built / installed jarはSHA-256 `e5f888feb70e7a9bb97b54eeffd7bad88a67fb5e97aab7427d3098b5ed0c1ffe`で一致する。証拠正本は`F:\mcmcp-testlab\20260902-hard-building-v1\eval-artifacts\20260903-final-verification\final-summary.json`である。

r15後の修正では、staircase探索の辞書index・4方向走査と256 noise surface回帰をPowerShell 5.1 mockで検証し、回帰caseは5秒未満で完了した。coreの固定20 tick replan reserveを含むJava testは**986 / 986件成功**し、この実装をr16以降の実ワールドrunへ投入した。r25 / r27へ投入した版でもPowerShell 5.1 construction mockと`gradlew clean build`は成功した。r27後のレビューでは、`TARGET_UNKNOWN`後に拒否前のtraversabilityを再利用し得る点、cleanup回収時のterminal台帳、wall範囲外へ跳ねたdropの探索範囲を追加修正し、local / remote PowerShell 5.1 mockとclean buildを再度通した。このレビュー後差分は次の実ワールドgateで再検証する。

## 製品失敗ではない準備・infrastructure障害

準備runで発生した以下の停止は、Action開始前、またはrunnerが公開APIを誤用した後の安全な受付拒否・混雑拒否である。world変更を伴ったrunもfresh baselineへ戻してから最終runを実施し、3 gateの製品合否へ算入しない。

| 事象 | 分類と扱い |
|---|---|
| bind mount上の`chmod`失敗 | host filesystem/bind mountの権限モデルによる環境失敗。Minecraft Actionの成否ではない。bindを緩めて回避せず、実行環境側の所有権・mount方法を直す |
| reduced JDKで`jdk.attach`がない | build用JDK imageの不足。Gradle/test起動失敗であり製品runtime失敗ではない。`jdk.attach`を含むJDKで再実行する |
| Windows PowerShell 5.1で`ConvertFrom-Json -Depth`が未対応 | reset scriptのhost互換性不良。reset側は`-Depth`を使わないparserへ修正し、AST testで再混入を拒否する |
| PowerShell 5.1で空結果の`.Count`が得られない | 空collectionが`$null`になるhost差。`@(Get-ChildItem ...).Count`へ修正し、static testで固定する |
| PowerShell 5.1でUTF-8 no-BOMの日本語入りEvalTrace self-testを直接実行するとparser error | ANSI誤読による任意の補強試行のhost差。`optional-ps5-encoding-*`へ分離した。必須のPowerShell 5.1 construction mockとPowerShell 7 EvalTrace 63 / 63は成功しており、製品合否には算入しない |
| `wall-5x5` r11でPowerShell 5.1に`Invoke-RestMethod -NoProxy` / `-ResponseHeadersVariable`がない | live HTTP runnerのhost互換性不良。MCP Action開始前に停止しworld変更0。proxy無効の`HttpClient`と明示的なresponse検証へ置換した |
| HUDをクリックしようとしてr1 readiness失敗 | HUD layerは表示専用で、mouse inputを受けるのはScreen上のbuttonである。r1は`control.mode=ready`前に停止したため、navigation/place/refの製品試験は未実施 |
| navigation r2のrecord acceptance不一致 | runnerが`CONFIRMED`だけを許可した一方、配達recordはほぼ`PROBE_ALLOWED`だった。Action開始前に停止しworld変更は0。runnerを`PROBE_ALLOWED`も受理するよう直してr3をfresh baselineから実行した |
| faces-place r1-r3の接近契約不一致 | r1は遮蔽されたchestを初期視界だけで要求、r2/r3は既知経路のない遠いsurfaceへ直接approachして受付拒否された。block/container変更前に停止した |
| faces-place r4のpagination lease枯渇 | 短距離navigation 2回の後、未完了pageを2枠保持したため3回目の観測が`SERVER_BUSY`になった。traversabilityを近傍へfilterして1 pageで完了するようrunnerを直した |
| faces-place r5の遠距離support approach | navigation 3回、chest approach、oak log取得は成功したが、遠いsupportへ直接approachして`NO_KNOWN_PATH`で受付拒否された。取得によるcontainer/inventory変更があったためSave and Quit後にfresh baselineへ復元し、距離4 m外では観測済み短距離navigationを使うようrunnerを直した |

infrastructureを直した後も、同じbaseline、公開5 Tool、fresh evidence、同じ合格条件で再現した失敗だけを製品失敗として扱う。

## 再現手順

gateごとに独立して次を行う。`<repo>`、`<artifact-dir>`、`<token-path>`はremote host上の絶対pathを記録し、token本文はartifactへ保存しない。

1. Docker containerとworldが完全停止していることを確認する。
2. remote hostのWindows PowerShell 5.1でbaselineを復元する。

   ```powershell
   Set-Location <repo>
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\eval\Reset-McmcpHardBuildingGateWorld.ps1 -Gate navigation
   ```

3. containerを開始し、world load後に表示専用HUDではなくScreen buttonからMCP操作を`READY`へする。開始方法は現時点のrepo scriptへ閉じていないため、実行したcontainer start commandを次表へ記録する。
4. reset時と同じGate名でpublic gateを実行する。

   ```powershell
   # Windows PowerShell 5.1対応。内部HttpClientでproxyを使わずresponseを検証する。
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\eval\Invoke-McmcpConstructionCapabilityGate.ps1 `
     -Gate navigation -ArtifactDirectory <artifact-dir> -TokenPath <token-path>
   ```

5. Save and Quit後にcontainerを停止し、reset receipt、`gate-result.json`、`gate-events.jsonl`を保存する。
6. worldが閉じている状態で[`Inspect-McmcpRegion.py`](../../../tools/eval/Inspect-McmcpRegion.py)を使い、source/destination/work-areaをbaselineと比較する。`faces-place`と`state-ref-ttl`ではmanifestの`exact_changed_position`だけが期待stateへ変化し、sourceと他destinationは不変でなければならない。
7. `faces-place`と`state-ref-ttl`も、それぞれstep 1から`-Gate`値だけを一致させて繰り返す。前gate後のworldを使い回さない。

## 3 gate結果

artifact、baseline receipt、offline oracleが揃ったrunだけを最終判定した。

| Gate | reset receipt / commit | public gate artifact | public結果 | offline oracle | 最終判定・次の一手 |
|---|---|---|---|---|---|
| `navigation` r3 | `20260902T202036197Z-navigation-4ced30ea39a74520972c8cbba391fbb5` | `F:\mcmcp-testlab\20260902-hard-building-v1\eval-artifacts\20260903-navigation-r3` | PASS。target `(-15,56,-11)`、1 Action / 8 Tool calls / 3.87 s | semantic diff 0 | **PASS**。route / budgetの短距離契約を実ワールドで確認 |
| `faces-place` r7 | `20260902T213332561Z-faces-place-065447bf1b224bd593d5bdaf77c8c7de`、jar `c562f2ef...519e` | `F:\mcmcp-testlab\20260902-hard-building-v1\eval-artifacts\20260903-faces-place-r7` | PASS。21 Actions / 107 Tool calls / 31.53 s。oak log 64個を取得し、`(-18,56,9)`へ1個設置。inventory 64→63 | target 1 cellだけ`air`→`oak_log[axis=y]`、その他差分0 | **PASS**。r6のP0修正後に再現条件を完走 |
| `state-ref-ttl` r1 | `20260902T213845555Z-state-ref-ttl-a0a94585d792472a94a9b6fdb378a367`、jar `c562f2ef...519e` | `F:\mcmcp-testlab\20260902-hard-building-v1\eval-artifacts\20260903-state-ref-ttl-r1` | PASS。21 Actions / 108 Tool calls / 96.504 s。material acquisitionを別証拠化し、source identity配達1回、待機65.002 s、source再観測0回で同じ1個設置。inventory 64→63 | target 1 cellだけ`air`→`oak_log[axis=y]`、その他差分0 | **PASS**。surface TTLとsession ref lifetimeの分離を確認 |

補足: `faces-place` r6は10 Action中9成功後、`take`が401 tickで`container_deadline`となった製品FAILである。interaction 0、block oracle diff 0であり、上記P0修正の原因runとしてartifact `F:\mcmcp-testlab\20260902-hard-building-v1\eval-artifacts\20260903-faces-place-r6`を保持する。準備runner r1-r5は前節のとおりrunnerの観測・接近契約不一致であり、Actionを実行したrunもfresh baselineへ戻して最終runとは分離したため、上表へは合否を記録しない。

全試験後は再びfresh baselineへ復元し、receipt `20260902T214600287Z-state-ref-ttl-792870ad85ca46e09c7e82d617b8fa00`を保存してcontainerを停止した。

## Gate B `wall-3x3`結果

全runで同じ停止済みbaselineを復元し、施工前後に`x=-32..0, y=52..70, z=-16..24`の25,707 cellを取得した。不合格でもSave and Quit、offline oracle、fresh reset、container 0まで完了している。

| Run | 到達点 | 最初の問題 | 判定と修正 |
|---|---|---|---|
| r1 | 0/9、19/19 Action成功、29.857 s | runnerが製品reach 4.5に対し4.0を固定 | FAIL。製品契約4.5へ統一 |
| r2 | 0/9、19/19 Action成功、29.828 s | 3-entry row開始headingが40°条件外 | FAIL。配達supportへのface Actionを先行 |
| r3 | 6/9、23/23 Action成功、33.208 s | 低いeyeからrow 1のUP面が見えず最上段support 0件 | FAIL。観測由来の1 block仮足場を追加 |
| r4 | 6/9、23/23 Action成功、34.175 s | 同一targetへ収束する複数traversability edgeをrunnerが重複異常扱い | FAIL。`CONFIRMED`を優先しrecord targetを無変換保持 |
| r5 | 9/9、仮足場撤去、35 Action中34成功、49.333 s | 動くdropのcollectが即`PATH_BLOCKED`、inventory -10、drop 1残留 | FAIL。入力解放付きbounded replanと観測前40 tick settleを追加 |
| r6 | 9/9、全35 Action成功、53.039 s | entity filterは`floor(position)`契約なのにclientだけ連続値比較し、境界を0.07 block越えたdropを誤除外 | FAIL。client再検証をfloor契約へ一致 |
| r7 | 9/9、36/36 Action成功、159 Tool、53.164 s | なし | **PASS**。inventory 64→55、unexpected block diff 0、source不変、仮足場air復元 |
| r8 | 9/9、36/36 Action成功、159 Tool、53.197 s | なし | **PASS**。r7とbefore / after / manifest SHAも一致 |

r7のdrop targetは`(-20.350639770968613, 56.0, 8.882561189427028)`、collectは12 tickでinventory 54→55をserver-confirmした。r8は`(-20.053168787316206, 56.0, 9.736213217184437)`を8 tickで回収した。両runともrow 0 / 1を3 entryずつ、row 2を奥→手前の単独3 Actionで置き、descent target `(-20,56,12)`、40 tick settle、source観測1回・再観測0回で一致した。

artifact rootはそれぞれ`F:\mcmcp-testlab\20260902-hard-building-v1\eval-artifacts\20260903-wall-3x3-r1`から`r8`である。最終fresh resetはr7が`20260903T061954565Z-wall-3x3-401d145fdf6f4464ba4726d4ce7fc58f`、r8が`20260903T063425599Z-wall-3x3-e3e132f74e36456f8a345e7485514497`で、いずれもcontainer 0を確認した。resource-load停滞、stale X99 lock、PS5.1の`-NoProxy`非対応試行はrunner / HTTP / Action開始前でworld変更0のinfrastructure事象として製品判定から分離した。

## Gate B `wall-5x5` remote r11-r15結果

r11以降は`aod-mimoid`のDocker環境へ戻し、各runをfresh baselineから開始した。r11はAction開始前に終了し、r12-r15は不合格でも公開input解放、Save and Quit、container停止、offline oracleを行い、次runへworld変更を持ち越していない。

| Run | 到達点 | 最初の問題 | offline oracle・判定と修正 |
|---|---|---|---|
| r11 | Action開始前 | Windows PowerShell 5.1に`Invoke-RestMethod -NoProxy` / `-ResponseHeadersVariable`がなくlive HTTP runnerが停止 | world変更0。製品FAILには算入しない。`HttpClient(UseProxy=false)`とstatus / content type / JSON検証へ置換 |
| r12 | 恒久20 / 25、一時2、66 / 66 Action terminal、127.118 s | 旧vertical pillar上では低いeyeから最上段supportのUP面を配達できず、current revision待ちが終了 | 差分は恒久oak log 20＋一時oak log 2だけでsource / extras変更0。施工視認と降下経路を両立する3-2-1 staircaseへ変更 |
| r13 | block mutation前、16 / 16 Action terminal | 接近候補を移動距離最小で選び、短い移動を繰り返した結果、bounded approach上限内にwhite-woolへ到達できなかった | before / after SHA一致、差分0。`goal_distance`最小の最大進捗優先へ変更 |
| r14 | r13の接近停止点を進み、材料chestへの5回目の接近まで到達。5 / 5 Action terminal | navigation中の`route_edge_changed`後、tick 7で`BUDGET_EXCEEDED`。block mutation前に停止 | 差分0、input release PASS。coreへ固定20 tickのreplan reserveを追加 |
| r15 | r14の停止点を越え、下2段10 / 25を通常player Actionで設置。inventory 64→54 | `Select-TemporaryStaircasePlan`がnoise surfaceを含む候補へ`O(S^3 + ...)`相当の探索を行い、約64.5秒後に施工証拠が`FRAME_EXPIRED` | 差分は恒久oak log 10だけ。一時block、extras、source変更0、input release PASS。辞書index＋4方向走査の概ね`O(n log n)`へ置換 |

r15は通常player Actionによる下2段施工の成立を示したが、この時点では仮足場を1個も設置しておらず、3-2-1 staircase、最上段施工、6 block cleanup、drop回収は未実証だった。これらは次節のr16-r27で段階的に実行し、r25 / r27で一式を完走した。mockの99 Actionはliveの固定期待値ではない。

artifact directoryは`F:\mcmcp-testlab\20260902-hard-building-v1\eval-artifacts\20260904-wall-5x5-r11`から`r15`へ保存した。r11はlive HTTP開始前に停止したため`gate-result.json`を生成していない。

## Gate B `wall-5x5` remote r16-r27結果

r16-r27も`aod-mimoid`の停止済みDocker worldを毎回fresh baselineへ復元して実施した。表のAction数は`action_accepted / action_terminal`であり、全runで両者が一致した。途中のActionが安全budgetで失敗しても、runnerがfresh evidenceから別Actionへresliceして最終目的とoracleを満たせばgate全体は合格し得る。一方、accepted Actionが1件でもnon-terminalのままなら、施工結果にかかわらず不合格とする。

| Run | 到達点・Action | 最初の問題 | offline oracle・判定と修正 |
|---|---|---|---|
| r16 | 恒久10 / 25、一時0、36 / 36 terminal | 要求stepに一致する一意な配達済み足場navigation targetを選べなかった | 恒久10 cellだけ変更、extras 0、source不変。fresh Action sliceごとのtarget再選択へ修正 |
| r17 | 恒久10 / 25、一時1、38 / 38 terminal | `abs(dy) <= 1`を満たす配達済み隣接stepがなかった | 恒久10＋一時1、extras 0、source不変。残存足場を含む現在の移動可能性を再取得するよう修正 |
| r18 | 恒久10 / 25、一時1、39 / 39 terminal | 足場navigation Actionが`BUDGET_EXCEEDED` | 恒久10＋一時1、extras 0、source不変。navigationをfreshなbounded sliceへ分割 |
| r19 | 恒久10 / 25、一時3、44 / 44 terminal | 次の足場navigation Actionが`BUDGET_EXCEEDED` | 恒久10＋一時3、extras 0、source不変。各stepの現在poseと配達recordから再slice |
| r20 | 恒久10 / 25、一時6、47 / 47 terminal | oak-log surfaceが40 poll内にcurrent world revisionへ追いつかなかった | 恒久10＋一時6、extras 0、source不変。全face候補を取得し、足場上面のUP faceを優先するreorientationへ修正 |
| r21 | 恒久25 / 25、一時6を設置後、最初の1 blockを撤去。88 / 88 terminal | inventoryへ受動回収済みなのに、可視drop 1件だけを必須として0件を失敗扱いした | 恒久25＋残存足場5、extras 0、source不変。inventory `+1` / drop 0をpassive pickupとして証明するよう修正 |
| r22 | 恒久25 / 25、足場3 / 6撤去、96 / 96 terminal | visible dropはあったが、`collect_visible_item`が安全pickup cell不明の`TARGET_UNKNOWN`で受付拒否 | 恒久25＋残存足場3、extras 0、source不変。dropと独立にcurrent pickup traversabilityを配達するよう修正 |
| r23 | 恒久25 / 25、最初の1 blockを撤去、87 / 87 terminal | 最初のpost-settle観測がinventory差0 / drop 0で、即座に矛盾扱いした | 恒久25＋残存足場5、extras 0、source不変。異なるfresh frameを待つbounded semantic waitへ修正 |
| r24 | 恒久25 / 25、最初の1 blockを撤去、88 / 88 terminal | 40 poll / 28 observed frameでもinventory差0 / drop 0のまま証拠が得られなかった | r23と同じ恒久25＋残存足場5、extras 0、source不変。遮蔽時はcurrent safe traversabilityへ接近して全証拠を再取得するよう修正 |
| r25 | 恒久25 / 25、足場6 / 6撤去・回収、110 / 110 terminal（108 succeeded / 2 failed） | なし | **PASS**。passive pickup 5、active collect 1。変更は恒久25 cellだけ、仮設残0、extras 0、source不変 |
| r26 | 恒久25 / 25、最初のclear / settle後、87 / 87 terminal（85 succeeded / 2 failed） | active collect受付が`TARGET_UNKNOWN`。拒否Actionはaccepted数へ入らない | **FAIL**。`hard-area-after.json`とexternal oracle manifestを取得していないため、最終MCA差分は証明しない。受付拒否時もboundedに接近・再観測・再受付するよう修正 |
| r27 | 恒久25 / 25、足場6 / 6撤去・回収、110 / 110 terminal（108 succeeded / 2 failed） | なし | **PASS**。passive pickup 4、active collect 2。変更は恒久25 cellだけ、仮設残0、extras 0、source不変 |

r25 / r27のbefore SHA-256は同じ`C7DC87113500E74A4BF9635516D63EE897C519A961E44C3ADB1380E89D3A793A`、after SHA-256も同じ`8FFFC194ED36D1A67B302E9405B082F6B78F813F5D0E9459DEB5E0BFDD6A7B69`である。両runはmanifestが期待する恒久25 cellと独立MCA差分25が一致し、一時6 cellはairへ戻った。全r16-r27で終了時`control_ready=true`、`all_actions_terminal=true`を確認し、一時token pathも残していない。artifact directoryは`F:\mcmcp-testlab\20260902-hard-building-v1\eval-artifacts\20260904-wall-5x5-r16`から`r27`である。

## Gate B `wall-5x5` remote r28-r30追加結果

r27後のレビュー修正をfresh baselineから3回追加検証した。3 runとも恒久壁25 / 25を通常player Actionで施工し、`action_accepted == action_terminal`、終了時`control_ready=true`を保ったが、後処理のfalse negativeを順に露出したためgate判定はFAILのまま保持する。

| Run | 到達点・結果 | 根因 | 採用修正・証跡 |
|---|---|---|---|
| r28 | 恒久25 / 25、足場1 / 6撤去、90 / 90 terminal（87 succeeded / 3 failed）。offlineは恒久25＋残存足場5、extras 0 | 高さ58の足場から落ちたitemが地上Y=56まで達した一方、drop filterが撤去位置近傍だけを見てY=57未満を除外した。itemは存在するのにinventory差0 / drop 0と判定した探索範囲外false negative | 撤去位置からcleanup groundまでのbounded swept volumeと水平±2を`position_bounds`にし、3×3 / 5×5ごとの下限Yを明示した。証跡は`F:\mcmcp-testlab\20260902-hard-building-v1\eval-artifacts\20260904-wall-5x5-r28`（before `C7DC8711...793A`、after `68D4D185...288B`） |
| r29 | 恒久25 / 25、足場2 / 6撤去、92 / 92 terminal（89 succeeded / 3 failed）。cleanup #2の接近Actionは1.042 block進んで`BUDGET_EXCEEDED`となったが、その途中でitemを回収済み | recovery用navigationが失敗terminal後も旧exact targetの再配達を待った。playerがすでにitemとtarget付近を横切ったため同targetは40 pollで現れず、material ledgerを再確認する前に`no delivered scaffold navigation target...`としたfalse negative | recovery接近だけはreslice可能な失敗1回でfresh stateを返し、旧targetを待たずinventory / drop台帳へ戻す。通常navigationのreslice契約は変えない。証跡は`F:\mcmcp-testlab\20260902-hard-building-v1\eval-artifacts\20260904-wall-5x5-r29`（before `C7DC8711...793A`、after `04E4827F...1120`） |
| r30 | 恒久25 / 25、足場6 / 6を全撤去、111 / 111 terminal（107 succeeded / 4 failed）。offline afterはr25 / r27と同じ`8FFFC194...7B69`で恒久25 cellだけが残った。終了時はinventory 38＋最後の可視drop 1＋恒久25＝施工前64個を保存 | `Get-RecordsFromState`は各cursorを`next_cursor=null`まで完走していたが、serverの`ObservationFrameStore`が最終page後もleaseを60秒保持した。cleanup #2の全域traversabilityとcleanup #3の295件drop周辺queryで2枠が残り、cleanup #6の3件目が`SERVER_BUSY`になった | 最終pageでleaseをcompletedとしてactive 2枠から即時除外し、同一cursorのpage replayと既存expiryは維持する。上限増加や60秒sleepでは回避しない。証跡は`F:\mcmcp-testlab\20260902-hard-building-v1\eval-artifacts\20260904-wall-5x5-r30`（before `C7DC8711...793A`、after `8FFFC194...7B69`） |

r28-r30はいずれもworld変更そのものではなく、観測後処理が実際より厳しく失敗したrunである。r30はblock oracleと64個のmaterial conservationまで成立したが、最後のdropをinventoryへ戻す前にpaginationが停止したため完全PASSへ昇格しない。次runでは、completed lease後の新規paginationとcursor replayのcore回帰を通したJARを使い、同じbaselineから6回の撤去・6個のinventory回収まで再確認する。

## Gate B `wall-5x5`再現・追加実施手順（既存remote環境の例）

状態は**実worldで2回完全合格済み**である。以下はr25 / r27を再現する手順であり、mockの99 Actionをliveの固定値にはしない。実際のAction数は観測由来の接近・reslice・drop回収方式によって変わる。また、以下は既存`aod-mimoid`環境を再利用する場合の例であり、次回の実world検証hostを固定しない。実施時はその時点のユーザー指定hostに従い、local PCの場合はlocal用reset / instance pathへ読み替える。

1. container 0を確認し、remote Windows PowerShell 5.1でfresh baselineを復元する。出力されたreceipt path / operation idをartifactへ控える。

   ```powershell
   Set-Location <repo>
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\eval\Reset-McmcpHardBuildingGateWorld.ps1 -Gate wall-5x5
   ```

2. worldが閉じている間に、3×3と同じ監査領域`x=-32..0, y=52..70, z=-16..24`のbefore snapshotを取得する。`<closed-overworld-dir>`はreceiptの`restored_world`に対応するoverworld dimension directoryである。

   ```powershell
   $artifact = 'F:\mcmcp-testlab\20260902-hard-building-v1\eval-artifacts\20260904-wall-5x5-r<run>'
   New-Item -ItemType Directory -Force -Path $artifact | Out-Null
   python .\tools\eval\Inspect-McmcpRegion.py <closed-overworld-dir> -32 0 52 70 -16 24 --output "$artifact\before-region.json"
   ```

3. containerを開始してworld loadを待ち、Screen buttonでMCPを`READY`にする。公開5 Toolだけを使うrunnerを実行する。

   ```powershell
   # Windows PowerShell 5.1対応。内部HttpClientでproxyを使わずresponseを検証する。
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\eval\Invoke-McmcpConstructionCapabilityGate.ps1 `
     -Gate wall-5x5 -ArtifactDirectory $artifact -TokenPath <token-path>
   ```

4. `gate-result.json`でsource観測1 / 再観測0、5行 / 25 unique target、wall placement 25 Action、各cell直前のface / fresh support、3-2-1仮足場6 block、6回のsettle / drop回収、inventory delta `-25`（64→39）を確認する。`action_accepted`数と`action_terminal`数が一致し、未終端Actionが0、最終`control.mode=ready`でなければ不合格とする。mockは99 Action、r25 / r27のlive実測は110 Actionだったが、合計Action数そのものは固定合格条件にしない。
5. Save and Quitしてcontainer 0を確認後、同じ範囲を`after-region.json`へ取得する。`external-oracle-manifest.json`の25 permanent cellだけが`air`→`minecraft:oak_log[axis=y]`、source不変、仮足場6 cellがbefore / afterともair、未列挙変更0であることを比較する。どれか1条件でも欠けたrunはPASSにしない。
6. 合格してもfresh baselineへ復元してから独立runをもう1回行う。5×5ではr25 / r27の2回についてartifact、receipt、before / after、oracle結果が揃い、この安定合格条件を満たした。

## 合格条件と次修正方針

- 全gate共通: 固定5 Toolのみ、通常player Actionのみ、`action_accepted == action_terminal`かつ未終端Action 0、終了時`control.mode=ready`、source保全、想定外world mutationなし。cleanup/input解放不成立またはsource/領域外変更は安全P0として後続gateを止める。
- `navigation`: freshに配達されたtargetを無変換で受付・完了し、最終feet cellが一致し、world mutationが0。ここで`PROGRAM_BUDGET_UNPROVABLE`や`NO_KNOWN_PATH`が再現した場合だけroute/budget contractを再調査する。
- `faces-place`: `faces=["up"]`がUP面だけを返し、inline identityでexact stateを1個設置し、inventoryが正確に1減り、offlineでもtarget 1 cellだけが変化する。失敗時はfilter、support/aim、placement/server ACKのどこまで成功したかをeventとAction failureで分離する。
- `state-ref-ttl`: source観測1回、61秒以上、同一world session、source再観測0回でref設置が成功し、`faces-place`と同じinventory/oracle条件を満たす。失敗時はresponse delivery confirmation、session clear、eviction、compiler budget、planner/runtime二重解決を順に確認する。
- readiness、reset、build、JDK、PowerShell、bind、artifact不足で止まった場合は製品コードを修正せず、同じbaselineで環境を直して再実行する。
- 3 gate、Gate B 3×3、Gate B 5×5は合格した。5×5はr25 / r27で材料会計、3-2-1足場の設置、安全な降下、6回の撤去・回収、accepted / terminal一致、offline oracleを2回確認した。r26は追加のdrop位置で`TARGET_UNKNOWN`を再現し、after未取得のため不合格として保持した。3×3 / 5×5 PASSは任意規模construction jobや方向性blockを保証しない。
- smelt / brewも、container / craftと同じく配達済みvisible ray hitをplannerから各portの全open / reopenまで保持するよう修正し、remote focused testまで完了した。block center固定経路は削除したが、実ワールドのstation gateは未実施である。
- aim待機のno-progress診断には、stage、検証済みaim point、現在raycast target/face、interaction送信回数を残す。安全条件やtimeoutは緩めず、`container_deadline`だけでは失われていた停止位置を観測可能にする。
- `pillar_up_known(placement_state_ref)`はGate B 5×5 r25 / r27で、3-2-1形状の6 block足場の設置・上昇・安全な降下・全撤去・全回収まで実ワールド合格した。ただしladder / scaffolding新設、sneak bridge、任意形状・任意規模の複数足場計画は未保証である。
- 次の対象は90分のfull hard-building再試験とstation系の実ワールド照準確認である。5×5合格はその前提gateを満たすが、方向性blockや任意規模construction jobの成功を先取りして主張しない。
