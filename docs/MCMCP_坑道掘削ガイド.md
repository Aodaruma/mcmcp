# 坑道の連続掘進 / Tunnel excavation

`excavate_tunnel`は、AIが指定した有限範囲をMODが観測しながら掘り進めるActionです。1マスごとにAIへ指示を求める往復を減らし、物理的な各ブロックの安全確認は続けます。実機での受入・所要時間・トークン使用量の測定は[Issue #21](https://github.com/Aodaruma/mcmcp/issues/21)で管理します。

The AI selects a finite footprint and the mod performs the excavation loop, including fresh observation, mining and movement. This reduces model round trips; it does not remove per-block safety checks. Game acceptance and measured performance remain tracked in Issue #21.

## 掘り方 / Layout

| 指定 / Setting | 動作 / Behavior |
| --- | --- |
| `pattern: "straight"`（省略時の既定値 / default） | 入口から水平に直進。/ A straight horizontal tunnel from the entrance. |
| `pattern: "branches"` | 主坑道の一定間隔ごとに左の枝を掘って戻り、右の枝を掘って戻ってから先へ進みます。/ At each interval, excavate and return from the left branch, then the right branch, before advancing along the main tunnel. |
| `length_blocks` | 主坑道の長さ1〜160マス。16は1チャンク相当、160は10チャンク相当です。/ Main-tunnel length, 1–160 blocks; 16 and 160 correspond to one and ten chunks of distance. |
| `branch_length_blocks` | 枝の長さ1〜7マス、枝坑道モードの既定値は6。/ Each branch is 1–7 blocks long, default 6 in branching mode. |
| `branch_spacing_blocks` | 枝の間隔3〜16マス、枝坑道モードの既定値は3。/ Branch interval, 3–16 blocks, default 3 in branching mode. |

坑道の断面は幅1・高さ2、床の高さは一定です。チャンクは**距離の単位**で、ワールドのチャンク境界への自動整列ではありません。枝の設定は枝坑道モードだけで指定します。左右7マスなら全幅は15マスです。完了時は主坑道の終点にいます。

Tunnels are one block wide and two blocks high at a fixed floor level. “Chunks” describe distance, not automatic alignment to world chunk boundaries. Branch settings apply only to branching mode. Seven blocks on each side give a 15-block-wide footprint. Successful completion ends at the main tunnel's far end.

「16マス直進して」のように枝道を求めない指示では`pattern`を省略し、直進坑道を使います。「主坑道16マス、4マス間隔で左右へ3マス」のように枝道を明示した場合だけ`pattern: "branches"`と枝の長さ・間隔を指定します。どちらも1つのActionの内部で最後まで進むため、AIが1マスごとに観測とAction開始を繰り返す必要はありません。

Omit `pattern` for ordinary straight-tunnel requests; straight is the default. Select `branches` and provide its length and spacing only when the user asks for side branches. Both layouts execute as one finite Action, avoiding a model round trip for every block.

## 入口と道具 / Entrance and tool

- 入口の**下段の壁**を公開観測し、その`target`、水平`face`、完全な`expected_state`を使います。その面の外側に隣接する足元セルへ移動し、セルの中央付近に立って開始します。/ Observe the lower entrance wall and use its target, horizontal face and complete state. Start near the center of the adjacent feet cell outside that face.
- 道具はホットバーにある鉄・ダイヤモンド・ネザライトのツルハシです。空きスロットと耐久を準備してください。/ Put an iron, diamond or netherite pickaxe in the hotbar, with an empty inventory slot and durability available.
- 対応は石・深層岩などと許可された通常鉱石です。砂・砂利、液体、コンテナ等の想定外の対象では停止します。赤石鉱石も初期対応範囲に含みません。/ The operation supports the listed natural stone and ordinary ore types; it stops on unsupported blocks such as sand, gravel, fluids and containers. Redstone ore is not included in this initial scope.

このnodeだけを`program.body`へ入れ、`movement`、`camera`、`block_break`を宣言します。入力形式・許可対象と総budgetは[公開catalog](MCMCP_MCP_Tool_Catalog.json)が正本です。通常の8回破壊・32マス移動上限を他のnodeへ広げるものではありません。

Use this as the only node in `program.body` and declare `movement`, `camera` and `block_break`. The catalog defines exact fields, supported materials and total budgets. Other operations retain their existing limits.

## 途中停止と確認 / Stopping and results

新しい掘進面が見えたら、MODが現在のfog・遮蔽・到達距離を確認します。破壊のserver ACKを待ち、次に歩く場所の床・通路・危険を再確認してから進みます。液体、危険な床、敵、道具や容量不足、解消できない観測欠測、期限、EscやOFFで止まり、Agent入力を解放します。壁の向こうの溶岩を先読みする機能ではありません。

Each newly exposed face needs fresh visibility and reach evidence. The mod waits for server acknowledgement and verifies local footing and passage safety before moving. Hazards, resource limits, unresolved observation gaps, deadlines, Esc or OFF stop the action and release its inputs. The mod cannot inspect lava hidden behind walls.

`agent_get_action`で進捗と終了結果を確認してください。坑道の完成は、必要な破壊の確認と経路の通過を意味します。初期版は回収完了を判定せず、`drop_collection=not_asserted`と記録します。資源の獲得が目的なら、前後の持ち物を別途確認してください。失敗・cancelでも、既に掘った部分は元には戻りません。結果不明の操作をそのまま再送せず、再観測して残りの範囲を新たに計画してください。

Poll `agent_get_action` for progress and completion. Completion means confirmed excavation and traversal. This initial version reports `drop_collection=not_asserted`; compare inventory separately when resource acquisition is the goal. Failed or cancelled actions do not undo completed work. Reobserve and plan the remaining scope instead of replaying an uncertain action.

## 開発時の固定受入 / Deterministic development acceptance

dev harnessには、直進16マス、直進160マス、主坑道16・枝3・間隔4、4列を掘った後に床穴の手前で止まる危険停止の4モードがあります。各モードは使い捨てクライアントで1回だけ初期化され、通常のMinecraft操作でActionを実行します。実行前の`status`と実行後の`oracle`は同じ`setupId`で結び、掘削範囲外の変更、2段の掘削列、移動数、最終位置、体力を有限範囲で照合します。

The dev harness provides four one-shot modes: straight 16, straight 160, branches 16/3/4, and a floor-gap stop after four excavated columns and three safe moves. Join the pre-run status, public Action result and post-run oracle by `setupId`; the checker rejects changes outside the bounded fixture.

```powershell
.\gradlew.bat runHarnessClient -PmcmcpFixturePhase5Mode=tunnel_straight16
# 他のmode: tunnel_straight160 / tunnel_branches / tunnel_hazard

# ゲーム内で実行前後に読み取り専用で取得
# /mcmcp_fixture phase5 tunnel_status
# /mcmcp_fixture phase5 tunnel_oracle

pwsh -NoProfile -File .\tools\eval\Test-McmcpTunnelAcceptance.ps1 `
  -GateResultPath '<gate-result.json>' `
  -FixtureStatusPath '<status.json>' `
  -FixtureOraclePath '<oracle.json>' `
  -OutputPath '<tunnel-acceptance.json>'
```

`Invoke-McmcpTunnelCapabilityGate.ps1`には`status`が返した`setupId`を`-FixtureSetupId`として渡します。160マスprofileは静的予約が約93分25秒のため、専用の120分評価期限と121分のHTTP上限を使います。欠測回復は`missing>0`かつ同じblock probeの`revalidated>0`をAction終端証跡で確認できた場合だけ成立し、低FPSやAction成功だけでは合格になりません。
