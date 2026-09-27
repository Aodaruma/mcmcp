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

失効理由と診断は解放処理に入る前に固定し、入力leaseのreleaseまたは施工cleanupが例外を投げても一般的な安全失敗へ置き換えない。同じattemptとleaseをruntimeのcleanupから到達可能なまま保持し、入力解放確認後に最初の失敗を公開する。

## 検証と再開

製品ソースは `3abd84dc285eacb95152303b83d85b2cc53280f2`、製品JARのSHA-256は `29731db675414fa356576e81de2cd1e85f1e09fa5af5db0bd1e02b4432d23681`。以後の実験記録追記では製品を再生成していない。

最終ソースでJava 1,304件、harness 13件、admin bridge 25件とisolation/buildが成功した。未変更のPython 14件と外部runner/capability mockは初回の全source checksで成功し、Javaだけのレビュー修正後には再実行していない。単体試験は非更新検査、期限ちょうど、pauseを除く時計、nanoTime wrap、owner検査、解放失敗後のcleanupを確認した。床延長の入口順序・末尾heartbeat、失敗前のeffect回収・診断配送、cleanup例外時の最初の失敗保持はruntime契約試験で検査した。

独立したCodex CLIレビューは初回 `ecb7881a2bad44a5f88188b67d6db5d60e7b97d3` でcleanup例外による失効診断消失を指摘した。修正後の上記製品ソースに対する差分と直接cleanup呼出元の再レビューでは、指摘解消・新規指摘なし。これはGitHubのapprovalや実機検証の代替ではない。

Minecraft 26.2 / NeoForge 26.2.0.59、maxFps 10の隔離Dockerで、固定 `construction-edge` fixtureを使った。通常回帰試験は公開MCPを呼ぶ決定的なcapability gateであり、fresh評価モデルによるMCP-only自律完遂評価とは区別する。

| run | 結果と分類 |
| --- | --- |
| 01 | 初回ソースの端設置4回・取消し成功。最終版の合格数へ含めない |
| 02 / 10 | 最終製品で各4回の端設置と早期取消しが成功。10は故障注入後の再起動を経た再確認。材料収支・支持された終点・全Action terminal・READY復帰を確認 |
| 03 / 06 / 08 | 材料準備または公開MCPからの要求作成のみ。施工試験へ算入しない |
| 04 / 05 / 07 | CPU制限0.05または0.1でstate/observation/preflightがタイムアウト。Action未開始のため失効検証へ算入しない。各制限は復旧済み |
| 09 | Action開始後に検証コンテナを外部から3秒pause/unpauseする、制御された故障注入。入口での失効停止と診断を確認 |

run09の停止は `floor_extension_input_lease_expired`、`orient / tick_entry / overdue=1090ms / tick_gap=1 / executor=0ms`。8tickで失敗し、移動・設置・effectは0、材料は64→64、入力cleanup後にREADYへ戻った。cameraの約56.77度は停止前の累積値である。既存分類 `SERVER_DENIED_OR_DESYNC / recoverable=true` と再観測要求を維持した。このrunは外部停止を含む故障試験であり、通常のMCP-only完遂評価へ算入しない。設置段階、設置確認後、heartbeat末尾における実機失効は未検証であり、元現場の遅延原因も確定していない。

全Action terminalとREADYを確認してからMinecraftを正常終了し、検証コンテナを停止した。開始前のワールド61ファイルを全件hash照合し、MOD・instance設定・optionsも復元した。CPU設定は開始前のmetadataへ完全復元し、実効値 `cpu.max=max 100000` がコンテナ再起動後も維持されることを確認した。元コンテナは停止状態を維持した。fixture-adminは検証専用であり、通常プロファイルの導入対象は上記製品JARだけである。

元現場ではconfirmedの新床と安全な支持・経路、同session/READY/健康状態、台帳のconfirmed/next/pendingを再観測して新規計画する。位置合わせで別途発生した`unverified_actual_movement`→`replanned_route_remaining_occurrence`は、再計画した移動を当初のoccurrence残枠へ収められないための停止である。30秒/600tickを全て消費したことや入力lease失効を意味しない。今回の変更でnavigation予算や移動検証を緩和しない。
