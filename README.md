# Minecraft NeoForge MCP Automation

Minecraft 26.2 / NeoForge 26.2向けに、通常のサバイバル操作だけを使う汎用クライアント自動化をMCP経由で制御するための設計リポジトリです。シングルプレイとマルチプレイの両方を対象とし、「くらふとぶ！ v01.2」を最初の互換性確認環境にします。

現時点は設計段階で、実行可能なMODはまだ含まれていません。

## 結論

技術的には実現可能です。ただし、LLMをMinecraftの20 TPS制御へ直接入れず、役割を分離します。

- LLM: 目標の分解、設計、routine選択、失敗後の再計画
- クライアントMOD: 毎tickの視点・移動・操作、サーバー同期確認、局所retry、安全停止
- Minecraftサーバー: 通常どおり最終的なゲーム状態を決定

サーバー側MOD、OP権限、独自packetは使いません。移動・採掘・設置・クラフト・コンテナ操作は、通常プレイヤーと同じ経路で行い、成功はサーバー同期後の状態で確認します。

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
| MCP Java SDK | 2.0.0を候補としてPoCで固定 |
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

## 段階導入

1. 読み取り、観測記憶、MCP transport、緊急停止
2. その場から動かない`stationary_break`
3. 有限のblock interaction、移動、postcondition検証
4. `compare_block_plan`と`apply_block_plan`による建築
5. クラフト、コンテナ、農林業、食事、睡眠
6. one-shot orchestration、安全な完了処理
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
