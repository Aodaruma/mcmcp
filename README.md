# Minecraft NeoForge MCP Automation

Minecraft 26.2 / NeoForge 26.2向けに、通常のサバイバル操作だけを使う汎用クライアント自動化をMCP経由で制御するクライアントMODです。シングルプレイとマルチプレイの両方を対象とし、「くらふとぶ！ v01.2」を最初の互換性確認環境にします。

## 現在の実装状況

- Phase 0（設計と安全境界）: 完了
- Phase 1（読み取り、観測記憶、loopback MCP、緊急停止）: 完了（実装・全受入ゲート合格）
- Phase 2（`stationary_break`、routine lifecycle、Simple Voice Chat安全化）: 完了（実装・全受入ゲート合格）
- Phase 3（有限semantic action）: 完了（実装・全受入ゲート合格）
- Phase 4（`apply_block_plan`による局所block plan施工）: 完了（実装・全受入ゲート合格）
- Phase 5〜6（inventory・農林業・one-shot）: 設計済み、未実装
- Phase 7（収容済みEntity搬送）: v1対象外のexperimental設計

MCP tool surfaceは、`get_status`、`get_snapshot`、`compare_block_plan`、`list_routines`、`get_routine`、`start_routine`、`cancel_routine`、`emergency_stop`の8つを維持しています。`stationary_break`、`navigate_to`、`break_block`、`place_block`、`interact_block`、`interact_entity`、`apply_block_plan`の7 routineをschemaとcatalogへ公開しています。`get_recipes`はPhase 5の未公開候補であり、現在のtool数には含みません。

Phase 4完了時点で、Java 25のunit/integration test 283件、harness test 8件（いずれも失敗0）、GameTest 4/4を通過しました。development fixtureでは実際の3-cell施工、waterlogged slab、directional stairs、hopper、既達成skipに加え、資材不足・hidden必須cell・実行中divergenceのfail-closedを確認しています。fixtureなしのproduction Prism実Modpackでは、正式MCP handshake、8 tools・7 routines、Simple Voice Chat接続、死亡時のlock復帰、正常な全dimension保存・shutdown、8765 listener解放を確認しました。production互換性確認とsemantic施工確認は分離し、実施工の証跡にはdevelopment live gateを使用しています。

## 結論

技術的には実現可能です。ただし、LLMをMinecraftの20 TPS制御へ直接入れず、役割を分離します。

- LLM: 目標の分解、設計、routine選択、失敗後の再計画
- クライアントMOD: 毎tickの視点・移動・操作、サーバー同期確認、局所retry、安全停止
- Minecraftサーバー: 通常どおり最終的なゲーム状態を決定

サーバー側MOD、OP権限、独自packetは使いません。移動・採掘・設置・クラフト・コンテナ操作は、通常プレイヤーと同じ経路で行います。block actionはprediction ACKとサーバー由来の完全なBlockStateで確認し、positive ACKがない通常移動は入力停止後の安定と補正packet不在を組み合わせた`server-reconciled`として確認します。自動破壊は`minecraft:cobblestone`、`minecraft:stone`、`minecraft:dirt`、`minecraft:obsidian`、`minecraft:grass_block`の5 IDだけを許可し、BlockEntity、流体を含むstate、未知・MOD blockはpacket直前にも拒否します。設置時のsupportもcanonicalな`stone / smooth_stone / cobblestone / dirt / grass_block / obsidian`の6 IDに限定し、隣接blockの通常useが設置より先に実行されることを防ぎます。

Phase 4の`apply_block_plan`は、移動を所有しない1回1phase・最大64 cellの局所施工です。各cellは`expected_before`と`expected_after`へruntime registry上の完全なBlockStateを指定し、`verify_only / break_to_air / place / replace`だけを扱います。相対座標とstateはmirror後にY軸時計回りrotationを適用し、開始前・各操作直前・操作後・最終確認をcurrent-onlyで行います。確認対象は要求したtarget cellであり、通常vanilla処理が発生させる隣接block更新やgame eventまで「無変化」と保証するものではありません。

## 到達目標

最終目標は丸石採掘専用マクロではなく、LLMが次の汎用能力を組み合わせて依頼を完遂できることです。

- 建築と機構施工: 相対座標のblock plan、BlockState、工程別の差分検査
- 資材準備: 採取、クラフト、コンテナ間の`transfer_items`
- 農林業: 作物の収穫・植え直し、植林・伐採・再植林
- 畜産: 可視Entityへの有限interaction。捕獲・任意搬送は別扱い
- サバイバル維持: 食事、登録済みベッドでの睡眠、安全場所への退避
- 完了後処理: 作業結果の検証、入力解放、安全化、ユーザー設定時のみ通常切断

アイアンゴーレムTTのような依頼では、LLMが設計、資材、建築、稼働確認を担当できます。一方、敵対Mobや村人を未整備の地形で捕獲・押し込む処理は成功率と危険性が高いため、初版はユーザーへhandoffして終了します。ユーザーがボート、トロッコ、封鎖セルなどへ収容した後の操車も、Phase 7のexperimental gateに合格するまで公開しません。

## one-shotの意味

one-shotは「1回のMCP tool callで必ず成功すること」ではありません。ユーザーが目標を一度伝えた後、LLMが複数の型付きroutine、状態照会、差分検査、再計画を組み合わせて進めることを指します。

保証する結果は次のいずれかです。

1. 必須postconditionをサーバー同期後の観測で確認した完了
2. 完了と断定できない理由、現在状態、再計画材料を伴う安全な停止

未観測、推定、古い記憶だけを成功扱いしません。

## 基本境界

- クライアント専用MOD。特定サーバーの機能や許可を実装要件にしない
- 生のキー長押しや任意コード実行をLLMへ公開せず、有限の意味的routineだけを公開
- 現在の通常観測と、過去に見た・自分の操作結果として確認した情報を区別して扱う
- 壁越しの現在状態、未ロード領域、サーバー内部状態は取得しない
- Simple Voice Chatは自動化中にミュートし、終了時に元の状態を安全に復元
- ローカル緊急停止、短い操作リース、切断・死亡・実入力時の即時停止
- MCPは`127.0.0.1`限定、Origin検証、Bearer token付き
- ローカルワールドと複製したPrism Launcherインスタンスで検証してから展開

## 対象バージョン

| 項目 | 確認値 |
|---|---|
| Modpack | くらふとぶ！ v01.2 |
| Minecraft | 26.2 |
| NeoForge | 26.2.0.59 |
| Java | 25.0.1 LTS |
| Simple Voice Chat | 2.6.22+26.2 |
| MCP Java SDK | 2.0.0（Phase 1で固定） |
| MCP protocol | 2025-11-25をPoC対象として固定 |

実環境のMinecraft関連値はPrism Launcherインスタンス、導入済みJAR、起動ログから確認した固定値です。MCP 2026-07-28は公開済みですが、現行Java SDK 2.0.0が追従するのは2025-11-25までのため、SDKとクライアントの相互運用を確認せずに新しいprotocolへ上げません。

## 設計文書

- [実現性と判断](docs/feasibility.md)
- [アーキテクチャ](docs/architecture.md)
- [観測・記憶モデル](docs/observation-model.md)
- [自動化runtimeと回復](docs/automation-runtime.md)
- [安全モデル](docs/safety-model.md)
- [MCPインターフェース](docs/mcp-interface.md)
- [くらふとぶ！互換性](docs/compatibility-kurafutobu-v01.2.md)
- [Simple Voice Chat連携](docs/voice-chat.md)
- [テストと段階導入](docs/testing-and-rollout.md)
- [参照資料](docs/sources.md)

## 開発と検証

Java 25を使用します。Windowsではリポジトリ直下から次を実行します。

```powershell
.\gradlew.bat clean test harnessTest harnessJar build
.\gradlew.bat runGameTestServer
.\gradlew.bat runHarnessClient
```

- `test`: MCP transport・入力検証・観測記憶・block plan差分・停止処理などの単体/統合テスト
- `harnessTest`: production classpathから分離したfixture/autorun設定の単体テスト
- `runGameTestServer`: 実際のMinecraft BlockStateを使うfixtureの自動GameTest
- `runHarnessClient`: 本体とfixtureを読み込む、破棄可能なシングルプレイヤー手動検証環境

`runHarnessClient`でワールドを作成後、`/craftagent_fixture load`で固定テストarenaを準備できます。Phase 3用には`/craftagent_fixture phase3 navigate|break|place|lever|cow|reset`、Phase 4用には`/craftagent_fixture phase4 all_satisfied|mutations|waterlogged|directional_stairs|hopper|shortage|divergence|hidden`で固定scenarioを切り替えます。fixtureの安全境界とコマンドは[fixture README](src/harness/README.md)を参照してください。通常のPrism Launcher instanceではfixtureを使わず、本体JARだけを導入します。詳しいgateは[テストと段階導入](docs/testing-and-rollout.md)にあります。

### 生成物

- `build/libs/craftagent-0.1.0-SNAPSHOT.jar`: 本体クライアントMOD。MCP実行依存をJar-in-Jarで同梱し、fixtureコードは含まない
- `build/libs/craftagent-0.1.0-SNAPSHOT-test-harness.jar`: 開発専用fixture MOD。本体コードは含まず、通常instanceやマルチプレイ環境へ導入しない

ファイル名のversionは`gradle.properties`の`mod_version`に従います。

## 段階導入

1. [完了] 読み取り、観測記憶、MCP transport、緊急停止
2. [完了] その場から動かない`stationary_break`
3. [完了] 有限のblock interaction、移動、kind固有postcondition検証
4. [完了] `compare_block_plan`と局所`apply_block_plan`による建築
5. [未実装] クラフト、コンテナ、農林業、食事、睡眠
6. [未実装] one-shot orchestration、安全な完了処理
7. 収容済みEntityの搬送をexperimentalとして個別検証

各段階は前段の停止・同期・回復試験に合格してから有効化します。

## 初版で公開しないもの

- テレポート、飛行、リーチ延長、速度・クールダウン・衝突判定の回避
- 壁越しの現在状態、未観測blockの一致oracle、未ロード領域、Entity ESP
- クリエイティブ相当のアイテム生成、任意packet、任意command・chat送信
- 任意Java呼び出し、reflection、script実行、自由文goalや汎用workflow DSL
- 汎用`transport_entity`、自動Mob捕獲、釣り竿による搬送、無期限の自動戦闘
- 自動ログイン、自動再接続、ユーザー設定なしの自動切断
- ユーザーの緊急停止や実入力を無視する自動化

## Ponytailについて

PonytailはMinecraftやMCPの実行依存にはしません。実装レビューで、既存機能と標準機能を優先し、必要最小限の機構を選ぶ判断補助としてのみ利用します。安全境界、観測の出所、postcondition検証、エラー処理は簡略化の対象外です。

## ライセンス

当面はprivate prototypeです。配布条件を決めるまでライセンスは付与しません。
