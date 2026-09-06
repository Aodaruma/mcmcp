# Issue #4: renderer欠測中のpreflight回復

対象Issue: [#4](https://github.com/Aodaruma/mcmcp/issues/4)。実装開始時のbaseは `f9f4d32`、最終baseは `38ad85d`。作業branchは `fix/issue-4-fog-recovery`。

## 確認した経路

`ClientFogDistanceSignals` は現在のlevel・camera・entity tickに一致したsampleだけを返す。これは維持する。一方、captureで表面を実再観測しても、その内部recordは保存されない。次のcommit/dispatchでrenderがなくなると、従来は元の古い配送recordに戻り、対象が同じでもrevision barrierにより `known_surface_changed` へ進んでいた。

既存 `LunaSurfacePreflightRegressionTest` はこの組合せのcharacterizationであり、過去のLuna実行で同じ原因が発生したことや低FPS実機での改善を証明しない。

## 今回の実装範囲

- 最初に評価されるprimitiveが `approach_known_surface` / `inspect_known_container` / `take_known_container_stack` / `store_known_container_stack` の場合、対象の配送済みfaceと元の60秒期限をcaptureで固定する。未配送ページは含めない。
- capture自体の欠測から待機可能。commitは同じInbox commandを次pre-tickへ延期し、元のsafety epoch・world generation・HTTP deadline・キャンセル判定を保持する。同じdrainで再実行しない。
- receipt配送ACK後のdispatchにも同じfenceを適用する。session・control epoch/capabilities・pose・local safety・policy・reconciliation・routeと対象証拠を再検証する。待機中には操作しない。
- Actionの起動/JITだけでは足りないため、複数tickの照準後、実際の初回 `useItemOn` の直前にもgateを置く。元lease・現在fog/LOS・revision・操作権限・camera/multiplayer policy・local safetyを検査する。portの位置/視点所有権・stationary・reach・exact state/crosshairの検証も維持する。欠測では `AIMING_INITIAL` に留まり、open token・prediction・openCount・normal useへ進まない。
- 最初の欠測から実行開始までの経過時間とtickを元のAction予算へ一度だけ算入する。commit成功で時計をresetしない。実行開始後のJIT待機は通常のAction時間/tick予算内で行う。
- 復帰した対象は元lease内の面へ現在のfog/LOSで必ず実rayを引く。失敗時に旧recordを残さない。元の公開frame、配送時刻、recordのtick/revisionは書き換えない。
- 同じposition/blockを使う後続nodeの未dispatch JITにも元leaseを適用する。後から配送した別faceや再配送によってこのleaseを拡大・延長しない。別target、先頭がwaitなどの場合、他のDSL primitive全般への欠測回復は今回の対象外。
- owned menuの転送・server ACK・UNKNOWN effectの処理には変更を加えない。未確認のclick、attack、useを再送する回復ではない。
- 初回openを送った後の `OPENING_INITIAL`、slot ACK待機、`AIMING_READBACK`、cleanupでは新しい初回open gateを呼ばない。送信済みの処理を欠測待機へ戻さず、従来の期限内で確認と解放を進める。

## 固定診断

| reason | 意味 |
| --- | --- |
| `renderer_evidence_missing` | 現tickのrenderer sampleがなく、期限内で待機中 |
| `renderer_evidence_timeout` | renderer待機が元のHTTP締切または総Action時間/tick予算に到達 |
| `delivery_expired` | captureした元配送leaseが失効。再配送しても元のActionは延長しない |
| `target_not_delivered` | 有効な配送認可がない、またはsession/storeから失われた |
| `surface_reobservation_mismatch` | fresh sampleで再観測したが、対象一致・fog・LOS等の証拠が得られない |

実不一致と期限切れでは欠測待機をしない。任意入力・item名・座標をreasonへ反射しない。capture以前にstoreからpurge済みの期限切れ記録は、保持していないため `target_not_delivered` と区別できない。HTTP応答が既にタイムアウトして届かなかった場合、呼出側には輸送層のtimeoutが見える場合がある。

## 検証と残る確認

`SurfacePreflightRecoveryTest` は本番Inbox・配送ACK/lease・回復gate・通常observerのray seamを接続し、capture/commit/dispatch各段の欠測→復帰と1回だけのdispatchを検証する。changed block、遮蔽、実fog 1ブロックは拒否する。公開frame/旧recordの不変と元budgetの継続も確認する。

追加試験は再配送後の元lease失効、未配送face/session切替の非認可、Inboxの1drain1試行・deadline・stop epoch・キャンセル・queue外実行中のstop、Action待機tickの一度だけの計上を確認する。`McmcpRuntimeSurfaceRecoveryContractTest` はこれらのgateが本番capture/commit/dispatch/JIT経路から呼ばれ、安全再検証の順序を保つことを検査する。これはMinecraft実行の代替ではない。

`MinecraftPhaseFiveInventoryPortTest` は初回open前の欠測→復帰/期限切れと、送信後・readback・cleanupの全stageで追加callbackが呼ばれないことを対照にする。本番の `dispatchExpectedOpen` でgateがopen token/prediction/useItemOnより前に接続され、元のhard deadlineを維持していることも検査する。

Java 25で `gradlew.bat test harnessTest adminBridgeTest verifyHarnessIsolation build` が成功した。unit 1,218件、harness 13件、admin bridge 21件が失敗・errorとも0。`git diff --check` も成功。既存の非推奨API警告は残る。

低FPSの実機試験・GUI操作・Minecraft再起動は行っていない。`release:verification-needed` を維持し、実機PASS前に公開tagを付けない。

## 実機受入用の有界な要約

PR #14 のmerge後、base `681bc52` に追従して計測を追加した。既存の5 Tool・DSL・catalog/schemaは変更しない。`agent_get_action` の既存 `trace` に、欠測が発生したActionだけ次の固定eventを最大1件合成する。

```json
{"event":"RENDERER_RECOVERY","detail":"missing=capture,initial_open;revalidated=capture,initial_open"}
```

`missing` と `revalidated` は、それぞれ固定の `capture,commit,dispatch,jit,initial_open` からなる順序付き集合で、空集合は `none` とする。`missing` はその段階で現tickのrenderer欠測を少なくとも一度検出した履歴、`revalidated` はその欠測後に同じ段階の現在証拠と安全検査をすべて通過した履歴である。単にfog sampleが戻っただけでは `revalidated` に追加しない。座標・対象名・任意文字列は記録しない。

**どちらもAction内の累積値であり、現在のREADY状態ではない。** 同じ段階で欠測→再検証→再欠測となっても `revalidated` は残る。要約だけでは現在の可視性、操作送信、server ACK、container解放、Action成功を証明できない。特に `initial_open` は初回open前の再検証通過を示すだけで、その後の `useItemOn` の実行や成功を記録しない。要約の `tick` もsnapshot時点のAction進捗であり、各欠測・復帰の発生時刻ではない。

capture/commitの欠測履歴は同じ回復状態に保持し、Action予約後に引き継ぐ。予約前に失敗してAction IDが発行されない場合は従来の固定診断だけになる。対象外または無欠測のActionには要約を追加せず、`none/none` も出さない。計測のない旧JARでは、要約がないことから欠測の有無を判定できない。

各pollでは最新の要約1件を既存256件のtrace枠内へ合成し、tickごとのeventは蓄積しない。通常traceが満杯でも要約は残る。保持は既存Action storeの最新Actionと直前terminal Actionまでで、さらに次へ進むと古い結果は失効する。必要なterminal結果は次の試行前にartifactへ保存する。

### 受入手順と判定の限界

1. `MCMCP-Validation` で対象SHA/JAR hash・同一baselineをT0前に確認する。通常FPSと、T0前に低いFPS上限（例10）を設定した条件を分け、同じ公開MCPの観測→単独inspectを1〜数回の有限試行で比較する。
2. T0以後はterminalまでGUI・fixture・operator操作を差し込まず、公開Tool応答を保存する。terminalの `trace`、`state`、`progress.interactions`、完全な `container_results`、評価leaseと入力解放の既存記録を採取する。
3. `RENDERER_RECOVERY` の `missing` と `revalidated` に同じ段階が含まれ、Actionが `succeeded`、inspectの確認済み `container_results` がcleanup後に公開された場合、その段階の欠測後に完全再検証を通って処理を完了した肯定証拠とする。既存 `progress.interactions` は操作実行の補助証拠であり、初回open専用の送信回数ではない。
4. 成功しても要約がない場合は「欠測経路は未確認」と記録する。低FPSだけでは欠測の発生を保証できず、この方法だけで全5段階を実機で通したとは言わない。

遮蔽や実fogの短距離制約・配送期限切れ・stopで新規操作へ進まない対照は下記の構成試験で確認する。T0前に設定した遮蔽の通常拒否は実機でも確認できるが、「欠測待機中に遮蔽が変わった」ことの証明にはならない。待機中の期限到達・Escを確実に発生させる専用runner/fixture割込は今回追加せず、実機未確認として残す。原状復旧はterminal記録後に行う。

### 計測の回帰検証

`SurfacePreflightRecoveryTest` はcapture/commit/dispatchの各missing/revalidated組合せ、遮蔽・実fog・対象変更時に再検証を記録しないこと、別段階による誤記録とfog復帰だけの誤記録を防ぐこと、同段階の再欠測で履歴が累積することを確認する。`AgentActionStoreTest` は300回更新してもtrace1件・全体256件以内、immutable snapshot・terminal保持と失効、未知bit拒否、effects/interactions/ticksが増えないことを確認する。本番配線の構成試験は各段階の安全検査後にだけ再検証を記録し、初回open前gateが操作送信を記録しないことを確認する。

Java 25の `gradlew.bat test harnessTest adminBridgeTest verifyHarnessIsolation build` で unit 1,222件、harness 13件、admin bridge 21件が失敗・errorとも0。元のTTL・HTTP締切・総予算、fog認可、revision、ACK、cleanup、再送禁止は変更していない。実機受入は未実施で、Issue #4 と `release:verification-needed` は維持する。
