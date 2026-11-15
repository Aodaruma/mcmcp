# v2 クリエイティブ／スペクテイターの飛行・降下

2026-09-30。利用者指定の隔離DockerでMinecraft 26.2 / NeoForge 26.2.0.59 / Java 25を使用。通常の「くらふとぶ！」は操作・差し替えしていない。

## 利用方法と実装範囲

既存の`agent_move`に対応し、公開14ツールと引数schemaを維持する。

```json
{"direction":"up","distance":9,"max_ticks":600}
{"direction":"down","distance":9,"max_ticks":600}
{"x":313,"y":101,"z":301,"max_ticks":900}
```

- creativeではVanillaの飛行権限を確認し、通常の二度押しジャンプで飛行を開始する。上下と水平を含む局所経路を選び、プレイヤーの体が衝突しない経路を通る。空中で終了した後も飛行状態を維持する。
- 足場のない高低差は、飛行で制御して降下する。歩行時の落差上限は適用しない。落下中からの開始も確認した。意図的に飛行を切って自由落下する専用操作は追加していない。
- spectatorではVanillaのすり抜け経路を使う。非衝突の移動経路にも、所有入力・期限・現在revision・ロード済み範囲・移動距離の最終確認を適用する。
- 空中経路は半径6の局所観測、world border、高さ範囲、既存の総tick・実時間・距離予算に従う。飛行権限をAPIで付与せず、survival/adventureの地上安全条件を維持する。
- 上下入力による慣性を追跡し、到着範囲でAgentの入力・慣性を解放した後、位置と局所安全が連続して安定したことを確認する。モード変更・取消・Esc・予算終了でも解放する。外部からの動きや重力は別に扱う。
- 3Dの密な経路mapで候補探索が上限を使い切る問題を、区間距離を満たす候補への絞り込みで修正した。保存8,192 edge・A*2,048展開・64候補の上限と、最大8回の再計画は維持する。

## 最終候補と実機結果

最終候補 **flight-r7** のSHA-256: `109dbb86f58ddaea37876a496f8caefac06b45b8d493cc5905b3740f0d990bd0`。

[fixtureと再現手順](../../tools/eval/fixtures/public-api-v2-navigation/README.md)。試験設備の配置とモード変更だけを管理用の通常チャットで行い、評価対象の移動・取消・状態取得は公開MCPを使用した。全移動で`clear_path`は省略（false）。上昇→水平→降下とspectatorの壁通過・降下は`auto_replan:false`。

| ケース | 終端 | 秒 | 再計画／停止理由 |
|---|---|---:|---|
| creative・地上から9 block上昇 | succeeded | 3.73 | `再計画 0 回` |
| creative・空中で12 block水平移動 | succeeded | 12.20 | `再計画 0 回` |
| creative・9 block降下 | succeeded | 2.76 | `再計画 0 回` |
| creative・足場の外へ進み10 block降下 | succeeded | 15.86 | `再計画 1 回` |
| creative・壁の周囲を迂回 | succeeded | 23.70 | `再計画 2 回` |
| spectator・壁を通過し4 block上昇 | succeeded | 13.20 | `再計画 0 回` |
| spectator・5 block降下 | succeeded | 1.59 | `再計画 0 回` |
| creative・高さ2 blockの通路 | succeeded | 4.75 | `再計画 0 回` |
| survival・dust/railのあるはしご上階 | succeeded | 6.25 | `再計画 0 回` |
| creative・落下中から開始して座標へ到達 | succeeded | 13.90 | `再計画 0 回` |
| creative・飛行中のAPI取消 | cancelled | 0.59 | `client_request` |
| creative・max_ticks:8 | failed | 0.45 | `tick_limit` |
| creative→survivalへのモード変更 | failed | 0.29 | `movement_unsupported_locomotion` |
| spectator・飛行中のAPI取消 | cancelled | 0.55 | `client_request` |
| creative・物理Esc | failed | 0.61 | `local_emergency_key` |

主要15ケースと停止準備の上昇2件、計17 jobの終端を確認した。成功した12移動すべてで、終端時のstateと1秒後を比較し、各軸の差0.02 block以内を確認。creative/spectatorの取消、tick上限、Escでも1秒後の位置を確認した。Escはポーズ解除後に比較している。モード変更では期待どおり停止し、その後のsurvivalの重力移動は位置保持の合否に含めない。

壁はx=306、y=101..115、z=298..305。creativeはz約306.5へ回って通過し、spectatorは壁内部のx=306..307、y=105台、z=302.5を通過したstateを記録した。各移動後の体力は20。落下試験はy=130へ準備後、開始直前のstateでy=123.406まで落下していたことを確認し、目的地y=112へ到達した。

[公開用結果抜粋](artifacts/20260930-v2-flight/results.json)にはfixtureの座標、終端、経過時間、停止後の差分だけを収録する。認証値、アカウント名、元world、画面、raw MCP transcriptは含めない。

## 試験中の修正

- r1/r2: 密な空中mapでの探索上限と、各高さへ合わせる入力の往復を確認。区間距離による候補の絞り込み、空中の距離予算、Vanillaの入力刻みを考慮した高度制御を修正した。
- r3: 到着位置で速度値だけが残り完了しないケースを確認し、実際の位置安定を判定する方式へ変更した。
- r4/r5: spectatorが目的地付近で小さく往復し区間期限に達した。待ち時間の追加だけでは解消せず、到着範囲で入力を解放してから安定確認するr6へ修正した。
- r6: 17 jobは期待どおり終了したが、入力解放後に追跡慣性が古いまま残り、最終cleanupで小さな位置ずれが生じた。r7で慣性を解放する順序を修正し、全成功移動にも1秒後の位置比較を加えて再実行した。
- 準備コマンドやhelperの引数不備によるrun、失敗後の開始位置から連続実行した降下は合格件数に含めていない。

## ソース検証・未確認範囲

`test harnessTest adminBridgeTest verifyHarnessIsolation build`が成功。Java 1,637件、harness 13件、admin bridge 28件、計1,678件でfailure/error/skipは0。PowerShell/Python source checksも成功。権限別の飛行可否、上下の入力刻みと飛行解除防止、3D到達、足場のない経路、密なmapの区間計画、離陸中の元予算、MixinのVanilla呼出箇所を検証した。

これはfixtureでの合格範囲。変更された飛行速度、任意MODの物理・collision、マルチプレイでの遅延・server補正、未ロードchunkやworld border/高さ上限への実機到達は追加確認として残る。UI OFFと次元変更は既存の停止経路・単体検証を継続利用し、今回の飛行専用実機ではAPI取消・tick期限・モード変更・Escを確認した。

## 復旧・導入

検証用Minecraftを通常終了し、containerを停止した。元world 61ファイルと設定・JARを含む65ファイルをすべてSHA-256一致で復旧し、fixtureの不在も確認した。[復旧receipt](artifacts/20260930-v2-flight/restoration.json)。

新JARは通常profileへ未導入。利用者の「後程置き換え」の指示に従い、差し替え用に保持する。PR #85はDraftのまま、main統合・タグ・Release公開は行わない。
