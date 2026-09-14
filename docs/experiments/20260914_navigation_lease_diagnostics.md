# 移動入力リース失効時の診断

Issue #56。通常移動が到着セル付近で2回停止し、既存のAction応答には`movement_lease_expired`だけが記録されていた。取得済み通常ログにも発生時刻付近の遅延・GC記録はなく、更新が遅れた原因は未確定である。到着後の安定待ちでも既存のheartbeatを呼ぶため、安定待ち固有の更新漏れは確認できていない。

## 変更

500msの入力watchdog、失効時の入力解放・失敗分類・Action予算を維持し、失効した場合だけ既存`failure.evidence`へ次の4項目を追加する。成功時にログや診断文字列を追加せず、期限延長・失効leaseの再取得・自動再送は行わない。

| 診断 | 意味 |
| --- | --- |
| `movement_lease_phase=driving/settling` | 失効を検出したのが移動出力か、到着の安定確認か |
| `movement_lease_overdue_ms` | 元の500ms更新期限を過ぎた時間。pauseを除くwatchdog時計で計測し、ミリ秒未満は切り捨て |
| `movement_lease_tick_gap` | 最後の成功heartbeat（初回はlease取得）からのAction tick差 |
| `movement_executor_ms` | 今回のexecutor tick入口からheartbeat直前までの経過時間。runtimeの先行する観測処理やtick間の描画・待機は含まない |

たとえばtick差1でexecutor内時間が短い場合、前回更新後から今回executor開始までの時間を調べる手掛かりになる。executor内時間が長くても、CPU処理とGC・OSによる停止を単独では区別できない。いずれも遅延原因の確定、設置成功、再送許可を表す値ではない。

## 検証方針

実際のMovementInputLeaseに制御可能な時計と入力portを与え、移動・安定待ち双方の期限超過時に入力を解放して診断を返し、入力を再発行しないことを確認する。正常heartbeatでは診断なしで元の500ms期限を更新し、解放が失敗した場合は同じleaseをcleanupで再試行できることも検証する。runtimeの公開失敗への接続と、出力直前の期限検査順序は既存の契約試験で確認する。

実機試験は許可済みの隔離Dockerで行い、通常移動と低い描画頻度の停止を別runで確認する。通常プロファイルの停止事例の原因究明・現象解消と、診断の配送確認を区別する。検証結果は実行後に追記する。
