# Phase 5 額縁ラベル倉庫 transfer gate

## 目的と合格条件

額縁ラベルからcontainerを選び、通常player GUI操作だけで単一chestからraw iron 16個を取り出し、別のbarrelへ全量格納する小規模E2Eである。固定fixtureは`/mcmcp_fixture phase5 label_transfer`で準備する。

- sourceは`(195,200,194)`のsingle chest、destinationは`(197,200,194)`のbarrel
- 両方のsouth面にnon-empty item frameを直接付着し、表示itemを`minecraft:raw_iron`へ固定
- T0はsourceにraw iron 16、playerとdestinationは空
- `visible_entity.container_label`と別途配達したcontainerのup surfaceを無変換でActionへ渡す
- take/storeの各Actionはinteraction 3、distance / break / place 0
- 各Actionのeffectは`confirmed`かつ`transferred=16`
- 最終player inventoryはraw iron 0、storeのclose/reopen readbackはdestination count 16を確認
- 終了時に全Actionがterminalでpublic inputが解放済み

このgateはexact registry item、single chest/barrelだけを対象にする。double chest、tag/category label、額縁がないcontainerへの自動推測は合格範囲外である。

## 実装

- fixture mode: `label_transfer`
- runner: `tools/eval/Invoke-McmcpWarehouseLabelCapabilityGate.ps1`
- offline mock: `tools/eval/Test-McmcpWarehouseLabelCapabilityGate.ps1`
- artifacts: `gate-events.jsonl`、`gate-result.json`

runnerはsourceのlabelを使ったtake後、fresh stateからdestination labelを再取得してstoreする。古いsource refをdestinationへ流用しない。ラベルの額縁に遮られないcontainer up surfaceを明示的に配達し、planner/runtimeの通常block interaction証拠も独立に保つ。

## aod-mimoidでの最短実行手順

1. このrevisionのproduction JARとharness JARを検証instanceへ配置し、test harnessを有効化する。
2. disposable singleplayer worldを開き、`/mcmcp_fixture phase5 label_transfer`を一度実行する。
3. MCMCPで`camera`と`inventory_transfer`を許可し、controlをReadyにする。
4. remote loopbackのMCP relayをSSH local forwardする。Compose既定ならremote `127.0.0.1:18765`である。
5. repo外の空artifact directoryと当該runのtoken pathを指定して次を実行する。

```powershell
pwsh -NoProfile -File .\tools\eval\Invoke-McmcpWarehouseLabelCapabilityGate.ps1 `
  -ArtifactDirectory '<repo外の空directory>\warehouse-label-transfer' `
  -TokenPath '<MCMCP tokenの絶対path>' `
  -Endpoint 'http://127.0.0.1:<SSHでforwardしたport>/mcp'
```

現在はfixture、runner、mock/harness testまでの準備であり、実ワールドPASSはまだ記録していない。最初のremote runでは額縁2個の描画・`container_label`返却、up surfaceの存在、take/storeの2 terminal、最後の入力解放を画面とartifactの双方で確認する。
