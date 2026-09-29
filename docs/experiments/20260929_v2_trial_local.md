# 公開API v2：優先機能・試用導入の実機記録（2026-09-29）

利用者指定の6項目を実装し、このWindows PCの隔離profileで検証した。`clear_path`は任意・既定falseを維持する。通常profileへの初回導入完了判定はAppData仮想化による誤認だったため、末尾で訂正する。利用方法と残る範囲は[試用メモ](../PUBLIC_API_V2_TRIAL_20260929.md)を参照。

## 対象と手順

- 製品・試験source: `48852653c1e412a5070c0390bdd45d95c0374db4`。後続の記録commitは文書・公開証拠のみ。
- Minecraft 26.2、NeoForge 26.2.0.59、Java 25。独立profile `MCMCP-V2-Local-20260928`。
- 最終JAR SHA-256: `03c7943f7fcd78f06021db2224a5df5b47fd77a2bc98bd99c32c5a930fadece1`。
- 最終session: `13891b85-858f-4df8-b846-16516fd1cfeb`。[候補JARと試験metadata](artifacts/20260929-v2-trial/candidate.json)

試験worldは専用の高所平面と閉じた通路。準備用datapackをゲームUIでフェーズ前に実行し、操作検証は[Trial runner](../../tools/eval/Invoke-McmcpV2Trial.ps1)から製品MCPだけで行った。fixture-adminの機能や製品の公開command境界は拡張していない。これは決定的な機能smokeであり、fresh LLMの自律評価ではない。通常profileのsave・設定・認証情報は試験に流用していない。

## 最終JARの結果

| フェーズ・証拠 | 確認した動作 |
|---|---|
| [PathDefault](artifacts/20260929-v2-trial/path-default-07.json) | `clear_path`省略で障害物を越えず、有限時間で停止。次の試験でも壁2cellが残っていることを確認 |
| [PathClear](artifacts/20260929-v2-trial/path-clear-07.json) | `clear_path:true`でdirt 2cellの破壊をserver確認し、目的地まで到達 |
| [Bridge](artifacts/20260929-v2-trial/bridge-07.json) | 明示したsmooth stoneで欠けた足場2cellを補充。2回の設置と到達を確認 |
| [PathCancel](artifacts/20260929-v2-trial/path-cancel-08.json) | 障害物処理の対象が実行中になった後にAPI取消。同一IDのcancelledと実行所有終了 |
| [Entity](artifacts/20260929-v2-trial/entity-07.json) | 約6.5block先の観測済みcowへ接近し、搾乳のmilk bucket更新を確認。その後の飲用も確認 |
| [Conditions](artifacts/20260929-v2-trial/conditions-07.json) | 所持item・見えるfloor・画面noneの成立前停止、moveのitem条件、隠れたairを成立扱いしないこと |
| [Menu](artifacts/20260929-v2-trial/menu-07.json) | chest開封・16個の内容取得、要求15個の不一致をクリック前に拒否、16個のShift移動、閉鎖・再開封して空を確認 |
| [Special](artifacts/20260929-v2-trial/special-08.json) | 二段植物・ベッド・扉を設置。各アンカーと相方cellの変更前後stateをserver確認 |
| [Construction](artifacts/20260929-v2-trial/construction-07.json) | 1アンカーでcheckpoint保存・pause。別runで2件目から再開し、最初のaction IDを保持して完了 |

9フェーズとも合格。jobの受付だけで成功とせず、同じIDのterminalと結果を確認した。取消・失敗はworld変更を巻き戻さない。

## 実機で見つかった問題と扱い

- 障害物の1cell手前でplannerが止まる場合、隣接cellだけの探索では壁へ届かなかった。現在見える支持済みの空間越しに、reach内の障害物まで最大3cellを確認するよう修正した。
- 観測待ちでは操作数が増えず、`max_ticks`に達しない場合があった。経過client tickも予算に含める回帰試験を追加した。
- [初期修正版](artifacts/20260929-v2-trial/path-clear-05-failed.json)では2cell破壊後の経路失効で停止。[次の修正版](artifacts/20260929-v2-trial/path-clear-06-failed.json)でも、既発行cellの履歴と破壊後の視線による狭い通路の横ずれで到達できなかった。許可時だけ新しい経路証拠を待つ最大8回の再計画と、既知経路の先頭方向への有限の向き直しを追加。未知地形への移動許可や安全判定の緩和はしていない。
- 初回のmenu Shift移動は実際には移送されたが、新しいcursor packetが来ないため確認待ちになった。通常のVanilla収納のShift移動はcursorを変更しないため、既存の空cursor証拠を維持し、新しいcontainer snapshotと全数量・component一致で確認するよう修正した。加工・取引・独自menuや特殊slotはクリック前に拒否する。
- floorの中心rayだけでは斜めに見える面を判定できなかった。中心と6面のrayを使い、視覚・衝突・液体の遮蔽とloaded境界を保って確認する。
- [Specialの1回](artifacts/20260929-v2-trial/special-07-failed.json)では、先に置いたベッドが植物の支持面を遮り、植物設置が停止した。成功として数えず、初期化した独立fixtureで植物→ベッド→扉の順に確認した。この種の配置で自動的に回り込むことは保証せず、試用メモの制約に記載した。
- runner側のフェーズ引数上書き、狭すぎるfixture座標境界、PSCustomObjectへのContains呼出しを修正。空の実行や境界誤判定のrunを合格証拠に含めていない。

途中のEscによるcomputer-use中断は、製品APIの停止試験とは区別した。最終の9フェーズは同じ最終JARで完了している。

## 自動検査・復旧・導入

`gradlew test harnessTest adminBridgeTest verifyHarnessIsolation build`成功。Java 1,595件、harness 13件、admin bridge 28件でfailure/errorなし。`tools/check-source.ps1 -SkipJava -PythonExecutable python`成功。施工checkpointの9基本検査に加え、応答喪失時のintent保持・再送拒否、session変更時の保留、排他lockの4検査を追加した。mockは実機証拠と区別する。

ゲームとlauncherを終了後、試験後profileをprivate artifactsへ退避し、試験前の**12ファイルの個数・全SHA-256一致**で復旧した。[復旧receipt](artifacts/20260929-v2-trial/restoration.json)

初回はCodexから見える「くらふとぶ！-v01.2」のMCMCP JARを置き換え、同じ表示パスからbackup・hash・他MOD不変を確認した。しかし、このAppDataパスはCodexの`LocalCache/Roaming`へ転送されていた。`ca47984f…`の旧JAR・`mcmcp-backups/20260929-public-api-v2/`も仮想化領域の記録であり、ネイティブPrismの実体への導入証拠ではない。初回の導入完了判定を撤回し、[receipt](artifacts/20260929-v2-trial/installation.json)の確認範囲を訂正した。隔離profileでの9フェーズの結果とは区別する。

差し替え担当の2026-09-29 04:05 JSTの記録では、実体側の旧JARは`f4b6b782d99cfb94e6aef8143211c08de02045be51e26f81b2fc09ea4a4ee1bc`だった。担当が別名backupを作成し、ハンドルからの物理パス・新版hash一致・製品JARが1個であることを確認した。Prism GUIから起動・同じ接続先へ再接続・MCP ON後、04:07 JSTのAPI確認で公開13ツールと状態schema 2を確認している。現場担当も別途13ツール、有限script、短いuseと取消を確認した。粉の実設置と24時間実運転は未試験。[訂正・実体側の確認要約](artifacts/20260929-v2-trial/installation-correction.json)

公開JSONは[manifest](artifacts/20260929-v2-trial/manifest.json)でhashを照合できる。認証情報、private profile、ゲームlogは含めていない。任意MOD menu、加工・独自ボタン、縦施工、複合条件、詳細effect、高度な道具選択、遅延・再接続・全MOD組合せと独立レビューは残る。PR #85はDraftのまま、main統合・タグ・Release公開はしない。
