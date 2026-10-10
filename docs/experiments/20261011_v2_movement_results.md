# v2移動・結果返却：隔離検証（2026-10-11）

Issue #84 / Draft PR #85。通常ゲームは地図アート担当が所有し、本試験では操作・差し替えを行わない。利用者の許可済みaod-mimoid Docker、固定fixtureだけで実施した。

## 実装と自動試験

- 同じ高さの1マス穴を越えるcardinalジャンプ、最大5blockの隣接落下を、共有観測へ混入しないAction固有の局所経路として追加。通常の入力所有・各tickの衝突照合を維持する。
- `max_fall_damage` はmove全体の累計。回復・再計画では返還しない。Vanilla属性・落下耐性・防護・耐性を予測し、packetの損傷原因と体力・吸収の減少を照合する。
- 短いActionはHTTP応答を開始してから最大20秒待ち、結果を返す。長いscript/範囲操作は既定でID。`agent_get_action` は既定でresultを含む。通信切断の取消を実TCPでも確認する。
- 小数座標と `coordinate_mode` を追加し、最後の位置合わせ・到着判定・結果座標に反映。
- 明示portal進入をNetherへ拡張。到着の安全が0.5秒持続することと入力解放を確認する。待機中のvanilla移動tickがAgent所有を復活させないよう修正。
- ローカル自動試験はJava 1,796、harness 14、admin bridge 28（計1,838）、失敗・skipなし。`verifyHarnessIsolation` とbuild成功。最終source checks・CIは後続で追記する。

## 実ゲームで確認済みの経路

証拠はローカル `artifacts/v2-storage-arrival-20261011` と、隔離labの同日同名runrootに保持する。認証情報・private logは配布しない。

| 条件 | 結果 |
|---|---|
| 1マス穴、同じ高さ | 成功、1 jump、損傷0 |
| 2マス穴／頭上の低い穴 | jumpせずtick_limit、損傷0 |
| 3block落下、既定0 | 成功、損傷0 |
| 4block落下、既定0 | 落下せずtick_limit、損傷0 |
| 4block落下、上限1 | 成功、実測1 |
| 5block落下、上限2 | 成功、実測2 |
| 落下耐性IV、4block、上限0.52 | 成功、実測0.5200004578 |
| 落下耐性IV＋防護IV、4block、上限0.36 | 成功、実測0.3600006104 |
| 4block落下3か所、累計上限2 | 2回まで進み、3回目の前で停止、実測2 |
| 同じ3か所、累計上限3 | 成功、3 drops、実測3、予算消費3 |
| 穴越え後 `(307.85,106,305.15)`、tolerance 0.1 | 成功、実位置 `(307.7957500611,106,305.1748281851)` |
| 上記moveの同期返却 | 42操作・約2.15秒でsucceededとresultを直接取得 |
| IDだけを渡すget_action | include_result省略で結果を取得 |
| wait_timeout_ms=0、途中取消 | queued即時返却、cancelled終端、READY/jobなし |

R1は `nav-r1-*.jsonl`、R3は `nav-r3-*` に保存。R3 product SHA-256は `9cf9b5f38fad0fa83551d01fb8b138d60179a9f4f5e9412ec9ad22522c618ec3`。

## Portalの追試で見つかった問題

R3ではNetherへの新session到着と入力解放は成功したが、約10秒後にfixture外からの溶岩が流入して死亡した。到着自体の誤検出とは区別し、固定fixtureを密閉した。R4では安全確認待ちに `portal_arrival_input_owned` で停止した。AgentInputStateが待機tickでも追跡identityを再作成する原因を修正し、自動回帰試験は成功した。

最終修正JARでのPortal往復、半ブロック・水中・飛行・乗り物の小数位置合わせは、この記録時点では追試前。R3の一部成功を最終候補の全項目合格とは扱わない。初回End帰還のクレジット、MOD独自Portal、MOD独自損傷式、遠隔サーバーの大きな遅延は未確認。

## 接続と復元

06:34 JSTにSSHのCloudflare認証が失効。既知の空lock退避と単独quiet loginではまだ再認証できず、許可済みaodのtmp診断チャットからhostname・サービスRunning・baseline66件・対象container稼働を確認した。通常ゲームには触れない。検証world・設定・JARの66ファイル復元、Docker停止、lease終了の結果は後続で追記する。
