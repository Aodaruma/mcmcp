# Issue #91 r8: このPCでの飛行救助受入（2026-11-15）

実AABB補強後のr8で、cold pool・edge・wide・取消・warm poolの5ケースを受け入れた。対象sourceは`f21fe71213c645718812c73c720853f6299195a4`、版は`0.2.0-rc.1`。

| artifact | SHA-256 |
| --- | --- |
| product.jar | `93966e9bb33e4ea31675c0f961d48a25600387e11048c592e17143ee4cce96f3` |
| test-harness.jar | `a6ec51bd01bb32ecef55d874424555b933d14454e1310516034f776761828bf1` |
| fixture-admin.jar | `feb649f2557e395b8dea976869df6857ca1d0e7151318cea1bf05ee39f3c9fb2` |

全ケースで実機担当が同じsource/JAR pairと、T0からterminalまでUI介入がないことを確認した。cold poolは今回のゲーム起動後にflightコードを初めて呼び出す試験。各caseのfixtureは開始前に準備し、private oracleは公開Actionのterminal後に限り、取消後の安全床再準備は終端後の復旧として分離した。集計担当は終了済みartifactだけを読み、通常profileや共有worldを集計には使っていない。

focused40件・全Java1,969件（通常1,925・harness16・admin28）・`verifyHarnessIsolation build`と[対象sourceのCI](https://github.com/Aodaruma/mcmcp/actions/runs/43670292514)は成功、failure/error/skipは0。製品JAR内のfixtureクラス不在を確認した。過去候補の不合格は[候補記録](20261115_issue91_landing_candidate.md)に残す。

## 5ケースの結果

| case / Action ID | artifact（`20261115-local-acceptance/`以下） | 救助結果 | tick / 移動block / camera度 |
| --- | --- | --- | --- |
| cold pool / `684a26e7-2cad-4af1-9161-a0c4296d92d8` | `recovery_elytra_pool-121927` | PASS、乾いた床で100tick安定 | 240 / 49.51 / 118.65 |
| edge / `61a75922-872d-4cbe-b1e9-37ef61b17710` | `recovery_elytra_edge-122127` | PASS、乾いた床で100tick安定 | 231 / 46.35 / 85.70 |
| wide / `aa1295a9-89b2-488c-96da-dced7405b21f` | `recovery_elytra-122226` | PASS、乾いた床で100tick安定 | 204 / 38.06 / 95.00 |
| cancel edge / `29cf6af2-1903-4f0b-8654-6ce1de74643f` | `recovery_elytra_edge-122348` | PASS、Action・救助ともclient_requestで取消 | 24 / 4.00 / 55.06 |
| warm pool / `ab6d037a-5bbd-4c90-9d5a-90a1870e2b4a` | `recovery_elytra_pool-122355` | PASS、乾いた床で100tick安定 | 242 / 49.68 / 100.60 |

救助を完了した4ケースの元入力Actionは`failed/preempted_for_recovery`で終わり、救助自身は`status=stable`・`stable_ticks=100`となった。入力Actionの成功とは表現しない。全5ケースの終端はREADY・空cursor・HP20、minimum health20、花火0。移動128block・camera360度・600tickの元救助予算内だった。

狭岸の3ケースと取消は`water_ticks=0`。wideはこのcounterとserver sample配列を収録していないが、全104flight samplesでwater=false、公開結果のstable100とterminal oracleの乾いた接地・飛行falseを確認した。

## 着地・停止と取消の確認

| case | terminal position | 末尾の乾いた接地server samples / 終点と完全同位置のsamples |
| --- | --- | --- |
| cold pool | (348.687683,101,328.514102) | 120 / 109 |
| edge | (348.691239,101,292.157172) | 118 / 107 |
| wide | (341.967373,101,310.5) | 配列なし。公開stable100とterminal oracleで確認 |
| warm pool | (348.998630,101,329.884384) | 117 / 106 |

cold poolではplayer tick3125と3126にcoastを採用した。現在yaw-74.51059・pitch-25を保ち、20tick全prefixのclear証明付きで`landing=false`・`boost=false`だった。before/afterの姿勢も一致する。次の3127に`landing=true`・horizon101の完全courseを17.14msで採用し、3238まで112samples連続で更新した。3238では残り1tickの停止支持まで証明した後、3239に乾いたy101へ接地した。

edgeでも6tickのcoast後に112samplesの完全course、warm poolでも2tickのcoast後に127samplesの完全courseを採用した。wideはcoastなしで96samplesの完全courseを使った。いずれも支持の未確認を着地へ昇格させず、現在の支持LOS・期待ground高さ・4遅延・停止域proofを維持している。profileの`first_support_failure`は探索途中の未採用候補の拒否であり、採用courseの支持が未確認という意味ではない。

cancel edgeでは、飛行courseの途中で同じAction IDを取り消し、Actionと救助がともに`cancelled`、理由`client_request`となった。終端時の飛行継続はVanillaの物理として残る。入力解放は、その後のinput-idle guard付き固定fixture再準備がreceipt `73028954:recovery_elytra_edge`で`prepared`となったことから確認した。このguardはAgent所有のmovement・attack・use・pickとgoal proofがないことを必須にする。安全床への再準備は元oracle保存後の復旧として分けた。

取消の末尾1profile sampleはobservationを持たず、cleanup時のlastPlanだけが残るため、新しい着地証明の採用数へ数えていない。

## 処理時間と観測の範囲

| case | flight profile数 | 中央値ms | p95（higher）ms | 最大ms |
| --- | --- | --- | --- | --- |
| cold pool | 140 | 3.94 | 11.00 | 36.92 |
| edge | 131 | 4.02 | 19.85 | 23.53 |
| wide | 104 | 1.82 | 8.08 | 12.76 |
| cancel edge | 24 | 2.50 | 7.77 | 20.75 |
| warm pool | 142 | 3.28 | 13.26 | 22.02 |

cold poolの最大36.92msは最初の非飛行状態の呼出し（player tick3106）。coast初回は32.06ms、次は23.37msだった。20msは検索の協調期限であり、JIT・観測callback・fallback等を含むflight tick全体の絶対上限ではない。

観測を持つ全profileのfogは256、profile打切りはない。cold poolのprofile air cellsは最大454だった。Profilerの`sampled_cells`はair cacheだけの数で、支持cacheを含む合計とは区別する。合計2,048cell上限は製品sourceと回帰で維持している。

## 記録と残る最終確認

ローカル集計は`artifacts/issue91-safe-landing-20261115-r8-native-acceptance/five-cases-adjudicated-summary.json`。元summaryとfixture reportのSHA-256を含め、対象pair・Action・oracle・予算・coast/完全course・処理時間を固定した。公開文書へ認証情報や通常profileのprivate logは含めていない。

[R6 Docker施工復帰の別記録](https://github.com/Aodaruma/mcmcp/blob/2a3dfb0ec66413b225fab77ab4bb7855b43d669f/docs/experiments/20261115_build_flight_acceptance.md)はsource `0eaba1b`・製品`552e7af4`の証拠であり、PR #100で統合側へ取り込まれた。r8の本記録が受け入れた範囲は上記5飛行ケースで、R6の施工再開をr8の施工再試験として数えない。

Issue #91のr8実機5ケースは合格。通常profile 288ファイルのhash復旧照合と、PR #95の最終統合JAR受入は親担当の最終作業として確認待ちであり、全体Releaseの合格・復旧完了は別に記録する。
