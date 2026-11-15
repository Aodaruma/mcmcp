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
- 実機: 未実行・未合格。公開前に版付き製品とharnessの組で受入を行う。

公開前には同fixtureの北→南／南→北、通常gap、梯子・支柱欠損と障害物による停止、損傷・入力解放・READY復帰を候補JARで確認する。

## native用の固定fixture

dev-only harness の `mcmcp.fixture.v2Priorities` に `N:ladder_side` を追加する。上記の壁・梯子・3床を固定し、開始位置は `(202.5,201,199.5)`。初期化は x=199..205、y=201..216、z=197..203 の空間と、指定した床・壁・梯子だけを扱う。既存の300番台arenaは初期化しない。

所有者・非公開singleplayer・Overworld・server threadの既存認可とinputIdle確認後、対象の1 chunk `(12,12)` をpreloadする。最大fillは784block、固定16 commandを既存の再認可ループで実行し、reportと終了処理は共通のものを使う。任意座標・任意commandを受け付けない。whitelist、命令数・範囲、認可→inputIdle→preload→command再認可の接続契約に追加した2回帰もPASS。
