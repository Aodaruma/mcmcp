# 水中navigation・上陸のDocker検証（Issue #42）

Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25で、既知の水源内の移動と岸への復帰を検証した。ユーザー指定のaod-mimoid上のDocker検証cloneを使用した。

## 成立した範囲

Local Observation Volumeが確認したVanillaの水源と空気だけを通る水平・上下移動、および支持付きの岸への出入りを、既存の`navigate_to_known`で実行する。入力は通常の移動・JUMP・CROUCHで、速度を見て減速する。水中の到達点も公開`traversability.navigation_target`から無変換で取得する。

水中の通常のair減少だけによるreplanはnavigation中には行わず、危険な酸素量や他の危険に対する既存Recovery判定は優先する。READY待機中に自動遊泳や自動救助を始めない。

水流、bubble column、水草、waterlogged block、未知のMOD流体は経路として許可しない。今回の実機試験は、既知のプール内での移動と上陸の決定論的capability gateである。初見LLMの自律完遂、実海域での施工中の落水、酸素枯渇からの実機救助は未検証。酸素危険域がnavigation中もRecoveryを優先することはunit testで確認した。

## 実行条件と結果

- 製品JAR SHA-256: `ae3d308f610e0b5be714fc8d6d0c8bb20142c1ebea5c2e8f131ef3ed86c99bbb`
- fixture: `tools/eval/fixtures/water-navigation`。水源プールは深さ5 block、開始は岸の上。
- runner: `tools/eval/Invoke-McmcpWaterNavigationGate.ps1`。
- adminによる準備とMCPのUI ONはT0前。開始後は固定5 Toolだけを使用し、terminal後に停止状態確認・pause・復旧を行った。
- 各Actionは600 tick / 30秒 / 距離32 block、camera・interaction・破壊・設置の予算は0。
- Action内の水中到達は水平0.2 block・高度0.2 block以内の3安全tick。HTTPでの事後位置も水平・高度とも0.25 block以内を確認した。岸は通常の地上着地確認を使う。

| ケース | run10 | run11 |
|---|---:|---:|
| 岸から入水 | 14 tick / 成功 | 14 tick / 成功 |
| 水中で移動・下降 | 17 tick / 成功 | 17 tick / 成功 |
| さらに下降 | 19 tick / 成功 | 19 tick / 成功 |
| 水中で移動・上昇 | 21 tick / 成功 | 21 tick / 成功 |
| 水面へ上昇 | 23 tick / 成功 | 23 tick / 成功 |
| 岸へ上陸 | 37 tick / 成功 | 43 tick / 成功 |
| 同じ岸の地点を再指定 | 11 tick / 成功 | 11 tick / 成功 |

両runとも7/7成功。体力20を維持し、事後のair最小値は230、上陸後は300へ回復した。camera変更・world mutation・UNKNOWN effectは0。入水と上陸では各1回の有限replanを含む。再試行なしの完走や任意の水域の安定性を主張する結果ではない。

最後のActionはrun10が`2c9fb9cd-eb9a-436b-bcfb-19e92a7fb6fd`、run11が`be26f69e-680b-47d1-88ea-305ea4c5aba5`。両runで公開状態のREADY、非pause、全Action terminalを確認した。入力owner自体は公開Toolに露出しないため、直接のowner検査とは区別する。

## 検証中に修正した不具合

1. 上下候補で水平方向専用の非ゼロ制約へ違反し、観測が停止する問題。
2. 入水前のShiftによる端の保持、および水面直上をプール底までの危険な落下と扱う問題。
3. 水中の慣性を考慮しない深度・水平制御と、自然沈下で受付時の姿勢照合に失敗する問題。受付例外は単独navigation、同一セル内0.25 block・5 tick以内、同じ向き・eye heightに限定し、セル全体の距離誤差を先に予約する。
4. Agent速度の記録が水抵抗を受けず、終了時に過剰に引かれて逆方向へ動く問題。Vanillaの水抵抗、重力調整、潜水入力、岸への加速へ追従する。
5. 岸への通常の跳び上がりを高度誤差として拒否する問題。支持付き着地点への検証済みの跳躍範囲だけを認める。同じ岸の地点を再指定する空経路も、地上の着地判定へ分岐させる。

run01〜run09の失敗記録を保存し、合格記録と混在させていない。途中の診断で事後位置の許容幅を広げたrun08があるが、最終run10/11は0.25 blockへ戻している。

## ソース検査・復旧

`pwsh -File tools/check-source.ps1`でJava unit、harness、admin bridge、harness isolation、build、Python transport 14件、building ledger/recovery、capability mockが合格。新規の境界・距離予約・入力・速度追跡・Minecraft bytecodeの呼出箇所も対応試験へ追加した。

検証前のsave 61ファイルを全件hash照合して復旧し、元のJAR群とinstance.cfgもhash一致を確認した。検証containerを停止し、元containerは停止のまま維持した。元のゲームプロファイルへの製品JAR差し替えは行っていない。

remote artifactは検証lab配下の`eval-artifacts/20260914-water-navigation-r1`。各runのAction、事後位置、air、trace、復旧receiptを保持する。公開資料には認証情報やsave本体を含めない。
