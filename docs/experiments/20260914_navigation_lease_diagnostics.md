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

実機試験は許可済みの隔離Dockerで行い、通常移動とCPU制限下の停止を別runで確認した。通常プロファイルの停止事例の原因究明・現象解消と、診断の配送確認を区別する。

## 検証結果

製品コード・独立レビュー対象は`2de541ce9bebc472dced0e3a1a07414f46d114e0`。製品JARのSHA-256は`17be6973e6109b7d1346591f143f12354883d402f6d61980d766c961bf89bc42`。記録の追記だけではJARを再生成していない。

- `tools/check-source.ps1`成功。Java通常試験1,299件、harness 13件、admin bridge 25件は失敗・errorとも0。isolation/build、Python 14件、外部runner/capability mockも成功。
- 独立レビューは指摘なし。移動・安定待ち両経路の失効、正常更新、解放失敗後のcleanup、公開失敗への診断配送をコード・契約試験で確認。
- DockerはMinecraft 26.2 / NeoForge 26.2.0.59、`maxFps:10`。T0前に`construction-materials` fixtureを適用し、run中は公開MCPのみで移動した。これは機能回帰試験であり、fresh評価モデルによる建築課題の完遂試験ではない。

| run | 条件 | 結果 |
| --- | --- | --- |
| run01 | CPU制限なし、近隣への移動 | 成功、terminal後READY |
| run02 | CPU 0.05、同じ高さの遠めの移動候補を選択 | 準備段階で候補なし。Action未送信。失効確認には算入しない |
| run03 | CPU制限解除、配送済みの平地候補を選択 | 成功、terminal後READY |
| run04 | 同じfixtureからCPU 0.05、平地候補へ移動 | 想定したリース失効と4項目の配送を確認、terminal後READY |
| run05 | CPU制限解除後にコンテナとMinecraftを再起動、同じ平地候補へ移動 | 成功、terminal後READY |

run04のAction `7d0f6998-f62f-4eb7-9fbd-a873d2d8156d`は`INTERNAL_ERROR` / `recoverable=false`となり、次を記録した。

```text
movement_lease_expired
movement_lease_phase=driving
movement_lease_overdue_ms=3302
movement_lease_tick_gap=1
movement_executor_ms=0
```

移動は発生しており、Actionの記録距離は約0.098ブロック、2tickだった。interaction・破壊・設置・effectは0、`resume_requires_reobservation=true`。終端後の全Action terminalとREADYを公開MCPから確認した。input ownerは公開surfaceに直接露出しないため、直接値の確認とは扱わない。安定待ち中の失効は単体試験で確認しており、Dockerでは移動中の失効だけを確認した。

終了後、検証ワールド61ファイルのhash、MOD、instance設定、optionsを復元確認し、検証コンテナを停止した。元コンテナは停止を維持した。CPUはDockerの`--cpus 0`が更新を反映しなかったため、`CpuQuota=-1`によって無制限へ戻した。`NanoCpus=50000000`の設定値は残るが、実効`cpu.max=max 100000`をコンテナ再起動後にも確認した。CPU設定値の完全一致ではなく、元の無制限動作の復旧である。

通常プロファイルの2件の原因は未確定で、この変更を現象解消済みとは扱わない。次回停止時にはこの4項目とAction・通常ログを照合する。診断を根拠に期限を延長したり、失敗したActionを再送したりしない。監査artifactは`20260914-movement-lease-diagnostics`へ保存した。
