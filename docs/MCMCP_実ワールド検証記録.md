# MCMCP 実ワールド検証記録

- 更新日: 2026-09-05
- 対象: Prism Launcherの単一検証profile、Minecraft 26.2 / NeoForge 26.2.0.59
- 状態: 木こりの最小gateは合格。畑の栽培・収穫ループはMCP操作だけでwheat 64個へ到達。fresh LLMによるchest取得からのend-to-endはR10で39 / 64、R11で26 / 64となり、最終合格は未達
- 最新実験ノート: [`experiments/02_wheet/2026-08-29_fresh-sol-high-mcp-only-r11-original-field.md`](experiments/02_wheet/2026-08-29_fresh-sol-high-mcp-only-r11-original-field.md)

## 完成目標と判定原則

完成目標は、LLMがMinecraftの画面操作を代行する`computer-use`を使わず、MCMCPが公開するMCP Toolだけで一連の作業を完遂できる状態である。

したがって、次はテスト準備または機能ギャップであり、MCPによる成功には数えない。

- chest GUIをマウスで開き、itemをhotbarへ移す
- fence gateをマウスで開閉する
- Screen上のMCP操作を手動でONへ戻す
- Minecraftの移動・視点・attack・useを外部のraw key / mouse操作で補う

最終的な合格判定では、観測、item取得、container操作、gate操作、移動、作業、成果確認をMCP呼出しだけで連続実行し、各操作をMCMCPの監査traceとworld / inventoryの事後条件で確認する。

## 2026-09-05 Phase 5 実ワールド回帰試験

`aod-mimoid`のDocker内Prism profileで、精錬、醸造、丸石生成、釣りを順に検証した。

| 対象 | 結果 | artifact / 所見 |
|---|---|---|
| 精錬 | 合格 | `20260905-3a57b18-smelt-r1` |
| 醸造 | 合格 | `20260905-3a57b18-brew-r1` |
| 丸石生成 | 合格 | `20260905-9ff0af0-cobble-r15`。8個を9試行で回収し、1 drop喪失をbounded retryで回復 |
| 釣り | online合格・offline継続 | `20260905-0a74cb6-fishing-r11`。自然なsplashを536 ticksで検知し、reel後4 poll以内にloot 1個を受動回収。3 / 3 Action terminal、入力解放を確認。Save and Quit後の独立oracleとfresh baselineからの2回目が残る |

Fishing r1〜r10では、旧audio-source hook、cast/wait/reelのbudget、source-waterのray witness、45秒の有限wait、reel直後のloot飛翔時間を順に分離した。r11はlevel sound event版で自然splashからloot取得まで初めてonline完走した。直前r10の「reel成功直後にはinventoryにも可視entityにもlootがない」というfalse negativeは、reel後最大2秒のbounded settleを入れて解消した。r11ではsettle 4 pollでinventoryへ直接入り、追加collectは不要だった。

r11 artifactには`gate-events.jsonl`、`gate-result.json`、`external-oracle-manifest.json`があるが、worldを閉じた後の`offline-fishing-oracle.json`は取得していない。そのためrod damage、残留bobber / item、pool全cell不変をonline結果だけで最終合格へ昇格させない。runnerはcast後の失敗・timeout・cancelでも保持中のsingle-use `fishing_session_ref`を一度だけreel cleanupし、bobber消失を確認してから入力解放する。通常reelとcleanup reelはいずれもconfirmed effectのbobber `true -> false`とrod damage `+1`を要求する。Vanilla treasureの`minecraft:enchanted_book`も正当なlootとして扱う。

丸石生成の上表artifactは、旧runnerが`break_known_block`を1回ずつ再観測して8個集めた記録である。後続実装に合わせ、`Invoke-McmcpCobblestoneGeneratorCapabilityGate.ps1`は現在、同じfixtureと公開5 Toolだけを使い、delivery-backed targetへの`face_known_position`を独立Actionで完了し、freshなtarget / state / faceを再観測してから、単独の`operate_known_cobblestone_generator` Action（絶対inventory目標8、`max_breaks=8`、再生成待ち100 ticks、運転上限3600 ticks）を開始する。両Actionのterminal、各8 cycleのconfirmed effect、generator Actionの移動・視点・interactionが0、最終inventory 8、pickaxe damage 8、入力解放を合格条件とする。runner mockは合格済みだが、この新Actionによる実ワールド再試験結果はまだ記録していない。

### fixture切替時の死亡事故と修正

Fishingの11×11×3水槽を後続fixtureへ置換するcleanupで、player直下を先に消去した後、残留水の即時検査が失敗して後続layoutを作らないまま例外終了した。このためplayerが落下死した。意図されたfixture動作ではなく、試験ハーネスの順序不具合である。

- 事故時刻: 2026-09-05 01:56:20 JST
- 復旧: respawn後、`(210.5, 201.0, 210.5)`の一時的な滑らかな石の足場へ退避し、health 20を確認
- 修正commit: `cf6393d`（cleanup前にarena通常床上へ退避、速度とfall distanceをreset、neighbor updateを抑えた全owned-volume消去）
- 内部検証: `harnessTest`成功、GameTest 14/14成功
- 再起動後確認: 2026-09-05 02:11:09 JSTに`cobblestone_generator`のserver setup/client slot適用が完了し、cleanup例外・再死亡なし
- review反映: 退避床を宣言外のBEDROCKにせず、arena共通baselineのsmooth stoneへ変更。同一tick内の無意味な二重clearは削除

残る回帰確認は、MCMCP再許可後にFishing→各後続fixtureを切り替え、20〜40 ticks後にも旧Fishing外周が乾いていること、playerの座標・体力・支持面が保たれることを観測することである。

## 木こりテスト

### 対象

石の柵で囲われた安全な領域にある木を伐採し、原木を回収する。将来の加点目標は、苗木の植え直し、巨木を含む全原木の回収、各原木1 stackの収集である。

### 確認できた結果

| 項目 | 結果 | 根拠・範囲 |
|---|---|---|
| 既知地点への初回移動 | 成功 | 最小木こりgateで、地上から届く既知のoak幹まで移動できた |
| 既知oak幹の伐採 | 成功 | Action `32a87494-4768-445a-a142-3b688566bbbb`が`SUCCEEDED`。3 blocks、73 ticks、camera 59.63° |
| fixtureの安全条件 | 成功 | `phase5.tree.gate=PASS`。柵、支持面、player位置を保持し、axe damage 3を確認 |
| 使用道具の自律取得 | 未検証 | iron axeはテスト前に用意されており、取得工程は木こり成功に含まれない |
| drop回収 | 未検証 | 原木itemの全回収は保証していない |
| 葉の破壊を伴う接近 | 未実装・未検証 | 隠れた幹の探索と葉を除去する経路は最小gateの対象外 |
| 巨木、高所、異種木 | 未検証 | 地上から届く3段oak幹だけが合格範囲 |
| 苗木の植え直し | 未検証 | 植林は最小gateの対象外 |
| 1 stack収集 | 未検証 | 伐採、drop回収、植林、成長待機の反復は未実施 |

### 改善点

1. chest等から適切なaxeをMCP経由で取得し、既存itemだけを使う。
2. leaf blockを可視・既知targetとして安全に破壊し、幹とdropへ到達できる経路を再計画する。
3. drop entityの観測、到達可能性、inventory増加を使い、場内の原木回収を検証する。
4. full-blockの高低差、足場、落下リスクを扱い、巨木と1 block高い木へ段階的に対応する。
5. 元の土と樹種を記録し、対応するsaplingの回収・植え直しを事後条件付きで実行する。

## 畑テスト

### 対象

chest内のhoeとwheat seedsを取得し、fence gateを通って畑へ入り、耕す、植える、成熟を待つ、収穫する、植え直す、を反復してwheatを1 stack集める。

### 2026-08-29 fresh MCP-only R11

R10後に追加したobservation filter、整数`navigation_target`、`collect_visible_item_batch`、camera量子化reserveを含む環境で、同じproduction prompt一文によるfresh `gpt-5.6-sol high`評価を行った。T0後のoperator介入はなく、trace auditは違反0だった。chest確認、hoe / seeds取得、gate通過、耕作、播種、収穫、drop回収、再播種まで実行したが、最終inventoryはwheat 26、wheat seeds 74、netherite hoe 1で未達となった。

dynamic callは224件、`agent_start_action`は73件中39件受理、34件成功、5件fail-safe失敗だった。Minecraft内のAction実行は合計約64秒に対しwall timeは約989秒で、最初の収穫開始が約576秒後になった。受付拒否34件の内訳は`INVALID_ARGUMENT` 15、`TARGET_UNKNOWN` 10、`NO_KNOWN_PATH` 4、`PROGRAM_BUDGET_UNPROVABLE` 4、deadline拒否1である。

新filterはobservation 58回中50回で使用されたが、正しいDSL signatureとbudget条件の発見に多数の再送を要した。また、`collect_visible_item_batch`は内部で単品collect列へ展開されるため、処理中に後続dropの座標witnessが失効し、4 Action中3件が途中でfail-safe停止した。したがってR11の改善候補は、strict schemaを維持した正確なsignature提示、複数validation errorの一括返却、budgetの安全な自動導出または必要量の構造化返却、collect batchの第一級実行単位化である。これらは**未実装**であり、feedbackを受けるまで修正・再実験を保留する。

### 2026-08-29 fresh MCP-only R10

production prompt一文だけを渡したfresh `gpt-5.6-sol high`が、元の畑でchest確認、hoe / seeds取得、gate通過、14区画の耕作・植付け、41株の収穫、drop回収までをT0後のoperator介入なしで実行した。最終inventoryはwheat 39、wheat seeds 115、netherite hoe 1で、64個には25個不足した。

accepted Action 38件は全件terminal（成功36、fail-safe失敗2）で、trace auditは違反0だった。Minecraft内実行は合計約55秒だがwall timeは約990秒で、37回のobservationが約1.98MB、単品drop回収が14 ActionとなったLLM↔MCP往復が未達の主因である。R11向けに、delivery-only observation filter、整数`traversability.navigation_target`、1〜8件の`collect_visible_item_batch` DSL macro、camera量子化reserveを実装した。

### 2026-08-27時点の結果

| 工程 | 結果 | 詳細 |
|---|---|---|
| chest内itemの確認 | 手動補助 | `computer-use`でchest GUIを開いた |
| hoeとseedsの取得 | 手動補助 | netherite hoe 1個とwheat seeds 64個をhotbarへ移した。MCP成功には数えない |
| 畑入口付近までの移動 | 一部成功 | MCPの移動で入口付近まで到達した |
| 狭路の通過 | 失敗 | `PATH_BLOCKED`で停止した |
| gateへ視点を向ける | 条件付き成功 | camera budget 180°では`BUDGET_EXCEEDED`。360°での再試行は65.37°、8 ticksで成功した |
| 経路再計画後の移動 | 失敗 | `navigate_to_known`の`primitive_replanned_route`が`BUDGET_EXCEEDED`になった |
| fence gateの開閉・通過 | 手動補助 | `computer-use`で開閉・通過した。MCP単独成功には数えない |
| MCP操作の再有効化 | 手動補助 | Screen上でONへ戻した。MCP単独の連続実行ではない |
| `till_known_block` | 失敗 | dirt `(-15,55,-14)`に対し、camera 24.37°、5 ticks、interaction 0の時点で`BUDGET_EXCEEDED (primitive_budget)`。worldは未変更 |
| 播種、成長待機、収穫 | 未到達 | 耕作primitiveの予算不整合を先に修正する |
| wheat 1 stack | 未達 | 反復試験を開始できていない |

### 2026-08-28 追加検証

commit `7fabfca`で、vanillaの段階的なcamera回転とfloat丸めをadmission側でも再現し、実行時と同じcamera消費量を事前予約するよう修正した。これにより、解析上の角度との差で正常な操作が`BUDGET_EXCEEDED`になる問題を根治した。

| 工程 | 結果 | 詳細 |
|---|---|---|
| camera予算修正後の収穫 | 成功 | Action `7e1617de-c820-4466-bfcb-6aa246d93510`でwheat 2株を収穫した |
| 8区画への播種 | 部分成功 | Action `1eaeb2ae-29ea-424f-8dff-1d6980a6ab02`で6株を播種後、7株目の実行前にglobal camera上限360°へ到達して停止した。修正前の誤差ではなく、宣言したAction全体予算による正当な停止である |
| 観測遮蔽を伴う播種 | 部分成功 | Action `178859fa-e8ac-4eb4-ab72-d33b3709ed6c`は1株を播種後、次の支持面を現在の観測から確定できず`TARGET_UNKNOWN`で停止した |
| 1区画ずつの播種 | 成功 | Actions `7a611dae-f11e-4ae0-a5bd-1b86df69b095`、`eddf4a41-cf9d-45a0-962c-1f972de754ff`で各1株を播種した |
| 3区画batch | 成功 | Actions `29a7da88-2fe1-4387-a65f-9d837e5fe6d8`、`83d57409-bbf0-4256-9194-be4be65c9e91`が各3株を播種した |
| 畑内の区画間移動 | 成功 | cardinal方向の1区画移動を複数回実行し、既知の作物上を安全に通過できた。未知区画への直接移動は`TARGET_UNKNOWN`で拒否された |
| 作付面積 | 進行中 | 合計20区画へ播種した。inventoryはwheat 7、wheat seeds 50、hoe 1 |
| 成熟待機 | 進行中 | Action `b6eb16c8-d34b-48bf-8fe0-de4c3624d112`で既知の1株が成熟するまで最大11,000 ticks待機中 |
| wheat 1 stack | 進行中 | 成熟後の収穫、drop回収、再播種の反復が残る |

追加で判明した改善点は次のとおり。

1. 多数区画を1 Actionへ詰め込まず、camera消費を見ながら2～4区画程度の小さいbatchへ分割する。
2. mutation直前に対象面を再観測し、player自身や作物による遮蔽、world revision更新で失効した証拠を使わない。
3. 収穫後はdropが消失する前にcardinal移動を組み合わせた回収routeを実行し、inventory増加で回収を確認する。
4. 成熟待機、収穫、drop回収、再播種を小さいDSL単位で反復し、wheat 64個到達を確認する。

### 2026-08-28 1 stack到達結果

20区画の畑で、移動、照準、成熟作物の破壊、drop回収、播種をMCMCPのAction DSLから実行し、inventoryのwheatを32個から64個まで増やした。Minecraftの移動・視点・attack・useに`computer-use`は使用していない。`computer-use`は、待機時間を短縮する検証fixtureのコマンド投入にだけ使用したため、gameplay操作の成功には含めていない。

| 項目 | 結果 | 根拠・範囲 |
|---|---|---|
| wheat 1 stack | 合格 | 最終Action `3f6aded9-b54f-4881-9cea-711b1a8233eb`が`succeeded`。wheat 63→64、seeds 168、control mode `ready` |
| MCPだけの栽培・収穫操作 | 合格 | gameplay中の移動、視点、破壊、回収、播種はすべてMCP Toolから実行した |
| programmed DSL | 合格 | 経路移動、複数株の収穫、待機、複数区画の播種を複数nodeの1 Actionとして実行した |
| drop回収 | 合格 | 作物中央へ寄ってから破壊し20 ticks待機する手順で、最終15株を含む連続収穫がすべてwheat +1になった |
| 再播種 | 合格 | 収穫後の区画へ複数回再播種し、成長・再収穫の周期を確認した。最終64個到達直後の15区画は未再播種 |
| 成熟待機fixture | 復旧済み | `/mcmcp_fixture random_ticks accelerate`で待機を短縮し、終了時に`normal`へ戻した。最終値は3 |
| Action失敗後の状態 | 合格 | `BUDGET_EXCEEDED`と`PATH_BLOCKED`の失敗後もMCPはOFFにならず、入力解放後に`ready`へ戻った |
| chest・gateを含むend-to-end | 未合格 | この一連の試行より前にchest内item取得と入口通過へ手動補助が入っているため、完全なMCP-only受入とは分ける |

代表Actionは次のとおり。

- 2株収穫: `8314f5c5-2a14-4957-b238-e200c0d973fe`（49→51）から`5fa5cb2e-a968-4611-8a3e-64e587d7428b`（61→63）まで、7 Action連続で各+2
- 最終移動: `9d3eebbb-e707-45bc-985a-66030bea128d`（4 nodesすべて成功）
- 最終収穫: `3f6aded9-b54f-4881-9cea-711b1a8233eb`（63→64、+1）
- 正当な予算停止: `bc6e8a52-8aea-43e5-982d-68ec75f09c8a`（11/13 nodes実行後にcamera 337.81°で`BUDGET_EXCEEDED`、wheat 32→34、controlは`ready`）

実ワールドで有効だった最小戦略は、収穫を1 Actionあたり2株、播種を観測遮蔽の少ない遠い区画から2～4区画ずつ処理することである。多数の対象を1 Actionへ詰め込む必要はなく、宣言済みcamera上限内に収めた小さいprogrammed DSLを反復すればよい。

残る改善点は次のとおり。

1. 作物やplayer自身で支持面が隠れる順序を避けるため、播種対象を遠い順に並べ、必要ならnode間で再観測する。
2. full dirtの1 block高低差を含む経路は、plannerが中間cellとY座標を自動挿入する。今回の試験では明示的な直交routeで補った。
3. camera予算から安全なbatch数を事前計算し、途中までworldを変更してから`BUDGET_EXCEEDED`になる頻度を下げる。
4. 観測paginationの完了時にleaseを即時解放する。現状は最大2 leaseがTTLまで残り、短時間の再観測が`SERVER_BUSY`になる場合がある。
5. 農地上の平坦移動では踏み荒らしを再現しなかったが、落下・jumpを伴う進入は別のGameTestで検証し、必要ならfarmland保護をnavigationへ追加する。
6. 通常のrandom tickだけを使う長時間試験を別途実施する。今回のfixtureは待機時間短縮専用であり、収穫・播種そのものは支援していない。

### 改善点

優先順は次のとおり。

1. 既存の`open_known_fence_gate`でfence gateの現在の`open`状態を観測し、通常use後の反転を実ワールド検証する。
2. gateの開閉でKnown Traversability Mapのedgeを更新し、player AABBが通る幅の狭路を過度に`PATH_BLOCKED`へしない。
3. `face_known_position`とnavigation replanのcamera / motion budget消費をtraceから分離して調べ、同じprimitive内の再計画でbudgetを意図せず使い切らない。camera上限は実行前に十分な値を宣言する。
4. 既存のcontainer同期・`transfer_items`を現在のAction DSLへ接続し、chestのinventory観測、item取得、事後inventory検証をlive確認する。
5. 上記の入口工程をMCP単独で通し、今回合格した栽培・収穫DSLへ接続する。
6. 最終収穫後の区画も再播種し、wheat 64個と畑の復旧を同じ受入Action列で確認する。

`till_known_block`の失敗は、admissionが観測面の照準点でcamera costを見積もる一方、semantic preparationが複数の到達可能面から別の照準点を選べるため、実消費がprimitive単位の見積もりを上回ったことが原因である。修正後は、実行側の照準点がadmissionへ公開されるまで、1回のblock mutationに安全側の360°上限を予約して再検証する。

## 次回の受入手順

### 木こり

1. MCMCP操作をONにする。これは試験開始時の利用者による明示許可とし、以後の作業操作には含めない。
2. MCPだけでchest等からaxeを取得する。
3. MCPだけで木へ移動し、伐採、drop回収、苗木の植え直しを行う。
4. inventoryとworldの事後条件を取得し、computer-useによる補助がなかったことを監査traceと合わせて記録する。

### 畑

1. MCPだけでchestを開き、hoe 1個と必要数のseedsを取得する。
2. MCPだけでgateを開け、通過し、必要なら閉じる。
3. MCPだけで耕作、播種、成熟待機、収穫、再播種を行う。
4. inventoryのwheatが64個以上であることを確認する。
5. 各ActionのID、terminal state、failure code、ticks、distance、camera、interaction / place / break数を本記録へ追記する。

途中に手動補助が1回でも入った場合、その試行は部分機能の診断結果として残すが、end-to-end合格にはしない。
