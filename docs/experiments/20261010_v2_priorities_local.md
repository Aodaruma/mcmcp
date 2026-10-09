# v2優先機能：ローカル実機検証

2026-10-10、Issue #84／Draft PR #85。Minecraft 26.2／NeoForge 26.2.0.59。利用者の許可を受け、このPCの専用 `MCMCP-Validation` profileで検証した。起動・MCP ON/OFF・終了はPrismとMinecraftのGUI、試験中の行動は公開MCPを使用した。

## 候補

- 最終候補 `priorities-r7` main SHA-256：`90a63cabbf3a1358ee176e06a218565fc108b3996ec3cd48c64242d2d1fbd69f`。
- harness SHA-256：`96e5057e8b6d59eb28c64db657d7453306b1910dcb4105435dbc80b3ca88e5b0`。
- 検証MOD：Sophisticated Backpacks 3.25.90.2084／Core 1.4.99.2265。通常profileのworld・設定を試験材料へ流用していない。
- 最終候補R7で以下の13ケースがすべて合格。再計画直後の水面到着にも酸素回復待ちを適用した。

## 実機ケース

| ケース | 確認内容 |
| --- | --- |
| Components | 名前付き原木→板材8、棒8、名前付き石→かまど2。名前・damage 10・Unbreaking I付き釣竿→ニンジン付きの棒1。残数と確定製作数を照合 |
| Inventory | 36枠満杯で異なる名前の石を交換ソート。同データの板材8＋16を24へ統合。頭装備・オフハンドへ移送。石32・板材24を保存、cursor空 |
| Cake | ケーキ2と空バケツ6。材料消費・戻り容器・終了時のmenu解放 |
| Cancel | 手元クラフトの配置途中で取消。確認済み完成品と原材料の保存、cursor回収 |
| TableCancel | 作業台の材料配置中に取消。石＋かまど×8＝16、確定数一致、解放待ちなし |
| Resources | 外部sourceとCSV、player観測、all/not条件から行動分岐 |
| ModBreak | 実在する `sophisticatedbackpacks:backpack` を通常攻撃で破壊し、server変更を確認 |
| Blueprint | 4設置・2分割を1設置で中断→残り3設置を再開→再実行0設置。外部JSON scriptでさらに2設置。石32→26 |
| Water | 水平・浮上・潜水・岸への上陸、4 job。体力20を維持 |
| WaterAir | 初期air 120から元目標を中止して水面へ退避。`air_supply_retreat`、元目標・退避理由、体力20・air 300への回復を確認 |
| Boat | 通常乗車→水面移動・減速。乾いた降車先のない水上では、入力前に `dismount_landing_unverified` で拒否 |
| Minecart | 乗車→約10 block・複数の局所経路→無通電レールで停止→server確認付き降車 |
| Horse | 飼い慣らした鞍付き馬へ乗車→移動・停止→server確認付き降車 |

トロッコの目標は停止用レールの先頭block。停止用レールのさらに奥は通常入力で届かないため、そこを成功条件にしない。ボートの水中への降車は今回の安全な足場契約の対象外。

## 実機で発見した修正

- 3×3クラフトで、正常なPICKUP中のcursorを汎用menu guardが拒否していた。クラフト自身のserver snapshot照合へ委ね、所有・menu・slotの確認は維持した。
- 取消時にcursor返却が確認済みでも、古い転送状態を再確認して終了待ちになっていた。確認後に転送を完了扱いにした。
- MOD破壊の照準解決にVanilla限定条件が残っていた。明示対象のfilterを渡し、現物の登録・硬度・到達・通常攻撃の検証へ接続した。
- 水中の小数Y座標と整数cellの到着判定が食い違っていた。実位置の到着判定を使用する。
- 配送確認前の酸素退避で実行結果を記録していた。結果記録はRUNNING移行後へ延期し、配送前に行動しない契約を維持した。
- 水面到達では酸素回復も待つ。job終了後は入力を解放するため、通常の沈下は再開する。水面を永続的に維持する機能ではない。
- 接地中の重力速度と乗員の座席offsetを、トロッコ・馬の地面衝突や移動継続と誤判定していた。乗り物の床面と乗員の側面・頭上を検証し、接地済みの下向き速度を停止判定から除外した。
- fixtureの遠方chunkを準備前にロード。観測試験は必要な種類・IDだけ取得してpagination leaseを使い切らないよう修正した。

## 自動検証と限界

`gradlew test harnessTest adminBridgeTest verifyHarnessIsolation build`：Java 1,677＋harness 14＋admin bridge 28＝**1,719件成功**。`tools/check-source.ps1 -SkipJava`も成功。座標境界、配送前退避、MOD照準証拠、乗員体積・接地速度、酸素回復待ちを回帰試験へ追加した。今回の追加Codex CLIレビューはCLIと指定modelのバージョン不一致で実行できず、独立レビュー済みとは扱わない。

128×128×9＝147,456セルの分割・模擬反復は自動検証、小規模施工の中断再開は上記の実機検証。147,456セル全量の実施工、全MOD・通信遅延／拒否／切断の組合せ、曲線・坂道・複数車両の網羅は未検証。先行の飛行・製錬・醸造の全ケースを今回すべて再実行したものでもない。

## 復旧・通常profile導入

ローカル検証profileは開始前の292ファイルを復元し、全ファイルのhash一致を確認。試験後のprofileも別途保存した。

2026-10-10 02:05 JST、Prism Launcherを先にGUI起動した状態で、通常の「くらふとぶ！-v01.2」へ最終候補R7を反映。ネイティブprocessから開いたJARハンドルの物理pathを確認し、AppData仮想化領域への誤配置を防いだ。元JAR（SHA-256 `227ae54631d1b09f3fbd75f617bfcd03c1eaf348cef48f4b0e6416ea516a597c`）を退避、新JARのhash一致・製品JARが1個・test harness不在を確認した。

Prism GUIから通常profileを起動し、02:07 JSTに公開15ツールの全input/output schemaが正本catalogと一致することを確認。Minecraft 26.2／NeoForge 26.2.0.59／32 modsのタイトル画面、MCP OFF、world未接続で引き渡した。通常worldは開いていない。証拠は `normal-installation.json` と `normal-verification.json`、元JARは導入artifactの `normal-before` に保存。

先行のリモートDockerはSSH timeoutのため、66ファイルの復旧をまだ確認できていない。利用者へ接続確認を依頼済み。ローカル成功をリモート復旧完了として扱わない。

生の実験結果と復旧用ファイルは親workspaceの `artifacts/v2-priorities-local-20261010` と `artifacts/v2-priorities-install-20261010` に保存し、Gitへ含めない。main統合・タグ・Release公開は行わない。
