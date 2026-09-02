# 高難易度建築コピーのfresh MCP-only正式評価（2026-09-02）

## 結論

`aod-mimoid`上のDocker cloneで高難易度建築コピーを90分の正式条件で実行したが、**機能不合格**だった。destination 294 cellsの完全BlockState一致は171 cells（58.16%）で、正しく配置できた非air blockは10 cellsだけだった。屋根、上階、開口閉鎖、仮設撤去が未完了である。

run07の操作はproduction MCMCP公開5 Toolだけで行われ、T0後のoperator介入、Minecraft command、fixture/admin操作はない。入力lockの終了処理は正常で、全Action terminal、input ownerなし、入力解除済みを確認した。

一方、元のtrace監査も不合格だった。理由はDockerでCodex sandboxを作れなかった`configWarning` 1件と、90分run中にapp-serverが通常生成した`contextCompaction` 2組である。後者はpayloadなしの厳密schemaに限って監査可能とする修正を試験後に加えた。前者はcontainer security profileの問題なのでallowlist化せず、次回正式runまでにcontainerを作り直す必要がある。機能結果が不合格なので、監査修正によって今回の合否が覆ることはない。

## 固定条件

- 実験ID: `20260902-hard-building-v2-run07`
- baseline: `20260902-hard-building-v2-nearby-start`
- prompt profile: `hard-building-copy`
- model / effort: `gpt-5.6-sol / high`
- T0: `2026-09-01T22:33:53.8423070Z`（JST 2026-09-02 07:33:53.842）
- 終了: `2026-09-02T00:03:26.2837917Z`（JST 09:03:26.284）
- 経過: 1時間29分32.441秒
- evaluator deadline: 90分（5400秒）
- Codex exit: 0、turn status: completed
- production source commit: `37d58e6662680f79d8e3be80505433825d32a0b1`
- production JAR SHA-256: `687ea418927664513988fca18825cfdcc024341fc1a229a12d57adc59d8e42db`
- corrected player fixture SHA-256: `8e961b49f0d7bfa184dee10d5cc0b7fef5d0d836de58b87a95ed90f3175a9332`
- original world archive SHA-256: `abfb8f879d70bbe49fa46ffad30c701368222e09b47aeed2a88be226e41817a4`

production promptは次の1文だけで、座標、手順、Action例、過去runのcontextは渡していない。

> チェストの材料を自由に加工して、近くにある屋根付きの木造建築を見本に、羊毛の上へ同じ建築をコピーしてください。

### 比較領域

| 領域 | bounds（両端含む） | cells | T0状態 |
|---|---|---:|---|
| source | `x=-23..-18, y=56..62, z=-1..5` | 294 | 見本建築 |
| destination | `x=-23..-18, y=56..62, z=9..15` | 294 | 全cell air |

destinationはsourceの`z+10`対応で比較した。sourceの初期構成はoak planks 43、oak stairs 38、oak log 22、cobblestone stairs 13、oak slab 5、glass pane 3、wall torch 3、oak door 2 cellsである。air、facing、axis、shape、attachmentを含む完全BlockStateを合格条件にした。

## 試行回数と分類

T0へ到達した正式試行は2回、T0前で止めたsetup試行は4回である。

| run | 分類 | 結果 |
|---|---|---|
| run01 | setup、T0前 | 90分profileに対するevaluation lease上限不足を検出し、lease acquire拒否。gameplay 0 |
| run02 | setup、T0前 | lease修正後も候補buildの取り込み不足を検出し、lease acquire拒否。gameplay 0 |
| run04 | 正式試行1 | 19分21秒。playerが柵と水路に閉じた農場から開始しており、建材・見本・羊毛へ到達不能。185 calls、153成功、32 domain error。source/destination変更なし |
| run05–06 | setup、T0前 | player開始位置を建築試験近傍へ直し、readinessと保存cloneを確認。production turnは開始していない |
| run07 | 正式試行2 | 89分32秒。材料取得・craft・一段目外周の一部まで進行したが、exact roofと高所施工を完了できずdeadline |

run04 artifactは `C:\Users\aod\Documents\GitHub\mcmcp-test-artifacts\20260902-hard-building-v1-run04`、run07 artifactは `C:\Users\aod\Documents\GitHub\mcmcp-test-artifacts\20260902-hard-building-v2-run07` に保存した。run07終了後worldはMinecraftのSave and Quitで保存し、`C:\Users\aod\AppData\Local\Temp\mcmcp-hard-building-20260902\post-run07-saves\tester (1)`へ回収した。

## run07で実際に行った方法

1. omnidirectional observationと移動を繰り返し、見本の壁5×5、外装・屋根7×7、destinationが見本からz+10であることを約8分で把握した。
2. 材料double chestからoak log 64個とcobblestone 64個を取得した。
3. crafting tableでoak planks、oak stairs、oak slab、cobblestone stairsをcraftした。craft工程は約2分半で、Action受付率94.4%だった。
4. 白い羊毛面を直接の支持証拠として使えなかったため、周囲の既知面から仮設bridgeを伸ばし、destinationの一段目外周を施工した。
5. 見本のoak stairからexact stateを再観測し、同じfacingの階段を連鎖的に置く「型材」の方法で屋根を試した。しかし隣接階段による自動shape変化、支持面、作業高さの条件を同時に満たせず中断した。
6. `pillar_up_known`で高所へ上がろうとしたが、白い羊毛の完全stateがpolicy-visibleでないこと、直前support witnessの不足、player直下へのcentering条件で成功しなかった。
7. 残り時間で板材の段状屋根、次いで平屋根へ近似したが、これは「同じ建築」の条件を満たさず、屋根材2個を確認した段階でdeadlineになった。

Minecraft内の配置はcommandやfixtureによるものではなく、すべてproduction MCMCP Actionによるplayer操作である。ただし完成建築ではなく、途中状態である。

## 時間分析

| 区間 | 経過時刻 | 所要時間 |
|---|---:|---:|
| 探索・見本把握 | 00:00:02–00:08:06 | 8:04 |
| 材料回収 | 00:08:06–00:12:59 | 4:53 |
| 初回craft | 約00:12:20–00:14:56 | 約2:36 |
| 一段目外周 | 00:14:56–00:48:05 | 33:09 |
| 型材階段・exact屋根の試行 | 00:54:25–01:05:29 | 11:04 |
| 再craft・高所上昇 | 01:05:29–01:16:58 | 11:29 |
| 近似屋根・内部施工 | 01:16:58–01:29:15 | 12:17 |
| deadline処理・終了 | 01:29:15–01:29:35 | 0:20 |

一段目外周だけで33分09秒（全体の37.0%）を使い、そのうち16分18秒は基準点と仮設bridgeの確立だった。屋根関連は合計41分30秒（46.3%）で、exact階段屋根と高所経路の失敗だけで少なくとも22分33秒を使った。MCPへforwardしてからresponseまでの累積は約211秒（3.9%）なので、主因はMCP server待ちではなく、呼出し間の再観測、位置取り、計画変更である。

## Tool・Action結果

- dynamic request: 1262、全件schema上valid
- success: 1174
- domain error: 86
- deadline rejection: 2、いずれも正当
- `agent_get_state`: 332成功
- `agent_get_observation`: 341成功、3 error
- `agent_start_action`: 250受付、83 error
- `agent_get_action`: 251成功
- `agent_cancel_action`: 0

`agent_start_action` error 83件の内訳は、`NO_KNOWN_PATH` 39、`TARGET_UNKNOWN` 30、`INVALID_ARGUMENT` 6、`PROGRAM_BUDGET_UNPROVABLE` 5、`SAFETY_PRECONDITION` 2、`CAPABILITY_DENIED` 1だった。特に`navigate_to_known`の既知path不足36件と、`apply_known_block_plan`の支持面・exact state不足が支配的だった。

`pillar_up_known`は4回試し、成功0だった。1回は`expected_support`の形式誤り、2回は直前に配達されたexact UP face/state不足、1回はplayer直下block上へのcentering失敗である。

## 外部オラクルによる完全state比較

### destination

- 294 cells中171一致、123不一致（58.16%）
- destination-afterはair 275、oak planks 19
- 正しい非air block: 10 cells
- 期待non-airだがair: 114 cells
  - oak stairs 38、oak planks 33、oak log 18、cobblestone stairs 13、oak slab 5、glass pane 3、wall torch 3、oak door 1
- 誤block: 5 cells
  - oak log期待にoak planks 4、oak door期待にoak planks 1
- air期待にoak planks: 4 cells
- 同block IDでpropertiesだけ異なる例: 0

### sourceと仮設残留

sourceの既存129 non-air blocksは破壊・置換されていない。ただしsource内のair 2 cells、`(-23,56,5)`と`(-19,56,5)`がoak planksになり、厳密なsource不変条件には不合格だった。

source/destination外の残留は3 cellsだった。

- `(-21,59,6)`: oak stairs、`facing=north, half=bottom, shape=straight, waterlogged=false`
- `(-21,59,7)`: oak planks
- `(-21,59,8)`: oak planks

source汚染2 cellsを含めるとdestination外残留は5 cellsである。hard-area全域 `x=-24..8, y=50..62, z=-8..16` の変更はoak planks 23個とoak stairs 1個の計24 cellsだけで、他の広域汚染はなかった。

## 材料・inventory収支

T0のplayer inventoryは空だった。終了時はcobblestone 40、cobblestone stairs 16、oak log 21、oak planks 82、oak slab 6、oak stairs 39の計204 itemsだった。

材料double chestはoak log 576→512、cobblestone 576→512、sand 576→576だった。別halfのnetherite pickaxeとnetherite axeは各1個のまま変化せず、モデルは取得していない。両furnaceも空のままで、smeltingは実行していない。

cobblestoneは取得64個 = 最終40個 + stairs 16個のcraft消費24個で一致した。oak系はrecipe原料換算で63 logs相当まで説明できるが、1 oak log = 4 planks相当がplayer、chest、評価領域、近傍item entityのいずれにもなく、保存stateだけでは行先を確定できなかった。

## 躓いた点と修正

1. **fixture開始位置**: run04は農場内開始で課題そのものへ到達できなかった。playerだけを建築近傍へ移したbaselineを作り直し、run07は探索を開始できた。
2. **羊毛支持面の完全state不足**: destination markerのwhite woolは安全なfull cubeだが、support policyとpolicy-visible stateの対象外だった。このため最初の足場確立に16分以上かかった。試験後、white woolだけを不活性support allowlistとnon-copy visible-state allowlistへ追加した。
3. **高所施工**: 1 blockずつの`pillar_up_known`は証拠とcenteringが厳しく、今回成功しなかった。連続施工用の新抽象化は追加せず、まずwhite wool supportの根本的不一致だけを修正した。
4. **方向付きstairs**: source stateの取得自体はできたが、隣接配置でstairs shapeが変わり、支持面・作業高さも不足した。exact copyを諦めて近似屋根へ進んだ判断は合格へ寄与しなかった。次回はexact経路が成立しない時点で早期に失敗として打ち切る。
5. **長時間trace監査**: app-server標準の`contextCompaction`を監査schemaが知らなかった。`type,id`だけのstarted/completed pairに限定して許可し、payloadや未知fieldは引き続きfail closedにした。
6. **Docker sandbox**: containerがuser namespaceを作れず`configWarning`が出た。監査を緩めず、次回はsandbox前提を満たすcontainer profileに変更する。

### 試験後修正の検証

| 検証 | 結果 |
|---|---|
| white wool support / observation / pillar DSL focused tests | 合格（Gradle `BUILD SUCCESSFUL`） |
| trace audit self-test | 63/63合格 |
| 修正後監査でrun07全traceを再照合 | `contextCompaction` 4件の違反は解消。`configWarning` 1件だけが残り、監査結果は引き続き不合格 |
| `git diff --check` | 合格 |

修正commitは`0f1f9d2`（white wool support）と`3946523`（context compaction監査）である。

## 終了時安全性

- `inputs_released=true`
- `input_owner_none=true`
- `all_actions_terminal=true`
- `release_http_confirmed=true`
- deadline後のstate/actionはMCPへforwardしていない
- MinecraftはSave and Quitし、integrated serverの全chunk保存を確認した

モデルの最終報告は次のとおりで、未完了を正しく申告していた。

> 羊毛上に木造の外周を複製し、屋根の施工途中まで進めました。実行期限に達したため、屋根の残り・一時開口の閉鎖・仮設材の撤去は未完了です。

## 次回正式試験の前提

次回は、white wool support修正のfocused testと、`contextCompaction`を含むtrace audit self-testを通したbuildを使う。さらにCodex sandboxが有効になるDocker profileへ作り直す。今回の失敗に対して汎用高所plannerや自動建築抽象化は追加していない。まず同じexact-copy課題で、支持面取得と1 block上昇が実際に成功し、近似へ逸脱しないことを短い回帰試験で確認してから、90分の正式再試験へ進む。
