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

実機確認時は対象SHA、session/control状態、各段のtick、current fog有無、元lease期限と固定reason、dispatch回数を秘密を除いて記録する。欠測→復帰の成功だけでなく、待機中の遮蔽・対象変更・期限到達・Escで新規操作が発生しないことも対照とする。
