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

## このPCでの初回受入と着地点の再選択

隔離profileのコピーと新しい平坦worldで、製品5b005979版とPR #92の検証harnessを使った。`recovery_elytra_edge`は未合格。275ticks、128.045blocksでrecovery_budget_exhaustedとなり、HP20だが飛行中だった。終端後に固定fixtureを再準備して安全床へ戻した。開始時に選んだ上段床y=121を、プレイヤーがy=120.92へ落ちた後も保持していた。新コースは下降着地用なので上方目標を認可せず、旧fallbackだけで滑空した。

広い乾いた床の`recovery_elytra`はHP20、y=101に接地し100ticks安定、距離37.215blocks、204ticksで救助を完了した。元の入力Actionの終端preempted_for_recoveryは、入力を再実行しない現在の契約どおり。広い床の成功は狭岸の合格へ代用しない。

この結果から、救助用の下降着地では現在位置以上の高さの目標を失効させ、新規候補からも除外する。通常の非救助飛行は変更しない。床の支持・視認条件を緩めず、同じ候補選択と元予算を使う。上段床・同じ高さ・下側岸・欠測の高さ境界回帰を追加した。

再修正のfocused24件と製品jar生成は成功。候補SHA-256は`5240b704b55b50906de35c1b31a816b828f7c356114e73fd2b4486cf02c4c653`。初回の失敗を成功へ読み替えず、この候補の実機を別に確認する。
