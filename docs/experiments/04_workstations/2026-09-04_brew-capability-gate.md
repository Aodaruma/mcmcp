# Phase 5 brew capability gate

## 目的と合格条件

`aod-mimoid`のDocker内Minecraft 26.2 / NeoForge 26.2.0.59で、公開固定5 Toolだけから`brew_known_potion_batch`を1 Action実行する。通常player GUI操作で水入り瓶3本、nether wart 1個、blaze powder 1個をbrewing standへ入れ、awkward potion 3本を回収する。合格には次をすべて要求した。

- 配達済みvisible surfaceの座標とblock identityを無変換でActionへ渡す
- accepted 1 == terminal 1、terminal state `succeeded`
- traceが`brewing_complete=3`の後に対象node完了を示す
- item inventoryがpotion `3 -> 3`、nether wart `1 -> 0`、blaze powder `1 -> 0`
- standard potion内訳がwater `3 -> 0`、awkward `0 -> 3`
- distance / block break / block placeは0、interactionは予算16以内
- 最終close/reopen readbackでstation slotsとcursorが空
- 終了時にpublic inputが解放され、一時tokenが残らない
- Save and Quit後の独立NBT検査でも同じinventoryと空stationを確認する

## 試験履歴

| Run | 実行結果 | 躓いた点 | 修正・判断 |
|---|---|---|---|
| r1 | Action開始前FAIL | brewing standはpartial shapeなので観測recordの`state`が`null`だったが、runnerがfull blockと同様に非null stateを要求した | target position、block identity、face、freshnessは維持し、partial shapeに不要なstate必須判定だけを外した。mockにも`state=null`を追加 |
| r2 | **PASS** | なし | accepted / terminal 1、interaction 11、water 3からawkward 3、station/cursor空、公開入力解放を確認 |
| r3 preflight | ゲーム操作前FAIL | remote host側runnerのendpointを既定`8765`のまま起動し、Docker公開port `18775`へ到達しなかった | artifactを`20260904-brew-r3-preflight-wrong-endpoint`へ分離。Action受付前なので醸造試験の成否回数には含めない |
| r3 | **PASS** | なし | r2直後にcooldownなしでfixtureを再適用。accepted / terminal 1、interaction 11、同じexact ledger、offline NBTもPASS |

r2 artifactは`F:\mcmcp-testlab\20260902-hard-building-v1\eval-artifacts\20260904-brew-r2`、r3 artifactは`F:\mcmcp-testlab\20260902-hard-building-v1\eval-artifacts\20260904-brew-r3`である。r3には`runner-console.log`、`gate-events.jsonl`、`gate-result.json`、`material-output-oracle.json`、`offline-brew-oracle.json`、setup / cleanup / world-reset receiptを保存した。

保存後NBTではplayer inventoryがpotion 3、nether wart 0、blaze powder 0で、3本すべてが`minecraft:awkward`だった。brewing stand item slotは空、brew timeは0、燃料ゲージは19である。燃料ゲージ19はblaze powder 1個を投入して1回分を使った正常な結果であり、未回収itemではない。

修正版harness SHA-256 `5A9F26EC252ACB6613939F70A3C86613AF64CBBC2EBF4D6A357EB41692BA8B35`を実際にロードし、r2 / r3が連続PASSした。各試験後は一時tokenを削除した。最終的にcontainerを停止し、一時test harness JARを削除、`instance.cfg`から`-Dmcmcp.testHarness=true`を除去した。production mod SHA-256は`B98D32368CF9C3775EB8E7AF6DAE5FBEC06CA9EB84B8FEE68466057015379AE4`のままで、worldはfresh `wall-5x5` baselineへ復元済みである。

## 実装・検証

- `Invoke-McmcpBrewCapabilityGate.ps1`は配達済みstation evidenceを使い、1 batchのexact item / standard-potion ledger、Action lifecycle、terminal trace、station/cursor readback、input releaseを検査する。
- `Test-McmcpBrewCapabilityGate.ps1`はpartial-shape surfaceを再現し、local PowerShell 7とremote Windows PowerShell 5.1でPASSした。
- `Inspect-McmcpBrewOracle.py`は閉じたworldだけを読み、playerのitem componentsからstandard potion種別を数え、当該brewing standのBlockEntityも検査する。
- fixtureはfurnaceと同様にbrewing standを一度airへ置換して再設置するため、残存BlockEntity dataを次の試験へ持ち越さない。

## 次の改善候補

このgateはknown vanilla potion recipeに限定している。将来のMOD醸造や独自GUIでは、item IDだけでなくcomponents全体をopaque fingerprintとして観測し、入力・触媒・燃料・出力slotの意味をGUI adapterから提供する必要がある。一方、未知GUIの画面座標をActionへ直書きする方式には広げず、観測で得たopaque control / slot参照と実行後ledgerの照合を共通契約にする。
