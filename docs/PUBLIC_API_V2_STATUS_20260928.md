# 公開API v2：進捗と再開メモ（2026-09-28）

## 最新更新：優先6項目と試用JAR（2026-09-29）

指定された優先機能を実装し、このPCの隔離Minecraftで9フェーズを確認した。「くらふとぶ！-v01.2」への初回導入はAppData仮想化領域への配置であり、実体への導入完了という判定を撤回する。その後、差し替え担当が物理パスを確認して実体へ導入し、Prism GUI再起動後の公開13ツール・schema 2を確認した。[試用メモ](PUBLIC_API_V2_TRIAL_20260929.md)と[実験記録](experiments/20260929_v2_trial_local.md)を参照。

| 機能 | 現在使える範囲 |
|---|---|
| 移動中の障害物処理 | `clear_path`は任意・既定false。水平の局所破壊と、`bridge_block`で明示した材料による足場補充。server確認後に再観測して進む |
| 遠方の対象への操作 | block・収納に加えて、観測済みentityにも既定`advance:true`で接近。見失った対象や到達不能では停止 |
| 条件の拡張 | move／input_sequenceの`stop_when`に座標・見えるblock state・所持item数・画面種類 |
| 特殊な設置 | ベッド・扉・二段植物。相方cellも事前確認し、両cellのserver更新を照合 |
| メニュー操作 | 開封・内容取得・限定されたVanilla収納の全stack Shiftクリック・閉鎖。slot/item/count条件とserver確認を要求 |
| 施工の保存・再開 | PC側のv2専用runner。1アンカーごとに操作IDと結果を保存し、確定済みの作業を飛ばして再開。応答不明は自動再送しない |

Java 1,595件、harness 13件、admin bridge 28件とbuild、PowerShell/Pythonのsource checksが成功。実機JARと通常profileへ導入したJARのSHA-256は`03c7943f7fcd78f06021db2224a5df5b47fd77a2bc98bd99c32c5a930fadece1`。隔離profileは元の12ファイル・全hash一致で復旧した。

残る主な機能は、任意MODのmenu／収納provider、クラフト・取引・独自ボタン、複合条件・任意entity状態条件、縦方向の障害物施工、高度な道具選択、詳細effect台帳、menuを開いたままのscript合成。新品backpackの自動初期化は未対応。収納の遅延同期・応答待ち取消、全MOD組合せ、PR全体の独立レビューは追加検証として残る。PR #85はDraftを維持し、main統合・タグ・Release公開はしていない。

追加指定により右クリックだけに限定せず、全行動の長時間・多回数指定を開発branchへ追加した。[引数・上限・停止契約](PUBLIC_API_V2_LONG_EXECUTION_20260929.md)と[追加検証記録](experiments/20260929_v2_long_execution.md)を参照。`f7871718…`版では短い隔離入力試験A–H（補充・取消・期限・背景継続・1,500tick）、通常profileへの実体導入・再起動後の新schemaを確認し、隔離baselineも復旧した。24時間連続実測は未実施。通常環境の収納inspect終了待ちも`227ae546…`版で修正・導入済み。作業台タブ開放時の安全な失敗・解放と、タブ閉鎖後の読み取り成功を[実機再確認](experiments/20260929_v2_storage_cleanup.md)した。

2026-09-30: [設計検討メモ](PUBLIC_API_V2_NAVIGATION_FOLLOWUP_20260929.md)の後続実装として、既定の非破壊再計画（`auto_replan:false`で無効化）、停止理由の詳細化、`sneak`での姿勢準備、操作前の照準再取得、視点専用`agent_look`を追加した。公開14ツール。隔離Dockerで、途中障害の迂回と無効化時の停止、はしご上端のdust/rail、1.5/1 block通路、周期door/piston越しのlever/chestと通過、lookの取消・期限・scriptを確認した。[結果と未確認範囲](experiments/20260930_v2_navigation_docker.md)を参照。通常の現場配置を含む全形状の成功は未確認。

以下は履歴。未実装一覧は当時の状態であり、現在の範囲は上の表を優先する。

## 履歴：所持収納と追加停止経路（2026-09-29）

利用者が指定した「所持収納」「追加停止経路」の実機確認を、このPCで完了した。[追加試験の記録・証拠](experiments/20260929_v2_storage_stop_local.md)を参照。製品の既存実装は今回の範囲で正常に動作し、再実行可能な試験runner・fixture・回帰mock 13件を追加した。製品Javaの変更はない。

- Sophisticated Backpacksの空収納inspect、store3、take2、毎回の再開封照合が成功。収納内snowは0→3→1、所持品は16→13→15、未確定移送なし。
- shield使用中のAPI取消、物理Esc、UI OFF、次元移動の4経路で、指定した理由による停止と入力所有終了を確認した。UI OFF・次元移動ではcontrol OFF、次元移動では新sessionを確認した。
- OFF中の操作拒否、手動再許可後の新しい操作成功も確認。専用profileは12ファイルの個数・全hash一致で復旧し、ゲーム・launcherを終了した。
- `tools/check-source.ps1 -SkipJava`成功。transport 15件、smoke mockは既存7件＋追加13件。製品JARは前回と同じ`6acde6beacbea21c812c390568332ccf5bcd8a21005204085ff3eda633dbdde5`。
- M7の基本経路と今回の追加経路は実機確認済み。全機能の完成や全停止経路の網羅とは区別し、PR #85は引き続きDraft。

### 現在の主な未実装機能

| 機能 | 現在の範囲と不足 |
|---|---|
| 一般的なmenu／GUI操作 | `interact`によるmenu操作とmenu内clickが未対応。対応収納の開封・数量移送は専用経路で動作する |
| 移動中の障害物処理 | `agent_move`が必要なblockを壊す・置く処理が未実装。`break(advance:true)`の接近しながらの採掘は実装・基本実機確認済み |
| 遠方entityへの自動接近 | entity操作は観測済みかつ手の届く対象まで。接近してから操作する一連の処理が未対応 |
| 停止・到達条件の拡張 | 座標以外のblock・item・画面状態等の条件、raw入力の追加guard・効果照合が未完了。今回確認したEsc等の停止経路とは別の機能 |
| 特殊な複数cell設置 | ベッド・扉・二段植物など、1回で複数cellを変える設置が未対応。単一cellを直方体内で順に置く処理は対応済み |
| 収納providerの追加 | 対応済みJARのSophisticated Backpacks／Core以外はprovider・同期profileが必要。新品backpackの自動初期化も未対応 |
| 高度な道具選択・効果記録 | 耐久・Silk Touch／Fortune等の条件を含む選択、破壊drop・消費item等の詳細effect記録が未完了 |
| 長期の施工保存・再開 | v2のjob保持は最新と直前の完了jobまで。旧施工台帳と統合したプロジェクト全体の永続保存・再開は未完了 |
| scriptの追加仕様 | menuをまたぐ実行、命令ごとの失敗詳細・観測量予算の整備が残る。既存の同期合成は基本実機確認済み |

楕円・パス等の任意形状と任意キー入力は拡張候補。完全なJavaScript、host I/O、任意commandは対象外であり、未実装の欠落とは扱わない。

### 実装済みだが追加検証が必要な範囲

収納transferやSWAPの応答待ち途中取消・遅延同期、装備枠／offhand／upgrade付き収納、他item・他action種類での停止、ログアウト／再接続が残る。今回の所持収納は通常UIで初期化したhotbar slot 2のbackpack、world境界は次元移動を対象にした。前回Hazardの停止後落下を避けるfixture改善、観測の実利用token測定、PR全体の独立レビューも未完了。

以下の2026-09-28更新と停止時点の表は履歴であり、上の最新結果を優先する。

## 再開後の更新（2026-09-28 23:26 JST）

利用者の指示で開発を再開し、このPCの独立した検証profileで実機smokeを行った。[実験記録と証拠](experiments/20260928_public_api_v2_local.md)を当日の結果として参照する。下の停止時点の表は履歴として残す。

- entityのPASS後に無関係な手持ち変化で成功し得る判定を修正。仕様・catalog・単体回帰2件を同期した。
- 実機で発見した数量投棄の確認待ちtimeoutと、接近後の破壊照準の外れを修正した。製品commitは`e79f59888e2aece6d426437e0d4c545f8e27384a`。
- 修正JARでItemEntity／Core／Cancel／Hazardの4フェーズが成功。搾乳・飲用、所持品・チェスト移送、gate、2cell設置・破壊、10cell採掘、script、取消・危険停止を確認した。
- Hazardの停止判定後、終了までの間に検証プレイヤーの落下死亡も確認。停止後の生存保証を合格扱いせず、試験手順に記録した。
- Java 1,577件、harness 13件、admin bridge 28件とbuild成功。専用profileは試験前12ファイルの個数・全hash一致で復旧し、ゲームを終了した。
- M7の基本smokeと復旧は確認できたが、所持収納のv2実機、未実装機能、追加停止経路、PR全体レビューは残る。PR #85は引き続きDraft。

## 以下は再開前の停止時点の記録

利用者の依頼により、ここで開発を一時停止する。**v2は未完成・未リリース**。基本行動の公開と実行器への接続、単体検査・CIは進んだが、新しいv2経路の実ゲーム試験と一部の行動範囲が残る。実装済みを、実機確認済みと読み替えないこと。

この文書は停止時点のスナップショット。[設計案](PUBLIC_API_V2.md)は完成目標も含むため、現在使える範囲は下表と[クイックガイド](MCMCP_Public_API_v2_クイックガイド.md)を併読する。

## 目標と現在地

LLMは目的・座標・範囲・条件・反復を指定し、MODが局所観測、経路、道具選択、照準、入力、結果確認を担当する。観測文字列と操作記述を減らし、JSON Action DSLを短い行動ツールと制限付き同期スクリプトへ置き換える。

完了には、合意した操作範囲の実装、公開仕様とテストの一致、隔離環境での主要成功・取消・危険停止、試験環境の復旧、レビュー可能なPRが必要。**mainへの統合・タグ・Release公開は、追加の明示指示まで行わない。**

| 項目 | 停止時点の状態 |
|---|---|
| 主対象 | [Issue #84](https://github.com/Aodaruma/mcmcp/issues/84)／[Draft PR #85](https://github.com/Aodaruma/mcmcp/pull/85) |
| branch／worktree | `feat/public-api-v2`／`workspaces/public-api-v2` |
| 実装・試験準備の基準commit | `96fd6e1d759d24f23bc293b7f12f382bba394b73`（この整理文書の追加前） |
| 製品コードの最終変更 | `a1a2a6abce7378348845dcfdb46e813ce9fd5f74`。その次の`96fd6e1`は試験runner・fixture・検査・文書 |
| GitHub状態 | PRはOPEN・Draft。Issueの`release:verification-needed`を維持 |
| 優先順位 | v2を主軸。旧v1の難所をv2の着手条件にはしない |

## 実装マイルストーン

「接続済み」は公開toolから実行器までコードがある状態。「検査済み」は単体・mock等の範囲であり、ゲームの成功証拠とは別である。

| 段階 | 達成したこと | 残ること／合格条件 |
|---|---|---|
| M1 責任境界と公開契約 | AGENTS・設計・catalogを改訂。13ツールを公開 | 残る機能の実装に合わせて仕様を同期 |
| M2 軽量な状態・観測 | state既定をプレイヤーとhotbar 9枠へ縮小。MCP情報を分離。block単位の観測、詳細の明示取得 | 実ゲームの読みやすさ・取得内容を確認。token削減率の実測は未実施 |
| M3 共通job・スクリプト・raw入力 | 配送後実行、共通ID・照会・取消・解放。同期言語と有限入力列を接続。公開JSON DSLを撤去 | 実ゲームの入力所有・取消・危険停止、追加停止条件、menu内操作 |
| M4 移動・範囲施工 | 座標／方向move、直方体break/place、`break(advance:true)`による接近＋採掘を接続 | move中の自動破壊・設置、特殊な複数cell設置、道具選択の拡張、実ゲーム試験 |
| M5 interact・inventory・収納 | block／item／entity、所持品inspect/swap/drop、座標収納・所持収納transferを接続 | menu操作、遠方entity接近、他MODのprovider／同期profile、実ゲーム試験 |
| M6 ソース検査・試験準備 | Java・ハーネス・transport等とCI成功。4種の実機試験runner／fixtureを準備 | runnerを実際の隔離Minecraftで動かす。mock成功だけで合格にしない |
| M7 実ゲーム・復旧 | 今回のv2経路では未実施 | 成功・取消・危険停止の証拠、導入JAR照合、world・MOD・設定の復旧hash |
| M8 レビューとPR完成 | Draftへ実装・制限・検査を記載。item/entity周辺の限定レビューを実施 | 指摘の評価、残る差分のレビュー、実機結果を反映して完成判断。公開は別承認 |

## ツール別の実装範囲

| 公開ツール | 接続済みの範囲 | 未対応・検証上の注意 |
|---|---|---|
| `agent_get_state` | プレイヤーとhotbar 9枠。全inventory等は明示section | 新しい既定応答の実機smokeは未実施 |
| `agent_get_mcp_status` | 制御状態、session、frame、実行中action等 | 状態取得とworld観測を分離 |
| `agent_get_observation` | 引数なし最新frame、block集約。詳細証拠・通行情報は明示要求 | 非表示の座標はunknown。既定でunknown boundary一覧を返さない |
| `agent_move` | 座標／方向へ、局所観測した安全な経路を区間ごとに進む | 経路中の自動破壊・設置、追加の到達条件は未実装 |
| `agent_break_block` | 単一cell／符号付き直方体、block条件、道具選択、`advance:true`の連続採掘 | Vanilla対象。耐久・エンチャント条件等は未完了。任意形状は将来候補 |
| `agent_place_block` | 単一cell／直方体、任意state、支持面・持ち替え・サーバー確認 | ベッド・二段植物等の複数cell設置は明示拒否。全Vanilla設置対応は未達 |
| `agent_interact` | block state変更、有限のitem使用、手の届く観測済みentityへの通常操作 | menu・遠方entity接近は未対応。受付と固有効果の成功を区別する |
| `agent_inventory` | 全所持品inspect、main→hotbar SWAP、数量drop、収納inspect/transfer | 収納slot・計画の対応範囲あり。任意MOD収納への自動対応は未達 |
| `agent_click` | world内の左右中ボタン、回数・押下時間・間隔、任意対象照合 | menu内click、追加guard・効果照合は残る |
| `agent_input_sequence` | 9種の論理入力、同時／順次、押下tick・間隔・有限反復、座標停止条件 | 任意キー、block・item・画面状態等による追加停止条件は未実装 |
| `agent_run_script` | 名前付き引数、変数、分岐、有限loop、小関数、8種の行動を合成 | JavaScript風の制限言語。完全なJS、async/await、host I/Oは対象外。実機未検証 |
| `agent_get_action` | 共通IDの進行・成否。明示要求で部分結果・確定cell等 | 保持は最新jobと直前の完了job。任意期間の施工履歴や永続再開ではない |
| `agent_cancel_action` | 指定jobを中止し、所有入力・menuの解放後に終了確定 | 確定済みのworld変更は巻き戻さない。新経路の実機解放確認は未実施 |

公開名・説明・入力schemaを合わせたcompact JSONは127,779文字から23,966文字へ約81%縮小した。**tools/listの文字数比較であり、観測応答全体や実利用tokenの81%削減を測定したものではない。**

所持収納は[PR #82](https://github.com/Aodaruma/mcmcp/pull/82)の共通基盤を再利用し、発見・開封・個体確認と、slot同期・数量移送を分離した。組込みproviderは現在Sophisticated Backpacks 3.25.90／Core 1.4.99。他MODはprovider／同期profileの追加が必要。共通化は進んだが、対応MODが自動的に増えたわけではない。transferは最大14 source stack／14 PICKUP入力で計画できる範囲に制限される。

はしご・斜め移動等の既存機能、旧施工台帳の試験実績を、今回のv2経路の実機合格へ転用しない。過去の「全体の進捗保存・再開」という要望についても、v2の短期job結果保持だけで達成したとは扱わず、旧台帳との関係を再開後に整理する。

## 検証結果と証拠

| 検証 | 結果・対象 |
|---|---|
| Java単体 | 1,575件成功、失敗・error・skip 0。製品コード`a1a2a6a`で実施 |
| ハーネス | 13件成功。build／harness分離検査も成功 |
| fixture admin bridge | `96fd6e1`で28件成功。新fixtureは既存のcommand許可範囲内 |
| MCP transport | 15件成功 |
| source check | `a1a2a6a`で全体成功。試験tool追加後は`-SkipJava`とadmin bridgeを再検査して成功 |
| 新smoke runnerのmock | 7ケース成功。誤session、応答消失、失敗、取消、危険試験の引渡し等。実ゲームではない |
| 評価trace self-test | `a1a2a6a`で66/66成功 |
| GitHub CI | [`96fd6e1`のbuild成功](https://github.com/Aodaruma/mcmcp/actions/runs/36426925108)。前の製品コードの[buildも成功](https://github.com/Aodaruma/mcmcp/actions/runs/36424315234) |
| 新v2経路の実ゲーム | **未実施**。飲食・entity・収納・施工・取消・危険停止・長押しmixinを未確認 |

準備済みの[隔離smoke手順](../tools/eval/fixtures/public-api-v2/README.md)は次の4フェーズ。全機能の網羅試験ではなく、まず基本経路を確認するためのもの。

1. `ItemEntity`：cowからの搾乳とmilk使用を、手持ちのserver更新で確認。
2. `Core`：観測、SWAP・投棄、チェストtake/store、gate操作、2cell設置・破壊、10cell連続採掘、script移動・inventory、入力反復。
3. `Cancel`：実行開始した移動入力を取消し、その後の静止と制御解放を確認。
4. `Hazard`：かがみ入力の稼働を確認してから外部fixture controllerが水を適用し、危険停止と解放を確認。

candidate・runner・fixtureは作業用artifactsの`20260928-v2-smoke-96fd6e1.zip`に保存済み。SHA-256は`3fc3943553461a60047979674931cd4fe17b364136e11ba4a6727f49e7bc3910`。14ファイルと個別hashのmanifestを含み、認証情報・通常profileは含まない。再開後に製品コードを修正した場合は、この古いJARで新実装を検証しない。

## 現在の課題

### 1. コード接続と実ゲーム成立の間に確認が残る

最も大きい未完了部分。試験準備はできたが、実ゲームで新しいv2経路が成立した証拠はない。とくに長押しmixinの実行時適用、server同期を伴う操作、取消時の入力・menu解放は単体テストだけでは確定できない。Hazardにはrunnerとは別のfixture controllerが必要であり、実行時の連携も確認する。

### 2. entity操作の成功判定にレビュー上の懸念

`a1a2a6a`のitem/entityと共通入力・終了処理を、固定したソース束でCodex CLI（gpt-5.6-sol）へ渡し、限定レビューを実施した。PR全体のレビューや実機検証ではない。

指摘は「`result_item`未指定時、native操作がPASSでも、後から無関係な拾得等で手持ちが変わると成功になり得る」というもの。`MinecraftV2EntityInteractDriver`の`clientConsumed || held.changed()`という経路はコード上確認できる。ただし「現契約ではclientConsumedのみが既定」というレビューの前提は設計文書と一致しない。**仕様上の因果関係の扱いと、誤成功として修正すべき条件を未整理。再現未確認・未修正として残す。** レビュー合格とは扱わない。

同様に、送信済みSWAPの応答前取消と遅延同期が次jobへ与える影響は追加確認候補。こちらは確定不具合ではなく、再現と既存停止契約の照合が先になる。

### 3. 完成目標に対して未実装の範囲がある

move中の障害物処理、menu操作、遠方entity接近、追加停止条件、複数cell設置、MOD収納の追加profile等は上表のとおり。すべてを「実機確認だけ残った」と扱わない。楕円・パス等の任意範囲形状は利用者が将来候補として許容したものなので、現在の直方体実装と区別する。

### 4. 検証環境と補助CLI

2026-09-28 22:21 JSTのローカル空き物理メモリは3.21 GiB（総量31.87 GiB）、空き仮想メモリ21.55 GiB。直前の空き物理約1.82 GiBから改善した。一時点の値であり、ゲーム試験を起動して余裕を確認した結果ではない。今回は停止の依頼に従い、新しいゲーム試験を開始していない。

通常のCLIレビューは`codex-code-mode-host.exe`欠落でファイル読取りに失敗した。失敗した試行をレビュー済みと数えず、ソース束を入力する方法で上記の限定レビューだけを完了した。CLI本体・global設定の変更はしていない。

ローカル資源不足、remote試験時間帯、CLI不具合はそれぞれ確認できた障害だが、過去の頻繁な停止すべての原因を一つに特定できたわけではない。単体検査、実装不足、実機待ちを混同せず、次回は一つのフェーズの成果と未完了を区切って報告する。

## 再開時の順序と運用条件

1. 利用者の再開指示後、goal・この文書・PRのHEAD・worktree差分・稼働processを確認する。新しい作業者の変更を上書きしない。
2. entity成功判定の懸念を限定した回帰例で評価する。修正する場合は関連コード・仕様・テストを揃え、candidate JARとmanifestを更新する。
3. 時間帯と資源を確認し、隔離環境を退避して4フェーズのsmokeを実施する。**12:00～23:00 JSTはこのPCのみ。23:00～12:00 JSTは許可済みaod-mimoidの隔離Dockerも可。** 通常profileや稼働中の他担当へ割り込まない。
4. action ID、導入JAR hash、readback、取消／危険停止、復旧後の全hashを実験記録へ残す。失敗は同一操作を無条件に再送せず、原因を限定して修正する。
5. 残る機能を上の表から選び、小さな単位で実装・確認する。未完了要件を断りなく落とさない。所持収納の実機試験はCoreのチェスト試験とは別に追加する。
6. 全体の差分レビューとPR本文・仕様・試験証拠を整合させて完成を判断する。利用者の追加指示なしでmerge・tag・Releaseへ進めない。

subagentsは使わない。必要な場合のみ範囲を絞ったCodex CLIを利用する。共有main、通常ゲームprofile、他担当worktree、private logを作業対象にしない。

旧v1関連では[Issue #26](https://github.com/Aodaruma/mcmcp/issues/26)がOPEN・実機確認待ち、PR #82もOPEN・Draftのまま。これらを完了・merge済みとは扱わない。難しいv1対応よりv2を優先するという利用者指示を維持する。

## 停止時に残す状態

この整理文書と入口リンクを専用branchへ保存し、PR #85にも現在地を反映して停止する。新しいゲーム試験・SSH試験・実装委託は開始しない。限定レビューのCLIは終了済み。新v2試験によるworld・MOD変更は今回行っていない。ゴールは「完了」ではなく、利用者指示による「一時停止」とする。
