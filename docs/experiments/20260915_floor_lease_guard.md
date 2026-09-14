# 床延長の入力リース入口検査と失効診断

Issue #58。`extend_known_floor`が`floor_extension_input_lease_expired`で停止した。設置1個はserver確認済み、unknownは0、全体の進捗はその設置を一度だけ反映している。Actionは30tick・移動約0.644ブロックで停止し、20秒/400tick全体予算の消費完了ではない。`SERVER_DENIED_OR_DESYNC`は床延長の既存の失敗分類であり、この応答からserverが設置を拒否したとは判断しない。

## 切り分け

床延長の入力leaseは最大2秒。末尾に近づくと残tick数×50msまで短くなる。今回の残予算では2秒で、通常navigationの500msとは異なる。全phaseが末尾のheartbeatを通るが、tick入口で既に期限切れの場合にもcameraや施工phaseへ入ってから失効を検出する順序だった。

`RENDERER_RECOVERY missing=commit,dispatch;revalidated=commit,dispatch`は処理開始前の回復履歴である。この待機中に床延長の入力leaseは存在せず、待機時間をそのまま引き継ぐ構造ではない。物理パスからexportした通常latest/debugログは発生前の01:31:07が最終書込みで、発生時刻01:35:24付近のGC・遅延を示す記録はなかった。失効の遅延原因と、今回のどのphaseで起きたかは未確定。

## 変更

`MovementInputLease.validate`はownerと元期限を検査し、失効時に既存の入力を解放する。成功しても期限を更新せず、入力を再発行しない。床延長は毎tickの入口で既存leaseを検査し、失効ならcamera/施工処理に入る前に終了する。末尾のheartbeatも維持する。

失効時だけ、既存`failure.evidence`へ次の5項目を追加する。phaseはそのtickの入口時点で固定するため、施工成功後に次phaseへ遷移したtickでも、実際に処理したphaseを記録する。

| 診断 | 意味 |
| --- | --- |
| `floor_extension_phase` | orient / lean / aim / place / advance_orient / advance |
| `floor_extension_lease_check` | tick_entry / heartbeat |
| `floor_extension_lease_overdue_ms` | pauseを除く時計で元期限を超過したms |
| `floor_extension_lease_tick_gap` | 最後の成功heartbeat（初回は取得）からのclient tick差 |
| `floor_extension_executor_ms` | 今回のtick入口から期限検査時刻までのms。入口検査なら0 |

msは小数部切捨て。executor内時間は先行するruntime観測処理、tick間の待機・描画、検査後のcleanupを含まず、GCとCPU処理時間を単独で区別しない。成功時の診断生成・ログ追加はない。

2秒以下の入力期限、400tick/20秒の総予算、既存の失敗分類、入力解放とeffect回収を維持する。失効leaseの再取得・自動再送・設置済みブロックの再設置はしない。処理の途中で遅延が起きる可能性は残るため、遅延そのものの解消と区別する。

## 検証と再開

単体試験は非更新検査、期限ちょうど、pauseを除く時計、nanoTime wrap、owner検査、解放失敗後のcleanupを確認する。床延長の入口順序・末尾heartbeatと、失敗前のeffect回収・診断配送はruntime契約試験で検査する。全source checks、独立レビュー、隔離Dockerの通常床延長・失効停止・復旧結果は完了後に追記する。

元現場ではconfirmedの新床と安全な支持・経路、同session/READY/健康状態、台帳のconfirmed/next/pendingを再観測して新規計画する。位置合わせで別途発生した`unverified_actual_movement`→`replanned_route_remaining_occurrence`は、再計画した移動を当初のoccurrence残枠へ収められないための停止である。30秒/600tickを全て消費したことや入力lease失効を意味しない。今回の変更でnavigation予算や移動検証を緩和しない。
