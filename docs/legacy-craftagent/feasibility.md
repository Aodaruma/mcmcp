# 実現性と判断

## 判断

**PoCはGOです。** シングルプレイとマルチプレイの両方で動く、特定serverに依存しないclient-only NeoForge MODとして設計できます。

Client MODはMinecraftの通常input、interaction、画面操作と、通常プレイヤー境界内の観測を扱えます。LLMを高水準のplanner、MOD内routineを20 TPSのexecutorにすれば、丸石採掘だけでなく建築、農林業、資材管理まで段階的に拡張できます。

サーバー側MOD、OP権限、独自packetは必要ありません。ただし、client側でEntityやinventory値を直接変更する機能は実装しません。通常操作の結果をserver同期後に確認します。

## 成立する理由

| 領域 | 判定 | 根拠 |
|---|---|---|
| client-only MOD | 高 | NeoForgeはphysical client専用entrypointを分離できる |
| 通常input/interaction | 高 | client tick、KeyMapping、game mode、screen click経路を利用できる |
| MCP接続 | 中〜高 | Java SDK 2.0.0にStreamable HTTP server実装がある。埋め込みと依存隔離はPoCで確認する |
| 観測とsession memory | 高 | live observationとlast-knownをclient thread上で分離できる |
| block建築 | 中〜高 | 期待state、通常設置、server-confirmed postconditionで収束できる |
| craft/container | 中 | 型付きscreen handler、slot revision、server同期試験が必要 |
| 農林業 | 中〜高 | bounded regionとBlockStateに基づく決定論的routineと相性がよい |
| Entity interaction | 中 | finite right-clickは可能。捕獲・任意輸送はAIと地形により不安定 |
| Simple Voice Chat | 中〜高 | 2.6.22で状態取得と内部setMuted経路を実JAR・公式sourceから確認済み |
| Modpack互換性 | 中 | renderer、screen、key、Entity追加MODとの回帰試験が必要 |
| multiplayer運用 | 条件付き | 接続先ごとのrule、負荷、公平性、anti-cheat挙動は利用者が確認する |

## 想定作業

| 作業 | 実現性 | 条件 |
|---|---|---|
| 丸石製造機の採掘 | 高 | 定位置、対象block、期限、inventory監視 |
| 経験値TTの処理 | 中〜高 | 安全な処理槽、有限attack、装備/health監視 |
| 通常建築 | 中〜高 | block plan、資材、navigation、phase検証 |
| Redstone/機構 | 中 | BlockStateと設置順序、稼働後観測が必要 |
| 作物の収穫・植え直し | 高 | bounded farm regionとcrop state |
| 植林・伐採 | 中〜高 | 可視/記憶済み区画、drop回収、再植林 |
| craft/container整理 | 中 | allowlist screenと重複防止 |
| 畜産 | 中 | 餌やり等の有限interaction。誘導は確率的 |
| アイアンゴーレムTT | 中 | 設計と建築は可能。Mob捕獲・輸送はuser handoffを推奨 |

## アイアンゴーレムTTに対する境界

LLMへ任せられる範囲:

- 対象version向け設計の選択とphase分割
- 必要資材計画、craft、container transfer
- 基礎、spawn platform、water、hopper、kill chamber等の施工
- BlockStateとclearanceの差分検査
- 目視可能な敵対Mob、light、potential spawn surfaceの調査
- golem出現、回収containerへのoutput等の稼働確認

初版でuser handoffを推奨する範囲:

- 村人や敵対Mobの未整備地形での捕獲
- boat/minecartへの押し込み
- aggro、餌、POI、釣り竿等による確率的誘導

ユーザーが対象を収容し、経路とdestinationを封鎖した後の操車は、独立したexperimental gateとして検証できます。

## 観測の実現性

現在画面だけでは長時間建築に不足するため、次を分けます。

- 今回のFOV/LOSで確認した`current`
- 過去に見た、または自分の操作結果としてserver-confirmedとなった`last_known`
- 根拠のない`unknown`

観測したblockは全BlockState propertyを返します。壁越しの現在stateは読まず、一度見た情報を時刻と出所付きで覚えます。必要なら`survey_area`が通常操作で歩き、視点を回してmemoryを更新します。

このモデルなら、通常プレイヤーの記憶と移動による再確認を表現しながら、X-ray相当の現在情報を避けられます。

## リアルタイム性

LLMを毎tickのloopへ入れる構成は採りません。

- inner loop: MODが20 TPSでprecheck、input、server sync、postcondition、局所retry
- outer loop: LLMが秒単位で状態確認、資材・設計・手順を再計画

one-shotは単一tool callではなく、一度のユーザー依頼をLLMが複数routineで完遂することです。成功は確認済みpostconditionに限定し、unknownや推定を成功扱いしません。

## MCP version判断

公式MCP Java SDK 2.0.0が追従するprotocolは2025-11-25です。MCP 2026-07-28は公開済みですが、Java SDKの正式追従前提では設計しません。

PoCは次で固定します。

- MCP Java SDK 2.0.0
- MCP protocol 2025-11-25
- Streamable HTTP単一endpoint
- JSON-RPC messageはPOST
- 独立GET streamを提供しない場合は405
- server pushへ依存せず`get_routine` polling

SDK/client双方の対応とconformanceを確認後にだけ更新します。

## 元の検討から確定した修正

1. 外部Python/TypeScript sidecarを標準にせず、MCP serverをNeoForge MODへ埋め込む
2. 低レベルhold inputをMCPへ公開せず、有限の意味的routineへ閉じ込める
3. 長時間処理をHTTP requestへ保持せず、`routine_id`で分離する
4. 現在観測とlast-known memoryを分け、hidden現在stateを公開しない
5. `prepare_build`/`inspect_build`をstatelessな`compare_block_plan`へ統合する
6. `transfer_items`をtarget-state型にし、GUI全面禁止をtyped screen ownershipへ修正する
7. 汎用`transport_entity`を初版から外し、user-contained Entityの搬送だけを後期候補にする
8. failure reason、postcondition、reconcile、安全なfinalizationをone-shotの成立条件にする
9. Simple Voice Chat 2.6.22 adapterを隔離し、対象packでmute失敗時はfail closedにする
10. Base Modpackのversionを固定し、自動更新しない

## 主な未確定事項

- Java SDK 2.0.0と利用MCP clientの2025-11-25相互運用
- SDK/Servlet依存をshade/relocateしたJARの起動・終了・thread leak
- Minecraft 26.2でのnormal input/interaction APIとSodium/animation系MODの挙動
- glass、water、stairs、slab等を含むrender-independent visibility判定
- typed screen handlerとJEI/Sophisticated Backpacks等の競合
- Simple Voice Chat内部adapterのversion検査
- navigation、fall avoidance、server lag下のpostcondition確認
- multiplayer接続先ごとのruleと連続稼働limit

これらは新環境で能動自動化を有効にする前のcompatibility/safety gateにします。
