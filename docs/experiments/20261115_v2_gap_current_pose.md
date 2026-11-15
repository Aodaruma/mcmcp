# v2 gap jump の実位置と中心整列

関連: #98、#81。旧DSL側PR #83の経路追加は含めない。

## 再現

`ccf83a1282c253edb6b52183afb643bea49677d1` の隔離Docker試験で、長梯子の上下と上床→北床は成功した。北床→南床だけ105 tick、8回再計画、最終 `route_edge_changed` により `route_replan_limit`。体力20、落下損傷0、READYへ停止した。現在のbase `ace323e` は同じsource tree。

- 床: x=200..204、y=200、z=198..202。
- 壁: x=203、y=201..214、z=200。
- 梯子: x=202、y=201..214、z=200、facing=west。
- 上床(201,212,200)、北床(202,207,199)、南床(202,207,201)。
- 公開move: 南側feet(202,208,201)、max_ticks=800、max_distance=32、clear_path=false、auto_replan=true。

北床開始位置は `(202.42891572207378,208,199.38901230735303)`。最初の20 tickまでに `(202.58053958960556,208,199.5406361748848)` へ変わり、その後は位置不変のまま再計画した。公開traceには選択された内部edge／locomotionがなく、実行時の直接原因は未断定。

## sourceと幾何から分かること

候補生成は現在sourceもセル中心AABBに移していた。driverはjump前に空routeで中心へ整列し、許容0.12で停止した後、実AABBでarcを証明し直していた。

梯子の厚みは x=202.8125..203。幅0.6の中心AABBはmaxX=202.8で通るが、停止位置のAABBはmaxX=202.88053958960556で重なる。開始位置のarcは通る。中心から約0.0902の停止位置は旧許容0.12の内側でも、同じcorridorを通れる保証にはならない。中心モデルの候補は観測tickが更新されるたび復活し、既存A*は同距離でprobe不要のjumpを梯子経路より先に選べる。

## 最小修正

1. 現在sourceには実AABBを使う。未来のsourceは従来のセル中心と現在の体寸法を使う。
2. ballistic前の空routeによる中心整列を削除する。現在の接地とsource cell一致を確認し、入力直前に既存 `GroundTraversal.discover`、着地高さ、累計落下予算を再確認する。
3. 証明不可なら従来の再計画へ戻す。現在sourceの同じ不安全arcは再生成されず、観測済みの梯子経路を探索できる。

collision、loaded、fluid、hazard、落下予測、各tickのresolved movement検証、入力所有権、有限予算は変更しない。数値toleranceを縮める回避、新しいplanner、失敗履歴の保持は追加しない。

## 検証状態

- `MinecraftGroundTransferMapTest` の6試験がPASS: 実fixture小数位置と東西反転の幾何、9回の観測更新での梯子fallback、元の安全arc／通常gap維持、梯子出口欠損の拒否、未来sourceの体寸法、入力前の再証明と中心整列不使用の接続契約。
- 幾何・経路試験は乾いたloaded fixtureのcollision部分をモデル化する。worldを使った完全な安全証明の代用にはしない。
- `git diff --check`: PASS。
- 親レビュー: 製品2file・6回帰とdev-only固定fixture差分を確認し承認。
- focused: 関連Java123/123、harness3/3 PASS。
- full: Java1997/1997、harness18/18、admin29/29、`verifyHarnessIsolation`／`build` PASS。Gradle daemon停止済み。
- `tools/check-source.ps1 -SkipJava -PythonExecutable python`: transport／building／capability gate等のmock回帰PASS。
- CI: 実ビルドsource `7410c78` と、記録2docsだけを追従した `21407ac` はともに成功。
- 実機: 下記のこのPCでの5連続move受入がPASS。元profile全288hashの復旧照合は全実機試験終了後に親担当が行うため、まだ未完了。

今回のnative受入は長い上下・北床への移動・北→南の再現に限定する。南→北、通常gap、梯子・支柱欠損や追加障害物のnative試験は今回の5連続moveに含まれず、純粋幾何・経路回帰の範囲と区別する。#81全体や旧PR #83の完了とは扱わない。

## native用の固定fixture

dev-only harness の `mcmcp.fixture.v2Priorities` に `N:ladder_side` を追加する。上記の壁・梯子・3床を固定し、開始位置は `(202.5,201,199.5)`。初期化は x=199..205、y=201..216、z=197..203 の空間と、指定した床・壁・梯子だけを扱う。既存の300番台arenaは初期化しない。

所有者・非公開singleplayer・Overworld・server threadの既存認可とinputIdle確認後、対象の1 chunk `(12,12)` をpreloadする。最大fillは784block、固定16 commandを既存の再認可ループで実行し、reportと終了処理は共通のものを使う。任意座標・任意commandを受け付けない。whitelist、命令数・範囲、認可→inputIdle→preload→command再認可の接続契約に追加した2回帰もPASS。

## このPCでのnative受入（2026-11-15）

親担当が同じ製品／harness pairで実施し、終了済みartifact `20261115-local-acceptance/ladder-side-120422` のみを後から解析した。fixtureは開始前の固定配置と終了後のoracleだけに使い、T0→terminalは公開MCPだけで進めた。実行中のUI介入はない。

- 実ビルドsource: `7410c78a3187a0175f4df7b4f586fa6f4a988e6e`、version `0.2.0-rc.1`。
- 製品SHA-256: `aa0e63d65a5471ccf2fdde1f6c868434a7d574966cef8f0c6b56677b38b11832`。
- harness SHA-256: `1feedca25a1b29af01c01df0f6dbdb39cb59948e079b50806dd6bdac0ff9c3ec`。
- 5回とも `clear_path:false`、`auto_replan:true`、`max_ticks:1600`、`max_distance:64`。製品の予算契約を変更したものではない。旧失敗の引数800／32とは区別する。

| 区間／目標feet | 結果 | tick | 到着座標（x,y,z） | jump/drop |
| --- | --- | ---: | --- | --- |
| 開始→上床 (201,213,200) | succeeded/completed | 156 | (201.364725,213,200.505950) | 0/0 |
| 上床→下床 (202,201,199) | succeeded/completed | 126 | (202.428916,201,199.505063) | 0/0 |
| 下床→上床 (201,213,200) | succeeded/completed | 155 | (201.364725,213,200.478349) | 0/0 |
| 上床→北床 (202,208,199) | succeeded/completed | 80 | (202.428916,208,199.367361) | 0/0 |
| 北床→南床 (202,208,201) | succeeded/completed | 27 | (202.428916,208,201.501943) | 1/0 |

合計544 tick、61 tool calls（action取得48回）。全結果の `max_fall_damage`、`fall_damage_observed`、`fall_damage_budget_used` は0。全48取得に `path_replans`／`last_replan_reason` はなく、記録された再計画はない。公開結果に内部edge列や実経路長は出ないため、それらの実測値とは扱わない。

北→南は開始x=202.42891572207378を保ち、11 tickで `(202.42891572207378,208.72675925718283,200.5057918186922)`、23 tickで南床へ接地し、27 tickで成功した。不要な中心整列をせず安全な実poseのarcを使う修正と整合する。

最終公開statusはREADY、`running_action_id:null`。公開stateはHP20、air300、非水没・非炎上。終了後oracleは `on_ground:true`、`on_climbable:false`、`fall_flying:false`。入力所有guard付きの固定 `report` が `prepared` となり、終点座標も一致した。入力解放の受入証拠はこの最終statusとreportであり、各move途中の非公開所有状態を読み取ったものではない。

受入資料 `summary.json` のSHA-256は `60183a44fa92c6a9cd262f707b0da5bc670d161461143d3a4715e2ae4a0a795d`、`fixture-report.json` は `687b73c9556573c3c18137e1f8556f47054311d12e3cbe3a162b06ca5a719803`。元profile復旧の全288hash照合は、この受入とは別の最終作業としてpendingを維持する。
