# v2 地図アート利用時の修正

この文書は開発候補 `merchants-r4` の追加仕様をまとめる。2026-10-10 03:38 JST、利用者の依頼で通常の「くらふとぶ！-v01.2」へ導入した。Prismからの起動と公開APIの照合も完了している。

## 修正内容

- **エリトラ装備での段差移動**：自動移動がジャンプを再入力したとき、意図せず滑空へ切り替わる処理を抑止する。エリトラを外す必要はない。エリトラ飛行そのものの経路探索は未対応。入力所有がない手動操作には適用しない。
- **レバー・ピストンの観測**：実際に見える `lever` / `piston` / `sticky_piston` の `state` に、向き・取付面・ON/OFF・伸長状態を返す。収納内容・block entity NBTなどの非表示情報を追加公開しない。設置対象の許可とは独立。
- **ディメンション移動**：同一接続内で別dimensionへ移った場合、通常のMCP ON意図を一度だけ引き継ぐ。古いjob・入力・画面・参照は終了し、新sessionの観測と新しいjobが必要。接続同一性、制御epoch、停止epoch、入力解放の完了を照合する。OFF・切断・死亡復活・停止競合・後始末未完了・評価用leaseは引き継がない。マルチプレイの一時許可は同じ接続に限定し、能力を元の範囲より増やさない。

## スクリプトの結果出力

```javascript
report(value=observe(kind="block", x=305, y=101, z=303));
let player=observe(kind="player");
report(value={label:"after_move", player:player});
```

`agent_get_action(action_id=..., include_result=true)` の最終 `result.reports` に呼出順で保存する。登録済みJSON/CSVから選んだ値、観測結果、操作結果、通常のデータを返せる。データは不変のコピーとして保持し、文字列を命令として実行しない。ファイル書込み・ネットワークアクセスは追加しない。

1 scriptあたり最大32件、合計512ノード（キーを含む）、深さ16、UTF-8 JSON合計16 KiB。構造・文字列の大きさをシリアライズ前にも制限し、共有配列を指数的に展開しない。コピーはwork予算と取消確認の対象。超過時はscriptを失敗で停止し、それ以前のreportを残す。ゲーム操作の取消・後始末が済むまで最終結果を公開しない。

## 検証

aod-mimoidの専用Docker・単一所有者の隔離worldで、次を確認した。

- エリトラ装備で1段上の足場へ移動成功。server側でも着地と `fall_flying=false` を確認。
- Overworld → End → OverworldでONを維持し、session IDを毎回更新。移動先の足場・体力を確認。
- UIで明示的にOFFにしてから同じ往復を行い、両移動先でもOFFを維持。
- レバーの `powered=true`、通常／粘着ピストンの向きと `extended=false` を観測APIから取得。
- scriptの `report(observe(...))` 相当が、レバーの状態を `result.reports` に返す。
- 死亡後の復活でOFF。試験準備の初回End足場が未ロードだったため落下が発生し、fixtureに移動先chunkのロードを追加して往復を再試験した。

Java 1,690件、harness 14件、admin bridge 28件、計1,732件の試験とbuildが成功。単体試験ではOFF／停止競合・接続変更・同dimension復活・後始末失敗・reportの共有グラフ／容量／不変性を検証している。実際のエンドポータル突入中のjob、マルチプレイ転送、Waystones経由の移動は実機未検証。

候補JAR SHA-256：`3a6e9fe6028b5e7a4d9d0cd509fef395ad306407c3679ab1769b44dacf9bd720`。実行記録はローカル `artifacts/v2-merchants-20261010` と同名のリモート評価ディレクトリに保存する。認証情報やworldの内容をGitへ追加しない。

2026-10-10 03:26 JST、今回の実機試験後も元world 61・設定3・JAR2の計66ファイルをハッシュ一致で復元し、fixtureを退避、検証containerを停止した。PowerShell／Python source checksも成功した。

## 通常プロファイルへの導入

Prism Launcher起動済み・Minecraft終了済みを確認し、旧R7のJARをバックアップしてから、検証済み候補の製品JARだけを交換した。ネイティブの物理path、導入前後とバックアップのSHA-256、製品JARが1個であること、検証用harness／admin bridgeの不在を確認した。

03:40 JST、Prism GUIから起動したゲームのMCP endpointについて、公開15ツールの全input/output schemaがcatalogと一致した。endpointの所有JVMの作業ディレクトリと起動時刻も照合し、対象profileが交換後に起動したことを確認。タイトル画面・MCP OFFで引き渡し、通常worldには入っていない。

ローカル記録と旧JARの保存先は `artifacts/v2-merchants-install-20261010`。`normal-installation.json`、`normal-verification.json`、`normal-before/`にそれぞれ導入・API照合・バックアップを保存した。

## 残件

- Sophisticated Backpacksのネザライトバッグで報告された `storage_layout_unsupported`：未解決。実際のupgrade・スロット構造に合わせた隔離再現が必要。判定を一括解除する変更はしていない。
- Waystonesの行き先一覧／選択：公開provider未実装。
- raw入力がポータルや梯子の縁で止まる報告：原因は未断定。強制sneakの追加はなく、入力の成功は到達成功を意味しない。衝突直前の足場判定との関係を切り分け中。梯子の上下は観測した次の段への `agent_move` を案内した。
- 金床・エンチャント、MOD収納の対応拡大、施工全体の保存・再開は引き続き残件。取引の追加は[取引仕様](PUBLIC_API_V2_MERCHANTS_20261010.md)を参照。
