# API v2 統合候補の Docker smoke（2026-11-15）

対象 source `ccf83a1282c253edb6b52183afb643bea49677d1`、version `0.2.0-rc.1`。issue #91 r4 の飛行修正は含まない候補であり、Release 全体の合格宣言ではない。隔離された既存検証 profile/world のcloneを使う決定的回帰試験で、fresh LLM評価とは区別する。

製品 SHA256 `54404b0c110ff970258735fdc1f90732809fc085d7cbd7a5272dc02d3e0cd4bb`、harness `fd7db8356f2e9acc13de02f01eb5aa6e3581663628dd7b8f7102d6b9489f1d1d`、admin `8632f271117d7f0d1d41b4b3bfc58615d0ddfda4d24cd81b73cc35238ce1b6cf`。公開 catalog と fixture/tools は対象commitを git archive で凍結した。

| ケース | 判定 | 共通assert数 | 公開request数 |
| --- | --- | ---: | ---: |
| Bags | PASS | 93 | 50 |
| Cake | PASS | 4 | 12 |
| Craft | PASS | 4 | 24 |
| LadderDown | PASS | 15 | 12 |
| LadderSide | FAIL | 33 | 28 |
| LadderUp | PASS | 16 | 13 |
| RecipeNegative | PASS | 35 | 20 |
| Surface | PASS | 62 | 43 |

採用runは計202公開request、共通assert 262件、job 35件（成功32・意図したlookup-only失敗2・対岸横断失敗1）。Productionの既存関数内の材料・数量・戻り容器assertは共通assert数へ二重加算していない。7ケース合格、LadderSide全体は不合格。

## 合格した範囲

- 公開17ツールの全定義がcatalogと一致し、旧 `agent_start_action` は公開されない。state既定3key/hotbar9、held_items明示時だけ追加。
- latestをhazardのみで取得し、cursorなし32recordsのframeを、statusが2つの後続frameを示した後にvisible_surfaceで再参照でき、ID/tickが一致した。statusはsource上 latestFrame() の読み取りであり、announceで対象を延命しない。既定compactはblock32+hazard32でcursorありとなったため、この実機runは既定compactのcursorなしケースではない。0/1/64のcursorなしcompact回帰は別の単体試験と親のnative試験を参照する。
- pagination cursor解放、lever ON/ON/OFF/OFF。2つのnoopはconfirmed_count=0/observed_count=1、yaw/pitch/selected hotbar不変。変更は各1回確認。
- 初期化済みバッグA/Bのstore4/7→take2/3→再開封で所持26/A2/B4、丸石32保存・数量確定・解放完了。
- 手元2×2の板材8/棒8、作業台3×3のかまど2をplan→execute。最終材料/完成品照合。cake2から空bucket6を回収しmilk0、menu/cursor解放。
- ALL_CRAFTABLE実同期はpartial、MOD bagのlookup-only参照はplan/executeともterminal failed（craft_not_confirmed / result.recipe_reference_expired）。材料不変。nether_star0件もabsence_proven=false。
- 安定床y201→y213の上昇、その到達状態から一切UI介入なくy213→y201の長下降。再上昇後、北側安定床(202,208,199)へ下降して成功。いずれもHP20、READY/jobなし。

## 対岸横断の失敗

北側床から南側床 `(202,208,201)` へ移るjob `7bd6d756-232e-40ae-a0fe-d7565094d701` は `route_replan_limit` で105tick/約5.23秒で失敗。path_replans=8、last_replan_reason=route_edge_changed。終端位置 `(202.58053958960556,208,199.5406361748848)` は北側床上。fall_damage_observed=0、HP20、control ready、running_action_id=nullを公開MCPで確認した。再送・追加fixture・解放中の介入は行わない。前段の上床→北側床は成功しており、旧 #83 の当該失敗と混同しない。

固定幾何: 下床 x200..204/y200/z198..202、壁x203/y201..214/z200、梯子x202/y201..214/z200 facing=west、上床(201,212,200)、北床(202,207,199)、南床(202,207,201)。Run-Cases.ps1 LadderSideの各目標は上床→北床→南床。max_ticks800/max_distance32/clear_path=false/auto_replan=true。

## 準備段階の記録

初期driverでtools/listへtool-call専用validatorを適用、既定compactのcursorなし前提、既知cursor未解放による4lease上限、inventory inspectへの非契約max_ticks、新品バッグの初期化/READY待機、negativeのtop-levelとresult failureの取り違えがあった。各失敗をinitial-*.jsonと全public JSONLへ保持した。数量mutationの応答不明・自動再送はない。採用run開始時にfresh state/既知ID terminalを確認した。入力schema検査をdispatch前へ追加し、各fixtureの終了を待ってからMCPを手動ONにした。

## 保全・復旧

元66ファイルをhash付き保全、45分排他leaseのfinallyでworld/MOD/config/optionsを復旧。タイトル画面でplayer=nullおよびrunning_action_id=nullを公開MCPで確認して保存完了を判定した。復旧66/66hash一致、world61ファイル一致、container停止、lease解放、元から稼働していたDocker Desktopは保持。通常profile/source worldは変更していない。

remote evidence.zip SHA256 `3b2bbb81edbe7065e6b496f7c0637c87a76ddbcfabe5ba0583299978bbc4a29a`。raw: `final-public.jsonl`、各`final-*-summary.json`、`restoration.json`、`shutdown.json`、`save-confirmation.json`。読みやすい別添は`side-floor-excerpt.json`。

## 配布文書レビュー

対象commitの生成ZIPのPUBLIC_API.mdは17toolと過去候補の履歴注記を持ち、相対Markdownリンク0。導入READMEのJAR名は全箇所0.2.0-rc.1に生成置換済み。実装に反する具体的な能力主張は見当たらなかった。ソース導入ガイドの旧JAR名2箇所は生成時に置換されるため、公開ZIPの機能・導入誤案内にはならない。
