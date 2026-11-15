# v2 梯子下降・長い梯子の隔離検証

2026-10-10、aod-mimoidの専用Dockerで実施。通常「くらふとぶ！」と地図アート現場は操作していない。製品候補 `ladder-r3` のSHA-256は `55f1703ed44cd1cec6fd8b199d2702e5964a3278a26161352d9c4008aaea0a94`。公開15ツール・全input/output schemaは既存catalogと一致し、公開引数は変更していない。

## 再現と修正

東向き梯子を `(305,101..104,305)`、背面壁をx=304へ置く。上階の足元y=104、下階y=101。下降開始 `(305.5814,104,304.7269)` は報告された入口の小数位置を再現する。長いケースは上階y=112へ変更する。終了目標は梯子横の足場 `(306,y,305)`。`clear_path:false`、`auto_replan:false`、`max_distance:24`、`max_ticks:600`で実行した。

- 元の `merchants-r4` は下降開始後12tickで `local_hazard`。梯子へ入った直後に残るVanillaの `onGround` が、自由落下の判定に使われていた。実接触中で安全確認済みの梯子を現在位置の判定へ反映する。
- その修正だけのR1は短い梯子を上下できたが、11ブロック下降では上階を移動し続け600tickで停止した。局所観測は入口から上下4段までで、足場しか経路の終点にできず、次の観測範囲へ進めなかった。
- R3は、目標の高さへ近づき、その先にもう1段の通過可能な梯子が観測されている場合だけ、内部区間の終点にできる。区間終了時に現在のedgeを再確認し、次の観測から続行する。公開の最終目的地は安定した足場のままとし、はしご接触だけでjob成功にはしない。

公開traversabilityは足場のない梯子の内部通過edgeを省くため、公開結果に下降edgeがないことは内部経路の不在を意味しない。

## 最終候補の結果

| ケース | 結果 | ticks | server側の確認 |
| --- | --- | ---: | --- |
| LongDown：y112→101 | succeeded | 106 | y101、接地、体力20 |
| LongUp：y101→112 | succeeded | 132 | y112、接地、体力20 |
| Down：y104→101 | succeeded | 54 | y101、接地、体力20 |
| Up：y101→104 | succeeded | 62 | y104、接地、体力20 |
| Cancel：下降開始後に取消 | cancelled / client_request | 23 | 梯子接触、体力20 |
| TickLimit：上限3tick | failed / tick_limit | 3 | y104、接地、体力20 |
| Gap：y102の段を除去 | failed / tick_limit | 600 | y104に留まり接地、体力20 |

7ケースとも終了時にREADY、実行中jobなし。上下4ケースは足場の中心から水平0.25ブロック以内を確認。取消では入力を解放し、job終了後もVanillaの重力で下降した。欠損した梯子は落下せず上限停止したが、経路なしを即時に判定する改善は含まない。

`Invoke-McmcpV2LadderSmoke.ps1` は既存の公開API smokeを共用し、session・固定試験範囲・開始高さを照合する。fixtureはharness専用の固定5ケースであり、任意の座標やコマンドを受け付けず、製品JARへ含まない。準備の手順は [harness README](../../src/harness/README.md) を参照。

Java 1,693件、harness 14件、admin bridge 28件の計1,735件、`verifyHarnessIsolation`、build、PowerShell/Python source checksが成功。単体試験では、観測済みの次段を必要とする継続、除外セル、逆向き・途中段の最終目標の拒否、既知になった足場への完了を追加確認した。

## 記録・復旧・範囲

ローカル `artifacts/v2-ladder-20261010` にbaseline失敗、R1/R2、最終 `r3-evidence`、製品／harnessのhash、復旧receiptを保存する。認証情報・world・private logはGitへ追加しない。最終製品JARは同ディレクトリの `package-r3/mcmcp-neoforge-26.2-0.1.0-SNAPSHOT.jar`。通常導入には製品JARだけを渡し、harnessを渡さない。

05:18 JSTに元world 61・設定3・JAR2、計66ファイルのハッシュ一致とfixture退避を確認し、検証containerを停止した。05:19 JSTにはDocker Desktopとbackendの終了も確認し、起動前の停止状態へ戻した。

通常profileへの交換・Prism GUI起動・サーバー再接続・MCP ONは利用者指定の差し替え担当が行い、05:27 JSTまでに完了報告と `artifacts/v2-ladder-install-20261010/normal-verification.json` を確認した。旧版バックアップ・導入先hash・交換後起動のJVM・新session・READY/jobなし・15ツール全schema一致を記録し、チャット表示状態で入力を終了した。開発担当は通常ゲームを並行操作していない。実装commit `a86ae3c` の[CI build](https://github.com/Aodaruma/mcmcp/actions/runs/37986473252)も成功している。

元の地図アート現場そのもの、MOD製の梯子、梯子途中の動的破壊、マルチプレイの通信遅延、raw入力によるポータル・縁の通過は実機未検証。足場のない梯子途中を最終目的地にする用途は対象外。先行する取引・生産・飛行の全ケースを今回のJARで再実行したものではない。

## 囲まれた入口の追加修正 `ladder-field-r7`

R3導入後の地図アート担当の現場試験では、入口から1歩で `movement_stalled` となった。新しい固定fixture `ladder_enclosed` は、東向き梯子の上段に東・南の壁と上のwall torchを追加し、開始位置を `(305.456794159,112,304.508786297)` とする。公開目標は実在する下階の足場 `(306,101,305)`。現場の下階全体を複製したものではなく、上段での進入停止を切り分ける再現である。

R3で33tickの `movement_stalled` と終了位置z=304.606786を再現した。梯子はセル幅の3/16を占め、プレイヤーの身体の端が梯子の薄い側面に重なっていた。経路はセル中央の通路を検証していたが、実際の入力は横位置のずれを許容して前進し、各tickの安全判定に止められていた。

修正版は最新revisionで安全確認したVanilla梯子の空き幅の中央を狙い、同じ高さの入口では、今の横位置で進入すると当たる場合に先に横へ寄せる。executorは引き続き局所安全層の検証結果を使う。最終の衝突・落下判定、公開の到着条件、経路・時間・距離の予算は緩めない。診断用ログは製品候補から除いた。

| ケース | 結果 | ticks | server側の確認 |
| --- | --- | ---: | --- |
| Enclosed：囲まれた入口からy112→101 | succeeded | 119 | y101、接地、体力20 |
| LongDown：y112→101 | succeeded | 105 | y101、接地、体力20 |
| LongUp：y101→112 | succeeded | 125 | y112、接地、体力20 |
| Down：y104→101 | succeeded | 54 | y101、接地、体力20 |
| Up：y101→104 | succeeded | 62 | y104、接地、体力20 |
| Cancel | cancelled / client_request | 22 | 梯子接触、体力20 |
| TickLimit | failed / tick_limit | 3 | y104、接地、体力20 |
| Gap | failed / tick_limit | 600 | y104、接地、体力20 |

8ケースともREADY・jobなし。成功5ケースは足場中心の水平0.25ブロック以内。Java 1,693＋harness 14＋admin bridge 28＝1,735件、build・harness隔離・PowerShell/Python source checksも成功した。公開15ツールの全schemaは既存catalogと一致する。

製品JAR SHA-256：`ee1a94e94cecdf3962b1cdc05fef0433b2836c8a2ae124399a6ce0d626aaa57c`。ローカル記録は `artifacts/v2-ladder-field-20261010/r7-evidence`、製品は同ディレクトリの `package-r7/mcmcp-neoforge-26.2-0.1.0-SNAPSHOT.jar`。05:57 JSTに元world・設定・JARの66ファイルを全hash一致で復元し、fixtureを退避、containerを停止した。05:59 JSTにはDocker Desktop/backendの終了も確認した。通常ゲームは地図アート担当が操作を保留し、差し替え担当へ引き継ぐ。通常サーバーでの修正版の動作確認は別途必要。

実装commit `91f490b` の[CI build](https://github.com/Aodaruma/mcmcp/actions/runs/37990744113)は成功。06:03 JST、差し替え担当の `artifacts/v2-ladder-field-install-20261010/normal-verification.json` で通常profileへの導入を確認した。製品hash、旧版backup、交換後のPrism GUI起動・サーバー再接続、新session、READY/jobなし、全15ツールschema一致を記録し、操作権を地図アート担当へ返した。

地図アート担当の通常サーバーでの追試では、梯子近くの下階足場を経由し、11段下降164tick、回収床までの移動156tick、上階への上り135tickが成功した。全行程で体力20、READY/jobなし。16箱のinspectと完成品104個のtakeも確認したとの報告を受領した。一方、上階から横にも離れた下階目標を直接指定すると、上階を探索して800tickに達するケースが残った。R7では先に梯子近くの既知の足場を指定することで回避できる。

## 遠い下階目標の追加修正 `ladder-far-r8`

同じ囲い付きfixtureで目標を `(313,101,301)` へ離し、`auto_replan:true`、距離40、800tickで実行。R7は上階を探索し続け、643tickの `distance_limit` で停止した。候補を3D直線距離だけで比較していたため、下りるよりも上階で横に近づく候補を先に選び、近づいた後は梯子へ戻る候補を距離増加として除外していた。

R8は既知の最終目的地を最優先に保ち、その次に、目標の高さへ進む安全確認済みの梯子区間を優先する。横方向に一時的に遠ざかる経路も許すが、次の1段の確認、除外セル、到達可能性、既訪問終点の抑制、全体の有限予算は維持する。未知の下階へ直接経路を作る変更ではない。

旧実装で失敗する2件の単体試験を追加し、Java 1,695＋harness 14＋admin bridge 28＝1,737件、build・harness隔離・source checksに成功。製品 `0f05022b5cf8a67b0ee20f3e0e7c665f0ad25358eaab397ed30a7244d527e15e` で次を確認した。

| ケース | 結果 | ticks |
| --- | --- | ---: |
| FarGoal：離れた下階足場 | succeeded | 274 |
| Enclosed：梯子近くの下階足場 | succeeded | 119 |
| LongUp | succeeded | 125 |
| Gap | failed / tick_limit | 600 |
| Cancel | cancelled / client_request | 24 |

全5ケース体力20、READY/jobなし。成功3ケースはserver側の接地を確認。記録は `artifacts/v2-ladder-far-20261010/r8-evidence.zip`、製品JARは同ディレクトリの `package-r8`。通常profileはR7のまま。地図アート担当からの依頼により、梯子だけの追加差し替えは急がず、Waystones／バッグ対応候補とまとめる。隔離環境はその継続検証に使用中であり、この段階では復旧完了を意味しない。

統合候補R15でもFarGoalを再確認し、server位置313.4994／101／301.5398、接地・体力20・cursor空で成功した。10:30 JSTに今回の元66ファイルをhash一致で復元し、10:32 JSTにcontainer／Docker Desktop停止と維持用SSH終了を確認した。[統合検証・製品hash・証拠](20261010_v2_waystones_docker.md)。
