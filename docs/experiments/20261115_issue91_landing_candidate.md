# Issue #91: 狭い岸への有限着地コース候補（2026-11-15）

## 原因と変更

既存の飛行plannerは単一のyaw/pitchを短い期間だけ予測し、確認できた将来tick数を強く優先していた。狭い岸へ下がる候補は床・流水へ近づくため短くなり、引き起こしを続けて岸の高さを通過した。R25～R28の不合格記録と区別して、新しい候補を作成した。

`ElytraLandingCourse` は、水平・浅い引き起こし・水平・最終減速の有限コースを、目標への相対方位と少数の区間候補から選ぶ。実験座標・特定の岸・固定の成功時刻は製品へ埋め込まない。まず純粋なVanilla飛行予測で成立しない候補を除き、残った候補の全通過域、乾いた支持、接地後の停止を確認する。

- 即時、全体遅延、段階だけ遅延、初期操舵だけ遅延の4経路が必要。途中で残コースが失効した場合も、残予算と現在の観測から区間時刻を有限に調整して再計画する。
- 着地予測は最大128ticks。接地後停止に32ticksを予約し、元Actionの時間・距離・camera予算へ収める。予測期間を延長してもActionの実行予算は増やさない。
- 各tickで新しい`FlightObservation`を使う。既存の96block等の観測設定、最大2,048cell、loaded・視線・液体・危険物・entityの条件は維持する。水面の高さ判定を緩める変更や、R27/R28の取り下げ済み試案は含めない。
- 床直前の身体余裕が床へ届いた部分は、同じ乾いた支持と停止証拠を必須にして着地として扱う。水や未知のセルをこの例外へ含めない。
- 接地後は通常摩擦0.6の支持だけで、3tickの無減速余裕と通常摩擦の停止を検証する。滑る床・未知の摩擦は新コースでは認可せず、既存の有限飛行処理へ戻す。
- 計画後も操作権限のcallbackを再確認してからcamera・item入力へ進む。新コースは花火を使わない。

公開Tool、引数schema、元の取消・Esc・OFF・world境界、入力解放の経路は変更していない。

## 初回候補のソース試験（レビュー前）

R28の保存済み診断から、固定fixtureの実際の流水5層を`src/test/resources/flight/recovery-bank.json`へ保存した。水源の直方体だけを使う近似ではない。

`ElytraLandingCourseTest` は、実測の位置・速度から毎tick新しい可視判定と有限cacheでコースを再証明・再計画し、乾いた岸への到達と停止を確認する。加えて、遅延経路のはみ出し、通過域の欠測、停止支持の欠測、元の時間・距離・camera予算不足を拒否する。これはオフラインの回帰試験であり、Minecraft実機合格とは数えない。

- 飛行focused 16件成功。
- 全Java 1,943件（通常1,901・harness14・admin28）成功、failure/error/skip 0。
- `test harnessTest adminBridgeTest verifyHarnessIsolation build` 成功。
- 全`tools/check-source.ps1`成功。transport23件、設計図変換6件、worker JSON6件、施工checkpoint・capability・v2 smoke mockを含む。

## 候補と残る確認

baseは`a44089b81198151fe82dd37669ad06d7287b920c`。レビュー前の初回候補製品JARのSHA-256は `B9AAF7A6DF01D0C8D35AC72FA6188287927E6FAF6372DD2D51C18E72947DEE9A`。source overlayと試験用harnessをローカルartifactへ凍結した。以下のレビュー修正後sourceは、このJARの検証へ流用しない。

実機確認は親担当がこのPCの隔離profileで行う。まず`recovery_elytra_edge`・pool、既存の広い床への救助、取消・OFF・予算停止、復旧を確認する。変更後JARの実機確認は本記録時点で未実施のため、installable=falseと`release:verification-needed`を維持する。通常profileや元worldは本対応で操作していない。

## 独立レビュー後の補強

停止域の上方airが見えるだけでは支持床の可視性を証明できず、旧landingの高さ許容幅を新コースへ使うと予測接地面と実groundがずれる、という2点を補強した。

- 新コースは期待goal高さと実groundを1e-6以内で一致させ、同じ高さでpadding切取りと停止を検証する。旧landing全体の契約は変更しない。
- 各停止支持の上面へ現在eyeからVISUAL/COLLIDER両方の実rayを要求し、UP・対象位置・hit高さを照合する。current renderer fogとrange、ray上の全cellのloaded/world borderも必須。未観測の支持state・fluid・frictionを停止証拠へ使わない。
- 停止支持のproof cacheも既存の2,048cell上限へ算入する。未知・液体・危険・通常摩擦以外の拒否と全停止域のstanding clearanceを維持する。
- 既存コンパイル済み初回候補を独立Java processで少数計測した。全支持が拒否される探索のwarm値はselect約8～27ms、replan約47～77msだった。cold select約2,706msはclass初期化/JITも含み、steady-state性能とは分ける。
- この結果を受け、新コース検証・再計画・選択は1tickで同じ20msの検索期限を共有し、物理予測・証拠確認中にも元Actionの権限callbackを検査する。未完のコースを認可せず、既存の有限fallbackへ戻る。clamp後に同じになる再計画候補も重複検査しない。

床面だけ隠れる低い壁、VISUAL/COLLIDERの不一致、途中の未ロード、霧欠測・短い霧、range外、高さ違い、取消、検索期限の回帰を追加した。Minecraft終了確認後の新source検証は次のとおり。

- focused 23/23成功。全Java 1,950件（通常1,908・harness14・admin28）、failure/error/skip 0。
- `test harnessTest adminBridgeTest verifyHarnessIsolation build`成功。追加`tools/check-source.ps1 -SkipJava`も全成功。transport23件・設計図変換6件・worker JSON6件、施工checkpoint・capability・v2 smoke mockを含む。実機合格は未確認。
- 新製品JAR SHA-256: `5b00597965eaca47f0abaa99cb33e457bc6f7c69362fc3929dae1ec95767e56f`。harness SHA-256: `d6e21630c55bdc1b6b090e3bb56b47366165ec0c1ec9f4a622dd8e2e9133407c`。
- 新sourceの独立Java 5試行では、支持を全拒否したwarm selectは12.0～20.1ms、replanは20.0msで全結果nullをassertした。cold初回はselect24.6ms・replan823.5msで、JVMのclass初期化・JITを含む。20ms期限は協調的検査であり、JVM初期化や単一callbackを中断する時間保証ではない。
- source overlay・製品・harness・検証ログを`artifacts/v2-issue91-landing-20261115-reviewed1`へ凍結した。旧B9AAF候補とは区別し、この新JARで実機受入を行う。

新候補の凍結後、Gradle daemonを停止した。製品・harness・source checksのログを同じartifactへ保存し、実機起動中の追加ローカルGradleは行わない。

## 下降目標の再選択後に残った実機不合格

上方に残ったplatformを選び直す`72abbda`（製品SHA-256 `5240b704b55b50906de35c1b31a816b828f7c356114e73fd2b4486cf02c4c653`）でも、2026-11-15の`recovery_elytra_edge-092003`は距離予算で停止した。lower bankは正しく選ばれたが、支持上面が未確認のコースは拒否され、従来のfallbackが急な降下を選んだ。広い床への救助成功とは区別する。

旧オフラインWitnessは乾いた支持IDと上方airだけを確認し、現在eyeから支持上面への実rayをモデル化していなかった。保存済み流水5層で、上方airが見えても乾いた支持の上面だけが手前の流水に隠れるケースを再現した。停止域の全支持へ製品と同じ`visibleLandingSupport`を使い、air/supportの合計cacheも2,048に制限したところ、旧固定コースの正当化とnativeの初期コース選択の2件が失敗した（focused26件中2件）。R28の開始stateからは、別の完全証明コースが成立した。

nativeの1326tickでは現在のbody+0.2余裕が頭上platformへ届くため、全コースの初期clearanceを証明できない。1329tickにはその下を抜けるが、最初の検索から8tick待つ間にfallbackがpitch40へ曲げ、1334tickには有効なコースがなくなっていた。また、元の候補には必要な減速区間の中間値がなかった。

独立Java（最大256MiB）で現在の完全証明関数と厳密Witnessを使い、1329tickの実stateからapproach0/flare32/coast24/final pitch-50の111tickコースを確認した。各tickで支持LOS・4遅延・全停止域を再証明し、必要時に有限再計画する追試では、121tickで乾いた岸の停止域へ到達した。移動43.51block、camera85.08度で元の予算内だった。これはオフライン証拠であり実機合格ではない。

補強候補は、必須の現在body+0.2 clearanceを検索前に検査し、coast24/final pitch-50を汎用候補へ追加する。8tickの検索backoffは削除し、共有20ms期限や未成立の証明は次tickの新しい証拠で再試行する。未証明の支持やprefixを着地認可へ使わず、元の可視性・予算・取消条件を維持する。修正後のstrict focused26件と全Java1,953件（通常1,911・harness14・admin28）、`verifyHarnessIsolation build`、`tools/check-source.ps1 -SkipJava`はすべて成功した。実機確認は未実施。新製品SHA-256は`722de853464906080a6567e3dd5d5294dcd8ad0af27ff3cb755654b5d100173f`、読取診断harnessは`06e0293ec654168eeb1d5f1a951385d07c10e506a05071d56bd41a234314ea90`。候補を`artifacts/issue91-safe-landing-20261115-r4`へ凍結し、Gradle daemon停止後にnative edge/poolの実機確認へ渡す。

読取専用のharness診断は`artifacts/issue91-support-sight-diagnostic-20261115-r3`へ別凍結した。これは上記5240製品との組合せに限る。`flight_profile.samples[].landing_visibility`へ現在のeye/range/fogと既に計算された支持ray結果を記録し、新しいworld読取や操作は行わない。1tick最大16試行・最初の失敗だけを採取し、打切りも明示する。

## このPCでの初回受入と着地点の再選択

隔離profileのコピーと新しい平坦worldで、製品5b005979版とPR #92の検証harnessを使った。`recovery_elytra_edge`は未合格。275ticks、128.045blocksでrecovery_budget_exhaustedとなり、HP20だが飛行中だった。終端後に固定fixtureを再準備して安全床へ戻した。開始時に選んだ上段床y=121を、プレイヤーがy=120.92へ落ちた後も保持していた。新コースは下降着地用なので上方目標を認可せず、旧fallbackだけで滑空した。

広い乾いた床の`recovery_elytra`はHP20、y=101に接地し100ticks安定、距離37.215blocks、204ticksで救助を完了した。元の入力Actionの終端preempted_for_recoveryは、入力を再実行しない現在の契約どおり。広い床の成功は狭岸の合格へ代用しない。

この結果から、救助用の下降着地では現在位置以上の高さの目標を失効させ、新規候補からも除外する。通常の非救助飛行は変更しない。床の支持・視認条件を緩めず、同じ候補選択と元予算を使う。上段床・同じ高さ・下側岸・欠測の高さ境界回帰を追加した。

再修正のfocused24件と製品jar生成は成功。候補SHA-256は`5240b704b55b50906de35c1b31a816b828f7c356114e73fd2b4486cf02c4c653`。初回の失敗を成功へ読み替えず、この候補の実機を別に確認する。

## r4 nativeの実測とpool残件（2026-11-15）

r4のsourceは`1ccd1c03d36b76e453b1c8ad6534d5c925c6185a`、製品SHA-256は`722de853464906080a6567e3dd5d5294dcd8ad0af27ff3cb755654b5d100173f`、harnessは`06e0293ec654168eeb1d5f1a951385d07c10e506a05071d56bd41a234314ea90`。

| native case | artifact | 判定 |
| --- | --- | --- |
| cold edge | `20261115-local-acceptance/recovery_elytra_edge-101947` | 単独PASS。救助stable100tick、HP20、乾いた床y101、READY。 |
| pool | `20261115-local-acceptance/recovery_elytra_pool-102033` | FAIL。水へ入り、床外へ落下。terminal後の落下死を親担当が確認。配布blocker。 |
| r5 edge/pool・wide・取消 | 新候補で再確認 | PENDING。r4 edge成功だけでPR全体を受け入れない。 |

cold edgeの元Action`5d97f510-6a83-4b86-87bf-ee7d1f5b0c94`は救助への移譲で`failed/preempted_for_recovery`となり、救助自体は`status=stable`・`stable_ticks=100`で完了した。終点は(348.716,101,291.906)、体力/minimum health20、water_ticks0、cursor empty。最後の105server samplesは同一位置で乾いた接地を保った。移動46.38block、camera133.09度、救助238tickで元の予算内。

完全な`plan.landing=true`はplayer tick513でhorizon110として成立し、120profile samplesで更新された。fog256で、視認できない支持は実際に手前の流水へrayが当たって拒否された。全138profile samplesのflight処理は中央値4.40ms・p95 11.04ms、cold初回38.95ms。tick512は26.49msでfallback、次の513は10.91msで完全courseに移った。tick579は完全courseの再証明を含め28.21msだった。20msは検索の協調期限であり、flight処理全体の上限とは区別する。

poolの元Action`819ef850-c9ac-4037-841d-ced61e6a96d7`では完全landing planが0件。開始から79tickで水へ入り、171tickで床外へ出た。救助は`exhausted/recovery_budget_exhausted`、stable0、移動130.54blockで終了し、oracle時点でy6.209・非接地、続いて落下死した。最終flightの`flight_deploy_camera_budget`はその後段の失敗で、最初の乾いたcourseを選べなかった問題と分ける。

## r5 pool向けの最小補強

poolの保存済み開始・終了の流水5層は既存回帰resourceと全cell一致した。native1420の実stateで、bearing-5度のcourse（approach0/flare48/coast24/final pitch-70）が全4遅延・全停止域の現在視認を証明する。一方、course中のtick7～23はraw intent点周辺のairが隠れ、既存の先行goal検査が有効なcourseを捨てていた。独立モデルでは全証明を更新して115tick・移動42.97block・camera52.14度で乾いた停止域へ到達した。

yaw候補へ±5度を補い、既存courseの完全証明をraw intent点の再検査より先に行う。証明成立時だけその結果を同tick/pose/cacheで再利用し、proof失効・上方goal・近いthreatでは特例保持しない。`Selection(course,verifiedTicks)`でselect/replanの証明結果も返し、callerでの二重verifyを除く。同じ20ms期限をpreverify・replan・selectへ通し、予算・期待ground・全支持LOSは変更しない。waterescape処理への変更はない。

strict focused28件・全Java1,955件（通常1,913・harness14・admin28）、`verifyHarnessIsolation build`は成功。r5製品SHA-256は`8f0acfce83f5d0cb7209fa8e27d964f9f90dac355366824cde33832ba737f8b6`、harnessは`06e0293ec654168eeb1d5f1a951385d07c10e506a05071d56bd41a234314ea90`。source/JAR/XML/logを`artifacts/issue91-safe-landing-20261115-r5`へ凍結する。実機はPENDING、installable=falseを維持する。

## r5 nativeの結果とr6の補強

r5 sourceは`73886c16ad2f7d07aceab6b5239964af2b9c6b6d`。上記r5製品・harnessの組合せをこのPCで確認した。

| native case | artifact | 判定 |
| --- | --- | --- |
| cold pool | `20261115-local-acceptance/recovery_elytra_pool-104700` | FAIL。完全course成立後に失効し、花火1発を使って距離予算で停止。HP20・water0だが飛行中、stable0。終端後に生存状態で安全床へ復旧。 |
| warm edge | `20261115-local-acceptance/recovery_elytra_edge-104804` | FAIL。完全courseなし、water1、距離予算で飛行中停止。終端後に生存状態で復旧。 |
| wide | `20261115-local-acceptance/recovery_elytra-104935` | PASS。乾いた床y101で100tick安定、HP/minimum health20、READY。 |
| cancel | `20261115-local-acceptance/recovery_elytra_pool-105155` | PASS。client_requestでActionと救助を取消、READY・HP20・water0。入力idle確認後の固定fixture復旧も成功。 |

cold poolではplayer tick1447から完全courseが成立したが、1460で現在courseのproofが失効した。raw intent点周辺が隠れていたため、同じgoalへの完全再計画より先に目標を捨てていた。厳密モデルでは、この時点でも全4遅延・全停止支持LOSを満たす85tickの再計画が成立する。また、完全courseの採用がlanding予約へ反映されず、失効後の上方safeAnchorに向けた不要な花火を許していた。warm edgeの初期状態は非飛行・水平速度0で、真上のsafeAnchorへの水平零距離bearingを0度として元のyaw-90度から回していた。

r6は既存courseの完全再証明と必要時の同goal再計画をraw intent検査より先に行い、同tickの証明結果を再利用する。新courseだけ開始tickを合わせ、失敗時の二重再計画や共有20ms期限の再設定は行わない。完全course採用後はlanding予約を維持して再加速を防ぎ、水平距離がほぼ0の展開時だけ現在yawを保つ。期待ground高さ・全停止支持LOS・4遅延・2,048cell cache・元予算・取消条件とwaterescape処理は変更していない。

strict focused31件が成功した。nativeのlate pool実stateから、raw intent非可視中の完全再計画と乾いた停止域への到達を確認し、既存1420回帰の非可視10tick以上の条件も維持した。landing予約の単調性、水平零距離と通常bearing、権限喪失の拒否を回帰で確認した。r6の全体ビルド・版付きJAR・native edge/pool/wide/cancelは確認待ち。r4/r5の失敗を合格へ読み替えず、PRはDraft・installable=falseを維持する。
