# Phase 5 smelt capability gate

## 目的と合格条件

`aod-mimoid`のDocker内Minecraft 26.2 / NeoForge 26.2.0.59で、公開固定5 Toolだけから`smelt_known_recipe`を1 Action実行する。通常player GUI操作でraw iron 1個とcoal 1個を空のfurnaceへ入れ、iron ingot 1個を回収する。合格には次をすべて要求した。

- recipe queryのopaque `recipe_ref` / fingerprintと配達済みvisible surfaceを無変換でActionへ渡す
- accepted 1 == terminal 1、terminal state `succeeded`
- traceが`smelt_complete=minecraft:iron_ingot`の後に対象node完了を示す
- inventoryがraw iron `1 -> 0`、coal `1 -> 0`、iron ingot `0 -> 1`
- distance / block break / block placeは0、interactionは予算7以内
- 最終close/reopen readbackでstation slotsとcursorが空
- 終了時にpublic inputが解放され、一時tokenが残らない
- Save and Quit後の独立NBT検査でも同じinventoryと空stationを確認する

## 試験履歴

| Run | 実行結果 | 躓いた点 | 修正・判断 |
|---|---|---|---|
| r1 | Action開始前FAIL | result itemがiron ingotの既知recipeはsmeltingとblastingの2件だったが、runnerが1件固定と誤認した | `display_kind=smelting`、`required_screen=furnace`、対応input/outputを満たす一意候補を選択する。queryの非truncated性と件数整合は維持 |
| r2 | Action自体はSUCCESS。raw iron / coal消費、ingot回収まで成立したがgateはFAIL | 予算上限`max_interactions=7`を実行回数の固定値と誤認し、実測6を拒否した | terminal proofを`1..7`へ修正し、実測値をartifactへ保存 |
| r3 | 1 interaction後に`SERVER_DENIED_OR_DESYNC`で安全停止 | r2後にfixtureを再適用した際、炉slotだけを空にし、約1400 tickの残燃焼dataを保持した。製品側はcold empty `[0,0,0,0]`だけを初期状態として受理する | fixtureのfurnaceとbrewing standをairへ戻して再設置し、BlockEntityごと再生成するよう修正。本体の安全判定は緩めない |
| r4 | **PASS** | なし | accepted / terminal 1、interaction 6、distance / break / place 0。公開入力解放とtoken削除もPASS |

r4 artifactは`F:\mcmcp-testlab\20260902-hard-building-v1\eval-artifacts\20260904-smelt-r4`である。`runner-console.log`、`gate-events.jsonl`、`gate-result.json`、`material-output-oracle.json`、`offline-smelt-oracle.json`、cleanup / world-reset receiptを保存した。保存後NBTではplayer inventoryがraw iron 0、coal 0、iron ingot 1、furnace item slotが空、cook time 0だった。burn timeは1204 tick残っており、r3のfixture隔離バグも独立に裏づけた。

試験後はcontainerを停止し、一時test harness JARを削除、`instance.cfg`を`JvmArgs=`へ復元した。production mod SHA-256は`B98D32368CF9C3775EB8E7AF6DAE5FBEC06CA9EB84B8FEE68466057015379AE4`のまま、一時tokenは不在である。worldはfresh `wall-5x5` baselineへ復元した。

## 実装・検証

- `Invoke-McmcpSmeltCapabilityGate.ps1`はfurnace smeltingに限定してrecipeを選び、budget上限と実測interactionを分離した。
- `Test-McmcpSmeltCapabilityGate.ps1`はempty trace、movement、fixed-five不足を拒否し、remote Windows PowerShell 5.1でもPASSした。
- repeated fixtureの独立性修正はremote JDK 25 Dockerで`harnessTest harnessJar`を実行し、BUILD SUCCESSFULだった。
- `Inspect-McmcpSmeltOracle.py`は閉じたworldだけを読み、通常`playerdata`と当該worldの`players/data`、通常overworldと`dimensions/minecraft/overworld`の両layoutを扱う。

## 次の改善候補

一般worldでは、slotが空でも残熱のある炉を約80秒拒否するのは非効率である。ただし初期data判定だけを緩めると、残熱だけで完了した場合のcoal消費を誤計上する。対応時は初期lit remainingを記録し、最終snapshotから実燃料消費0..理論上限を確定してinventory ledgerへ反映する。fixture修正とは独立した製品機能として実装・試験する。

また`FURNACE_NOT_INITIAL_EMPTY`のfailure evidenceには、raw slot番号を公開せず、`station_slots_empty`、`cursor_empty`、lit/cookのbounded dataを付けると診断が速くなる。smeltとbrewは「実行→fixture再適用→再実行」の2連続PASSを回帰gateに追加する。
