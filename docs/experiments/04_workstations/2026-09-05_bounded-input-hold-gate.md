# bounded input hold capability gate

## 目的

`hold_bounded_inputs`が、固定時間だけMinecraft入力を保持し、成功・失敗・キャンセルのいずれでもterminal前に全入力を解放することを確認する。自動釣り機や丸石製造機など、既設設備を定位置で操作する低判断コスト作業の基礎gateである。

公開契約は次のとおりである。

- `inputs`: `forward`、`back`、`left`、`right`、`jump`、`sneak`、`attack`、`use`の重複なし配列
- `duration_ticks`: 1..1,728,000 ticks（最大24時間）
- `attack`または`use`を含む場合だけ、freshな`visible_surface`から無変換でコピーした`target_guard={target,face,expected_state}`と、現在選択中の`selected_item`が必須
- 移動系入力だけの場合は`target_guard`と`selected_item`を禁止
- nodeはtop-levelに単独で置く
- `attack`は`block_break`、`use`は`item_use`、移動系は`movement` capabilityを要求

24時間はschema/unitで上限を検査し、実機で24時間待つ試験は行わない。通常は目的達成に必要な最短時間を指定する。

## 短時間fixture

`/mcmcp_fixture phase5 bounded_input_hold`は次を固定する。

- player: `(204.5,200,196.5)`、yaw `180`、pitch `35`
- target: `(204,200,194)`の`minecraft:obsidian` south面
- target直前の`(204,200,195)`はAIR（共通tree fixtureの柵を除き、照準を遮らない）
- selected slot 0: `minecraft:wooden_pickaxe` 1本
- それ以外のplayer inventoryは空

木のツルハシでは60 ticksのattack保持中に黒曜石を破壊できないため、長押し経路を実際に通しながらworld mutationを期待しない試験になる。forward等による移動や24時間保持は、この短時間gateでは実行しない。

## 合格条件

`tools/eval/Invoke-McmcpBoundedInputHoldCapabilityGate.ps1`は以下を確認する。

1. fixed-five MCP surfaceとReady状態
2. player位置・体力・木のツルハシ1本、およびfreshな黒曜石south面
3. `inputs=[attack]`または`inputs=[use]`、`duration_ticks=60`、exact target guard、selected itemを持つ単独Action
4. terminal `succeeded`、実測tick 60..80、移動・camera・interaction・破壊・設置が0
5. fresh stateで位置、体力、inventory、黒曜石の完全stateが不変
6. terminal後にpublic stateがReadyで、非terminal Actionがなく、入力が解放済み

artifactは`gate-events.jsonl`と`gate-result.json`である。offline runner contractは`tools/eval/Test-McmcpBoundedInputHoldCapabilityGate.ps1`で検査する。

## 実行

```powershell
pwsh -NoProfile -File .\tools\eval\Invoke-McmcpBoundedInputHoldCapabilityGate.ps1 `
  -ArtifactDirectory '<repo外の空directory>\bounded-input-hold' `
  -TokenPath '<MCMCP tokenの絶対path>' `
  -Endpoint 'http://127.0.0.1:<forwardしたport>/mcp'
```

右クリック側は同じコマンドへ`-HoldInput use`を追加する。省略時は`attack`である。

## 2026-09-05 実クライアント結果（aod-mimoid Docker）

- r1: `INTERNAL_ERROR`。長押しnodeを通常のworld-planning対象として扱っていたため開始前に失敗。専用runtime primitiveとしてplanner対象外へ修正した。
- r2: `SAFETY_INTERRUPTED / bounded_input_target_face_or_reach_changed`。共通tree fixtureの柵が黒曜石のsouth面を遮っていた。fixtureに1ブロックの視線開口を追加した。
- r3 attack: PASS。宣言60 ticks、記録60 ticks、黒曜石・位置・体力・inventory不変、cleanup後Readyかつ全Action terminal。
- use r1: gate parameter名にPowerShell自動変数`$Input`を使ったため不正なDSL値となり、開始前に拒否。`HoldInput`へ改名した。
- use r2: PASS。宣言60 ticks、記録60 ticks、対象・位置・体力・inventory不変、cleanup後Readyかつ全Action terminal。

成功artifactは`build/eval-artifacts/20260905-bounded-input-r3`および
`build/eval-artifacts/20260905-bounded-input-use-r2`。長時間そのものは実時間で待たず、24時間境界をschema/unitで検証する。

キャンセル・OFF・Esc・focus loss経路は安全性回帰試験で全入力解放を確認する。
