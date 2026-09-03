# Construction capability gate準備・実行記録（2026-09-03）

## 状態と目的

本書は、[高難易度建築レビュー](./2026-09-03_hard-building-review-and-roadmap.md)後のP0修正を、90分の建築再試験より先に3つの短い実ワールドgateで分離検証するための記録である。対象は`ssh aod-mimoid`上のDocker環境であり、ローカルPCでは実ワールド操作しない。

remoteでは`navigation` r3、`faces-place` r7、`state-ref-ttl` r1の3 gateに加え、Gate Bの`wall-3x3` r7 / r8がfresh baselineから2回連続で完全合格した。3×3壁は通常player Actionだけで9 blockを設置し、観測由来の仮足場を設置・撤去・回収した後、offline oracleでも恒久9 cell以外の変更0を確認した。`wall-3x3` r1-r6は製品とrunnerの境界不良を一つずつ分離した不合格runとして残す。5×5 Gate Bと90分の高難易度建築再試験はまだ実施していない。

## 今回の変更

### route / budget contract

- `NavigationDistanceBudget`へ公開上限32 blocks、trajectory係数1.5、垂直edge余裕1.5、開始pose reserveを集約した。
- A*、`RoutePlan`、planner、executorが同じcost式と上限を使う。A*が返した経路をplannerが公開最大budgetで拒否する旧契約差をなくす。
- `NavigationDistanceBudgetContractTest`は境界poseとfresh公開target、`KnownTraversabilityNavigationTest`は更新後の探索境界を固定する。

### `placement_state_ref`

- 成功配達されたpolicy-visibleな`visible_surface.state + placement_item`だけから、推測困難なsession-local refを発行する。hidden worldは読まない。
- refは成功したresponse書き込み後のconfirmation完了までは解決できない。最大512 identityを古い順にevictし、座標surfaceの60秒TTLとは独立してworld session終了まで保持する。
- `apply_known_block_plan` entryは`placement_state_ref`または`source_state + item`のexact one-ofである。refは見本座標の再訪だけを不要にし、target、support、pose、revision、JIT、server ACK、`SafeConstructionBlockPolicy`は緩めない。
- planner受付時とconstruction実行直前にrefを二重解決する。compilerは有効refをordinary=1 / oak-door=2 cellsとして計上し、未知refは安全側の2 cellsで過小評価を防ぐ。
- 当該3 gate実施時点のref対応は`apply_known_block_plan`のみで、`pillar_up_known`は従来のinline identityだった（gate後に同じref storeを再利用する対応を追加）。

### remote gate / reset

- [`Invoke-McmcpConstructionCapabilityGate.ps1`](../../../tools/eval/Invoke-McmcpConstructionCapabilityGate.ps1)は公開5 Toolだけを許可し、長pollでAction terminalを待ち、終了時にAction terminalと`control.mode=ready`を再確認する。
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
- 撤去前に近傍oak-log dropが0、撤去後は40 tickの入力なしwaitを挟み、最初のpost-settle frameでexact 1件を要求する。`visible_entity.position_bounds`のclient側検証もAPIどおり`floor(position)`で行うが、collectへ渡す連続XYZは丸めない。
- 動くdropが旧pickup cellを外れた場合、runtimeは即`PATH_BLOCKED`にせず入力を解放し、同じ`displayed_item`かつ提出位置から0.75 block以内のfresh witness、既知安全pickup cell / route、実AABBを有効なoccurrence / Action期限内で再証明する。公開recordにentity UUIDはなく個体同一性は主張しない。期限は再失効で延長しない。
- gate eventはAction受付時のbody / budget、terminalのprogress / failure / trace、descent target、settle、post-settle drop座標を保存する。失敗runでも外側の`PATH_BLOCKED`だけで推測しない。

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

Gate B最終版v10/v11では、remote JDK 25 Dockerでconstruction、collect replan、pillar ref、smelt / brew aimを含むfocused 11 class **194 / 194件**、`./gradlew check` **943 / 943件**、remote Windows PowerShell 5.1 runner mockに成功した。built / installed jarのSHA-256は`ccf0fd9adce31f553a91e7665f830f52f4990778b5561bc08bc920308496da16`で一致した。tracked-only source archive v11は503件、SHA-256 `6ef38e06519b1ace1fc02445f7ec14bea4ea7e1ce73e7fb5754fe155b11d63cc`である。

文書・catalogを含む終了時の最終同期v5では、tracked-only 503件、archive SHA-256 `e34744610b69c6d1757cfb7c55491fa1e22b9783274ffe5ff4a24c7cc253a9c6`をremoteへ展開し、試験前後ともlocal / remoteのpath・content manifest一致を確認した。catalog raw SHA-256は`4d13589339212fe36e84acf97c9cc8aba5c5ef27a871fb03ce5602257877ddbc`、semantic tool surface SHA-256は`728cf22ecd1f1eb3e023644bc52a3d6ed00e2bb41e37671b74579d53889745ec`で、固定5 Toolのparse、focused **235 / 235件**、PowerShell 5.1 construction mock、PowerShell 7 EvalTrace self-test **63 / 63件**、`./gradlew check` **976 / 976件**（main 943、harness 12、admin 21）、buildにすべて成功した。built / installed jarはSHA-256 `e5f888feb70e7a9bb97b54eeffd7bad88a67fb5e97aab7427d3098b5ed0c1ffe`で一致する。証拠正本は`F:\mcmcp-testlab\20260902-hard-building-v1\eval-artifacts\20260903-final-verification\final-summary.json`である。

## 製品失敗ではない準備・infrastructure障害

準備runで発生した以下の停止は、Action開始前、またはrunnerが公開APIを誤用した後の安全な受付拒否・混雑拒否である。world変更を伴ったrunもfresh baselineへ戻してから最終runを実施し、3 gateの製品合否へ算入しない。

| 事象 | 分類と扱い |
|---|---|
| bind mount上の`chmod`失敗 | host filesystem/bind mountの権限モデルによる環境失敗。Minecraft Actionの成否ではない。bindを緩めて回避せず、実行環境側の所有権・mount方法を直す |
| reduced JDKで`jdk.attach`がない | build用JDK imageの不足。Gradle/test起動失敗であり製品runtime失敗ではない。`jdk.attach`を含むJDKで再実行する |
| Windows PowerShell 5.1で`ConvertFrom-Json -Depth`が未対応 | reset scriptのhost互換性不良。reset側は`-Depth`を使わないparserへ修正し、AST testで再混入を拒否する |
| PowerShell 5.1で空結果の`.Count`が得られない | 空collectionが`$null`になるhost差。`@(Get-ChildItem ...).Count`へ修正し、static testで固定する |
| PowerShell 5.1でUTF-8 no-BOMの日本語入りEvalTrace self-testを直接実行するとparser error | ANSI誤読による任意の補強試行のhost差。`optional-ps5-encoding-*`へ分離した。必須のPowerShell 5.1 construction mockとPowerShell 7 EvalTrace 63 / 63は成功しており、製品合否には算入しない |
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

## 合格条件と次修正方針

- 全gate共通: 固定5 Toolのみ、通常player Actionのみ、Action terminal、終了時`control.mode=ready`、source保全、想定外world mutationなし。cleanup/input解放不成立またはsource/領域外変更は安全P0として後続gateを止める。
- `navigation`: freshに配達されたtargetを無変換で受付・完了し、最終feet cellが一致し、world mutationが0。ここで`PROGRAM_BUDGET_UNPROVABLE`や`NO_KNOWN_PATH`が再現した場合だけroute/budget contractを再調査する。
- `faces-place`: `faces=["up"]`がUP面だけを返し、inline identityでexact stateを1個設置し、inventoryが正確に1減り、offlineでもtarget 1 cellだけが変化する。失敗時はfilter、support/aim、placement/server ACKのどこまで成功したかをeventとAction failureで分離する。
- `state-ref-ttl`: source観測1回、61秒以上、同一world session、source再観測0回でref設置が成功し、`faces-place`と同じinventory/oracle条件を満たす。失敗時はresponse delivery confirmation、session clear、eviction、compiler budget、planner/runtime二重解決を順に確認する。
- readiness、reset、build、JDK、PowerShell、bind、artifact不足で止まった場合は製品コードを修正せず、同じbaselineで環境を直して再実行する。
- 3 gateとGate B 3×3は合格した。次は5×5 full-cube wallでphase分割、材料会計、仮足場の複数回利用を確認し、その後にfull hard-buildingへ戻す。3×3 PASSは任意規模construction jobや方向性blockを保証しない。
- smelt / brewも、container / craftと同じく配達済みvisible ray hitをplannerから各portの全open / reopenまで保持するよう修正し、remote focused testまで完了した。block center固定経路は削除したが、実ワールドのstation gateは未実施である。
- aim待機のno-progress診断には、stage、検証済みaim point、現在raycast target/face、interaction送信回数を残す。安全条件やtimeoutは緩めず、`container_deadline`だけでは失われていた停止位置を観測可能にする。
- `pillar_up_known(placement_state_ref)`はGate B r7 / r8で、1回の仮足場設置、上昇、撤去、drop回収まで実ワールド合格した。ただし連続2 block以上のpillaring、ladder / scaffolding新設、sneak bridge、複数足場の計画は未保証である。
- 90分のfull hard-building再試験はまだ実施していない。Gate B 5×5とstation系の実ワールド照準確認後にfresh baselineから行う。
