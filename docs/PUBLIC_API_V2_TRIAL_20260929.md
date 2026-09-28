# 公開API v2 試用メモ（2026-09-29）

Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25向けの試用版です。mainへの統合・Release公開はしていません。操作前にゲーム内のMCPをONにします。停止はEsc・MCP OFF・`agent_cancel_action`で行えます。

## 導入済みJAR

このPCのPrismLauncher **「くらふとぶ！-v01.2」**には、2026-09-29に差し替え担当が実体側へv2を導入し、Prism GUIからの再起動・再接続後に公開13ツールとschema 2を確認しています。[訂正・確認記録](experiments/artifacts/20260929-v2-trial/installation-correction.json)

- 導入先: profile内の`minecraft/mods/mcmcp-neoforge-26.2-0.1.0-SNAPSHOT.jar`
- 実体側の旧版: SHA-256 `f4b6b782d99cfb94e6aef8143211c08de02045be51e26f81b2fc09ea4a4ee1bc`。差し替え担当が別名でバックアップ済みです。
- 新JARのSHA-256: `03c7943f7fcd78f06021db2224a5df5b47fd77a2bc98bd99c32c5a930fadece1`

**初回の導入完了報告を訂正します。** Codexからの通常AppDataパスは仮想化領域の`LocalCache/Roaming`へ転送されており、初回のhash確認はネイティブPrismが読む実体への導入を証明していませんでした。初回の`mcmcp-backups/20260929-public-api-v2/`と旧版hash `ca47984f…`も、その仮想化領域での記録です。[初回receipt（確認範囲を訂正）](experiments/artifacts/20260929-v2-trial/installation.json)

以後は、ファイルハンドルからの物理パス確認・実体側hash・重複製品JAR確認に加え、再起動後の公開API確認までを導入完了条件とします。差し替え・再起動・再接続・MCP ONは指定の差し替え担当が行います。実体側の起動と短いAPI試験は確認済みですが、全MOD機能の互換性を網羅した検証ではありません。

## 使える範囲

| 機能 | 今回の範囲 |
|---|---|
| 観測・移動・施工 | 状態／見えるblock・entity、座標移動、直方体の破壊・設置、持ち替え、結果確認 |
| 移動中の障害物処理 | `clear_path:true`で有効。既定はfalse。水平移動の局所的な破壊、材料を明示した足場補充 |
| 遠方への操作 | block・収納・観測済みentityへ接近して通常操作。`advance:false`で接近を禁止 |
| 停止条件 | 座標、見えるblockとstate、所持item数、画面種類。`move`／`input_sequence`で使用 |
| 特殊な設置 | ベッド・扉・二段植物の両cellを確認 |
| メニュー | 開封→内容取得→必要ならVanilla収納のShiftクリック→確認→閉鎖。slot・item・現在の数量を指定 |
| 所持品・収納 | inspect、hotbar交換、数量投棄、座標収納take/store、対応版Sophisticated Backpacks |
| 施工の保存・再開 | 以下のPC側runner。1アンカーずつ進捗・操作ID・確定結果を保存 |
| 操作の合成 | 有限loop・条件分岐・小関数の制限付きscript、有限の論理入力列 |

行動ツールが返す`queued`は受付です。返されたIDを`agent_get_action({action_id:"…",include_result:true})`で照会し、`succeeded`／`failed`／`cancelled`まで確認してください。失敗・取消は変更済みのworldを元に戻しません。接続設定は既存のまま使用し、ツール一覧が古い場合はMCPクライアントを再接続してください。旧`agent_start_action`は公開しません。

## 保存・再開の例

任意の作業フォルダに`plan.json`を保存します。`steps`は最大16,384件で、各項目は1アンカーの`agent_place_block`引数です。ベッド等は1アンカーで相方も含みます。記述順に施工します。

```json
{"version":2,"dimension":"minecraft:overworld","steps":[
  {"x":4,"y":65,"z":8,"block":"minecraft:stone","advance":true},
  {"x":5,"y":65,"z":8,"block":"minecraft:stone","advance":true}
]}
```

repositoryのworktreeでPowerShell 7.4以上を使います。tokenの値は貼り付けず、対象profileの`minecraft/config/mcmcp/mcp-token`へのパスを指定します。

```powershell
./tools/building/Invoke-McmcpV2Construction.ps1 `
  -PlanPath 'C:/作業/plan.json' -CheckpointPath 'C:/作業/progress.json' `
  -TokenPath 'C:/対象profile/minecraft/config/mcmcp/mcp-token' -MaxSteps 32
```

同じ引数で再実行すると保存済みの次の場所から再開します。`-StatusOnly`は保存状況だけを読みます。既定は1回64アンカー・900秒。計画の書換えや同時実行は拒否します。完了済みblockが後から壊された場合の自動修復は別計画で行います。

再ログイン後は、**同じworldであることを確認して**`-AcceptWorldSessionChange`を付けます。session IDだけでは再接続前後のworld同一性を証明できないため、自動で受け入れません。

応答を失った操作・失敗・取消は保留して自動再送しません。保存ファイルの`pending`とworldを確認し、実行中jobを停止・終了させたうえで、必要に応じて`-ResolvePending Complete`（現物確認済み）または`-ResolvePending Retry`（再試行を明示）を指定します。runner終了時点でゲームのjobが残っている場合も、そのIDで照会・取消してください。

## 残る制約

- 任意MODのメニュー／収納、クラフトや独自ボタン、カーソル操作は未対応です。新品backpackは通常UIで一度開いて初期化してください。
- 自動障害物処理は水平の局所処理です。液体処理、階段・縦穴、任意形状の自動施工は含みません。
- 設置面が他のblockで遮られる配置では、安全に停止する場合があります。施工順や立ち位置を変えて対応してください。
- 道具の耐久・Silk Touch／Fortune条件、破壊dropや消費材料の詳細台帳、全MOD組合せ・通信遅延の検証は残っています。
- この導入済みJARの入力列は合計1,200 tick、scriptは最大72,000 tickです。[全行動の長時間・多回数実行](PUBLIC_API_V2_LONG_EXECUTION_20260929.md)を開発branchへ追加しましたが、新JARの実機検証・通常profileへの導入はまだです。

引数の詳細は[クイックガイド](MCMCP_Public_API_v2_クイックガイド.md)と[Tool Catalog](MCMCP_MCP_Tool_Catalog.json)を参照してください。[実機試験記録](experiments/20260929_v2_trial_local.md)には9フェーズの結果、失敗からの修正、検証環境の復旧をまとめています。
