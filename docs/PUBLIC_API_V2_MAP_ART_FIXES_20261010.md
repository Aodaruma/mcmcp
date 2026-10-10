# v2 地図アート利用時の修正

最新候補R18は、収納の同期不一致後も数量未確定を保持して所有cursorを回収し、終了待ち中の状態取得・取消が拒否され続ける問題を修正した。[隔離検証と通常R15の復旧手順](experiments/20261010_v2_storage_recovery_docker.md)。通常profileへのR18導入・新sessionは2026-10-10 13:17 JSTに差し替え担当が確認し、地図アート担当へ所有を返した。上段chestへの屋上迂回と移動中の被ダメージ原因、下記のscript停止child診断は別の残件。

この文書は開発候補 `merchants-r4` と後続の `ladder-r3` の追加仕様をまとめる。`merchants-r4` は2026-10-10 03:38 JSTに通常の「くらふとぶ！-v01.2」へ導入した。後続の `ladder-r3` も隔離Dockerで検証後、差し替え担当が導入し、05:27 JSTまでにPrism起動・サーバー接続・MCP ONと公開API照合を完了した。

## 修正内容

- **エリトラ装備での段差移動**：自動移動がジャンプを再入力したとき、意図せず滑空へ切り替わる処理を抑止する。エリトラを外す必要はない。エリトラ飛行そのものの経路探索は未対応。入力所有がない手動操作には適用しない。
- **レバー・ピストンの観測**：実際に見える `lever` / `piston` / `sticky_piston` の `state` に、向き・取付面・ON/OFF・伸長状態を返す。収納内容・block entity NBTなどの非表示情報を追加公開しない。設置対象の許可とは独立。
- **ディメンション移動**：同一接続内で別dimensionへ移った場合、通常のMCP ON意図を一度だけ引き継ぐ。古いjob・入力・画面・参照は終了し、新sessionの観測と新しいjobが必要。接続同一性、制御epoch、停止epoch、入力解放の完了を照合する。OFF・切断・死亡復活・停止競合・後始末未完了・評価用leaseは引き継がない。マルチプレイの一時許可は同じ接続に限定し、能力を元の範囲より増やさない。

## スクリプトの結果出力

```javascript
report(value=observe(kind="block", x=305, y=101, z=303));
let player=observe(kind="player");
report(value={label:"after_move", player:player});
```

`agent_get_action(action_id=..., include_result=true)` の最終 `result.reports` に呼出順で保存する。登録済みJSON/CSVから選んだ値、観測結果、操作結果、通常のデータを返せる。データは不変のコピーとして保持し、文字列を命令として実行しない。ファイル書込み・ネットワークアクセスは追加しない。

1 scriptあたり最大32件、合計512ノード（キーを含む）、深さ16、UTF-8 JSON合計16 KiB。構造・文字列の大きさをシリアライズ前にも制限し、共有配列を指数的に展開しない。コピーはwork予算と取消確認の対象。超過時はscriptを失敗で停止し、それ以前のreportを残す。ゲーム操作の取消・後始末が済むまで最終結果を公開しない。

## 検証

aod-mimoidの専用Docker・単一所有者の隔離worldで、次を確認した。

- エリトラ装備で1段上の足場へ移動成功。server側でも着地と `fall_flying=false` を確認。
- Overworld → End → OverworldでONを維持し、session IDを毎回更新。移動先の足場・体力を確認。
- UIで明示的にOFFにしてから同じ往復を行い、両移動先でもOFFを維持。
- レバーの `powered=true`、通常／粘着ピストンの向きと `extended=false` を観測APIから取得。
- scriptの `report(observe(...))` 相当が、レバーの状態を `result.reports` に返す。
- 死亡後の復活でOFF。試験準備の初回End足場が未ロードだったため落下が発生し、fixtureに移動先chunkのロードを追加して往復を再試験した。

Java 1,690件、harness 14件、admin bridge 28件、計1,732件の試験とbuildが成功。単体試験ではOFF／停止競合・接続変更・同dimension復活・後始末失敗・reportの共有グラフ／容量／不変性を検証している。実際のエンドポータル突入中のjob、マルチプレイ転送、Waystones経由の移動は実機未検証。

候補JAR SHA-256：`3a6e9fe6028b5e7a4d9d0cd509fef395ad306407c3679ab1769b44dacf9bd720`。実行記録はローカル `artifacts/v2-merchants-20261010` と同名のリモート評価ディレクトリに保存する。認証情報やworldの内容をGitへ追加しない。

2026-10-10 03:26 JST、今回の実機試験後も元world 61・設定3・JAR2の計66ファイルをハッシュ一致で復元し、fixtureを退避、検証containerを停止した。PowerShell／Python source checksも成功した。

## 通常プロファイルへの導入

Prism Launcher起動済み・Minecraft終了済みを確認し、旧R7のJARをバックアップしてから、検証済み候補の製品JARだけを交換した。ネイティブの物理path、導入前後とバックアップのSHA-256、製品JARが1個であること、検証用harness／admin bridgeの不在を確認した。

03:40 JST、Prism GUIから起動したゲームのMCP endpointについて、公開15ツールの全input/output schemaがcatalogと一致した。endpointの所有JVMの作業ディレクトリと起動時刻も照合し、対象profileが交換後に起動したことを確認。タイトル画面・MCP OFFで引き渡し、通常worldには入っていない。

ローカル記録と旧JARの保存先は `artifacts/v2-merchants-install-20261010`。`normal-installation.json`、`normal-verification.json`、`normal-before/`にそれぞれ導入・API照合・バックアップを保存した。

## 梯子下降の追加報告と案内の訂正

R4導入後、地図アート担当から次の再現情報を受領した。通常ゲームは同担当が所有し、この調査では操作・再起動していない。以下は担当からの報告であり、同じ現場をこちらから操作した結果ではない。

- 梯子は `(93,49..47,4)`、`facing=east`、背面の壁はx=92、上の足場はy=49。
- 開始 `(93.5814,49,3.7269)` → 目標 `(93,48,4)`、`clear_path=false`、`max_distance=3`、`max_ticks=180`、`auto_replan=false`。結果は `distance_limit`、終了 `(92.5876,49,3.5235)`。
- 続けて目標 `(93,49,4)`、`max_distance=3.5`、`max_ticks=180`でも `distance_limit`。終了 `(92.3317,50,3.8785)`で隣の縁に登った。
- 梯子49/48/47の可視情報はあるが、公開traversabilityに下降edgeがなかった。目標 `(93,50,4)` はsupport absent／BLOCKED。扉は明示interactで開け、内外の短区間を往復できた。

**以前の「観測した次の梯子1段を `agent_move` の終点にする」という案内は撤回する。** 現行の `KnownTraversabilitySnapshot.containsDestination` と `DeterministicAStar` は、足場のない梯子セルを通過点としてのみ扱い、終点には認めない。既存試験 `KnownTraversabilityNavigationTest.climbableRoutesUseUnsupportedCellsOnlyAsInternalTransitNodes` もこの契約を確認している。終点には梯子を経由した先の安定した足場が必要だが、今回の配置でそれを指定すれば成功するとは未確認。

ソースでは `CoordinateGoalPlanner` が既知の安全な候補へ部分経路を選び、`tickV2Move` が毎tickの実移動距離の累積で `distance_limit` を判定する。従って、直線距離が1ブロックでも迂回によって距離上限に達し得る。報告された迂回との整合はあるが、実際に選ばれたrouteの証拠がないため原因と断定しない。

公開traversabilityは、足場のない梯子の内部通過edgeを省く。従って、公開結果に下降edgeがないことだけで内部経路の不在とは判定できない。可視blockが返ることだけでも通過可能の証拠にはならない。

## 梯子修正版 `ladder-r3`

aod-mimoidの隔離Dockerで、東向きの梯子と同じ小数位置の入口を用意し、R4の下降が `local_hazard` で止まる問題を再現した。梯子へ入る最初のtickにVanillaの接地フラグが残っていたため、実際に接触中の安全な梯子を現在位置の落下判定へ反映した。梯子以外の落差判定は維持する。

さらに高低差11ブロックでは、局所観測の上下4段より先の足場が見つからず、上階を回り続けた。修正版は、目標の高さへ近づき、その先の1段も確認できた梯子を内部区間の引継ぎ点にできる。移動しながら観測を更新し、最後は安定した足場へ到達して成功する。梯子途中を利用者向けの最終目標として扱う契約には変更しない。

最終JARで短い／長い梯子の上下、下降途中の取消、3tick上限、欠損した梯子の計7ケースを確認した。上下4ケースはserver側の着地・体力20を確認。欠損ケースは落下せず600tickで停止したが、即座に経路なしと判断する改善は含まない。取消時は入力を解放するため、その後もVanillaの重力で梯子を滑り降りることがある。

Java 1,693＋harness 14＋admin bridge 28＝1,735件、build・harness隔離・PowerShell/Python source checksが成功。公開15ツールとschemaは変更なし。元world・設定・JARの66ファイルをハッシュ一致で復元した。[再現手順・結果・未確認範囲](experiments/20261010_v2_ladder_docker.md)。

製品JARのSHA-256：`55f1703ed44cd1cec6fd8b199d2702e5964a3278a26161352d9c4008aaea0a94`。ローカル保存先は `artifacts/v2-ladder-20261010/package-r3`。通常profileの交換・再接続・MCP ONは利用者指定の差し替え担当が完了した。旧版バックアップ、導入先hash、交換後起動のJVM、新session、READY/jobなし、15ツール全schema一致を同担当の `artifacts/v2-ladder-install-20261010/normal-verification.json` で確認。現場での追加移動・試験は行っていない。

## 囲まれた梯子の入口の追加修正

通常 `ladder-r3` 導入後、壁に囲まれた入口で1歩進んで停止する追加報告があった。現場の入口座標の小数部分と上段の壁・たいまつを隔離fixtureへ再現し、`movement_stalled` を確認。梯子の薄い当たり判定へ身体の端が重なる一方、経路制御が横ずれを許容して前進していた。追加候補 `ladder-field-r7` は確認済みの通路内で先に横位置を合わせる。衝突・落下の安全判定は緩めない。

同候補は囲まれた入口からの11段下降、通常の短い／長い梯子上下、取消、tick上限、欠損梯子の8ケースに合格。全ケース体力20、終了時READY/jobなし。公開15ツール/schemaは変更なし。製品JARはローカル `artifacts/v2-ladder-field-20261010/package-r7`、SHA-256 `ee1a94e94cecdf3962b1cdc05fef0433b2836c8a2ae124399a6ce0d626aaa57c`。通常ゲームの所有を地図アート担当から差し替え担当へ渡した後に導入する。Waystones・バッグ対応はこのJARには含まない。[追加検証の詳細](experiments/20261010_v2_ladder_docker.md#囲まれた入口の追加修正-ladder-field-r7)。

06:03 JSTに差し替え担当が旧版backup・製品hash・交換後起動・再接続・MCP ON・新session・READY/jobなし・15ツール全schema一致を確認し、地図アート担当へ所有を返した。記録は `artifacts/v2-ladder-field-install-20261010/normal-verification.json`。開発側は通常ゲームを並行操作していない。

## 最新候補と残件

- Sophisticated Backpacksのネザライトバッグ：回収upgradeタブ開放のlayout拒否と、拡張収納のPICKUP予測を修正。候補R15で端数take24・store16・再open、移送中取消・クリック上限拒否を含む5ケースが合格。upgrade設定・cursor・数量保存も確認。[検証記録](experiments/20261010_v2_backpack_docker.md)。
- Waystonesの行き先一覧／選択：`agent_interact`へ追加し、R15で経験値上限拒否・取消・Esc停止・End往復を実機確認。自動試験1,746件、公開15ツール全定義照合も成功。検証環境の66ファイル復元とcontainer／Docker Desktop停止を完了。10:43 JSTに差し替え担当が通常導入を完了し、現場でのバッグ収納・Waystones往復、有限scriptの15秒稼働後の設備OFFも成功。[仕様](PUBLIC_API_V2_WAYSTONES_20261010.md)、[統合検証と未確認範囲](experiments/20261010_v2_waystones_docker.md)。
- raw入力がポータルや梯子の縁で止まる報告：raw入力としての再検証は未実施。`agent_move` は現場の上段配置を再現した固定fixtureで修正確認済み。現場の下階全体、MOD製の梯子、通信遅延は未検証。
- 金床・エンチャント、MOD収納の対応拡大、施工全体の保存・再開は引き続き残件。取引の追加は[取引仕様](PUBLIC_API_V2_MERCHANTS_20261010.md)を参照。
- script内の移送失敗時に、停止した子操作のID・結果・effectsが親resultへ残らない。部分実行の数量監査を改善する候補として、下記に記録。R18には未実装。後続R19で実装・自動試験済みだが、実機確認・追加導入はしていない。

## スクリプト内移送失敗の診断情報（改善候補）

2026-10-10、地図アート担当から通常R15での報告。script `5b82b043-7917-4113-89a5-3719b88774c1` は黒take2・store2の後、ホッパーが流入中のチェストから灰take10で停止した。親は`script_command_failed`、`detail:"Command failed"`、`completed_commands:2`／`calls:3`となり、失敗childのaction_id・result・effectsが公開結果に残らなかった。報告によるとfresh inventoryには灰10があり、元チェストの再読では灰がなかったため、takeを再送せず、別のstore10と再観測で数量を確認・解決した。流入が失敗原因だったかは未確定。当担当はこのjobの原始traceを未取得で、通常ゲームで再現試験をしていない。

コード上は`ActionScript`のcall処理が失敗時に`Stop(FAILED,"Command failed")`を投げ、その後の`sink.result()`取得へ進まない。`ScriptJobExecution.finishIfReleased`も親のsummary・明示的reportsのみを保存する。したがって後続の`report(value=...)`では停止childを記録できない。失敗の診断情報が失われる経路は静的確認できたが、今回の移送失敗そのものの原因確定とは区別する。

改善案は、最後に停止したchild 1件のaction_id・操作種別・state／stop_reasonと、既存の公開方針で絞ったresult／effectsを、親resultに独立した上限付き領域として保持すること。confirmed_count、未確認の変更、release_pendingを混同せず、容量・深さ・要素数と打切り表示を定める。report領域が満杯でも最小の診断情報を残し、終了時の入力・cursor解放を確認する契約を維持する。`on_failure:continue`の対象拡大や失敗した移送の自動再送で回避しない。

実装時は、部分移送後の失敗、確認不能なclick、report上限、取消・world変更での保持内容と不変性を検証する。現時点は記録のみ。通常ゲームと設備の所有は地図アート担当が維持し、報告時点では装置OFF・回収と台帳整理中。


## R19候補での実装（実機未確認）

上記の停止child診断と、操作できない屋根を選ぶ接近処理を修正した。親scriptの `result.last_command` はreportと独立してID・結果・UNKNOWN/effectsを有界に保存する。収納・設備・interact・破壊では、近傍の終点に目線からのreachと対象へのrayを要求し、経路なしで移動し続けない。下降制限は維持した。Java等1,765件とbuildが成功。R18のJARは固定し、R19の隔離実機試験と通常導入は未実施。[仕様・試験・次回手順](experiments/20261010_v2_approach_script_candidate.md)。


## R20候補：収納枠境界（R18ベース、実機未確認）

R18導入後、既存収納1,816個から448個を一度にstoreすると、14クリック内の計画を作れず事前拒否するモデルをソースで再現した。従来順序で計画できない場合だけ、全スタックが入る枠を優先する有限な別計画を1回試す。上限・同期確認・UNKNOWN非再送を維持し、モデルでは2,008＋256個へ14クリックで収納できる。R18ベースで自動1,754件・build成功。R19とは別の候補で、実機確認・通常差し替えは未実施。[詳細と新fixture](experiments/20261010_v2_storage_boundary_candidate.md)。

## 扉端での安全判定拒否（調査・診断改善）

通常R18の上段扉付近でmove失敗後、新規move／観測scriptが `SAFETY_PRECONDITION` となった。局所衝突でREPLANになった場合の受付拒否はソースで確認したが、当時のhazard明細がなく現場原因は未確定。後続の位置変更と復帰は手動操作によるもので、公開APIの退避成功には含めない。

判定順序・許可条件を維持して、公開エラーmessageへ固定の拒否理由、局所安全による拒否なら保持しているhazard分類・観測tick／revisionを追加した。自動1,774件・build成功。R18～R20の固定配布物は維持し、追加差し替えは依頼しない。直接state/status/observationによる採取手順と、扉・感圧板の隔離再現、自律退避の未実装範囲は[調査記録](experiments/20261010_v2_safe_local_diagnosis.md)を参照。

## 破壊failed後の対象消失（診断改善の残件）

R18の黄緑→白の種交換で、breakは `block_runtime_failed` / broken_blocks:0だが直後のfresh観測ではair・直下glassを確認した。破壊を再送せず、別の白設置と再観測で復旧済み。失敗と観測された効果を分けて保持し、再起動・差し替えは不要。[事実・ソース候補・未実装の診断案](experiments/20261010_v2_break_post_effect_diagnosis.md)。長い上階目標で停止し短いwaypointでは到達した報告も同記録に残す。
