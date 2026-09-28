# 公開API v2：ローカルPCでの実機smoke

Issue #84 / Draft PR #85。2026-09-28、利用者の再開指示に従って、このWindows PCで実施した。決定的な機能試験であり、fresh LLMの自律評価やv2全機能の合格認定ではない。

## 対象と環境

- 製品ソース: `e79f59888e2aece6d426437e0d4c545f8e27384a`。entity判定修正`28d4ae2`に、投棄と破壊照準の修正を加えたもの。
- Minecraft 26.2 / NeoForge 26.2.0.59 / Zulu Java 25。新規の独立Prism profile `MCMCP-V2-Local-20260928`、新規superflat world、survival、peaceful。通常profileは試験へ流用していない。
- 製品JAR SHA-256: `6acde6beacbea21c812c390568332ccf5bcd8a21005204085ff3eda633dbdde5`。
- fixture-admin JAR SHA-256: `9b4aeb58e57c6ac3353fe8d553f55971cf11b50766506b4e9c2cd7dc6a584230`。製品とは別のtoken・endpointでfixtureだけを準備した。
- base fixture SHA-256: `75f2f8a5d210cec181729a9674220b9d79e65190d8af947fd33f69a84b061791`。water-stop: `b00924a8cba074b344d6c96677ea73a83a79df9c85d521ef20bf6bf5de94b80b`。
- 最終4フェーズのMCP session: `f4b2d604-2959-4982-b37f-8e7c6f96df69`。

T0前に対象chunkをforceloadし、base fixtureをadminで適用。`prepare-ui.mcfunction`をゲームUI経由で一度実行し、cow・道具・資材を用意した。準備中のslime被害を避けるためpeacefulにし、体力20と足場・開始位置・持ち物を確認した。保存終了後のsave全体52ファイルを再試験用baselineとし、失敗後は全体を復元してJARを交換した。ゲーム操作は公開MCP runnerで実施し、Hazardの外部controllerだけが稼働確認後に水を適用した。

実機導入JAR、保存candidate、製品commitで再生成したJARのhashは一致した。[対象metadata](artifacts/20260928-v2-local/candidate.json)と[証拠manifest](artifacts/20260928-v2-local/manifest.json)を参照。認証情報、profile本体、ゲームlogは公開添付に含めない。

## 実機で見つけた不具合と修正

### 数量投棄の確認待ちtimeout

旧経路は`LocalPlayer.drop`で所持数を予測更新した後、server slot payloadを待っていた。Vanillaの`ServerPlayer.drop`は対応slotをremote状態へ反映して通常の差分送信を抑えるため、1個が投棄されても確認待ちが終わらなかった。

[Core初回](artifacts/20260928-v2-local/core-01.json)のaction `da897db8-1684-46d6-ba56-223aa654b107`は要求2個に対し`confirmed_count:0 / unconfirmed_count:1`、`drop_ack_timeout`で停止。readbackは16→15で、未確認操作を再送しなかった。

所持品menuの通常`THROW`入力を、client側で数量を予測更新せず送るよう変更した。送信前のmenu・cursor・slot・session確認、送信後の新しいserver payloadとローカル数量の一致、未確認時の再送禁止は維持する。最終試験では2個確定・未確認0、16→14を確認した。全stack投棄、main inventoryからのstaging、応答遅延中の取消は今回の実機範囲外。

### 接近後の照準がblock端を外れる

投棄修正後の[Core 2回目](artifacts/20260928-v2-local/core-02.json)では、2cellの設置まで成功したが、破壊action `89b767e9-b039-485a-a51a-0c3c8519744e`は1cell確定後に`block_not_confirmed`で停止した。残るcellの端へ照準が外れた状態を画面・位置のreadbackで確認した。

観測した表面への照準後、実crosshairが一致しない場合に限り、同じ観測済みcellの中心へ一度だけ向き直す。攻撃前の実crosshair、live source、reach、安全条件とserverのair確認は維持する。最終試験では2cell破壊と10cellの連続採掘が成功した。既存Core runnerが両不具合の実機回帰試験になる。

### entityのPASSと無関係な拾得

再開メモの懸念を修正した。`result_item`省略時はclientの操作受付を成功条件とし、PASS後の無関係な手持ち変化だけでは成功にしない。明示`result_item`がある場合は、新しいserver payloadと現在の手持ちが条件に一致することを必須とする。catalog・設計・ガイドを同期し、単体回帰2件を追加した。無関係な拾得の再現は単体試験、cow操作は下記実機試験で確認した。

## 最終candidateの実機結果

| フェーズ | 結果とreadback | 証拠 |
|---|---|---|
| ItemEntity | cowからmilk取得。`server_held_item / effect_confirmed:true`。34tickの飲用後、bucketへ戻り`server_processed:true` | [結果JSON](artifacts/20260928-v2-local/item-entity-03.json) |
| Core | compact観測、所持品inspect、main→hotbar SWAP、2個投棄、チェストinspect/take3/store2、gateの通常使用・raw click、2cell設置・破壊、10cell採掘、script移動・inspect、入力反復が成功 | [結果JSON](artifacts/20260928-v2-local/core-03.json) |
| Cancel | 24tick実行後に`cancelled`。500ms間隔の2回の位置取得が一致し、terminal後は`ready / running_action_id:null` | [結果JSON](artifacts/20260928-v2-local/cancel-03.json) |
| Hazard | controllerが同じactionの31tick実行と座標を確認して水を適用。35tickで`failed / safety_interrupted`、terminal後は`ready / running_action_id:null` | [結果JSON](artifacts/20260928-v2-local/hazard-04.json)、[controller](artifacts/20260928-v2-local/hazard-controller.json) |

主要action ID:

- milk取得: `6975308e-3b56-4f59-ab05-54547ae3f98e`、飲用: `0d47870e-147f-4c6d-a103-58828ffb9494`。
- 2個投棄: `8a52ee00-ca95-45c5-8ad3-3ae9aea371ec`。チェストはsnow32→29→31、所持品は16→19→17。
- 2cell設置: `3e0c2baa-14ff-4a17-be54-5863507f659d`、2cell破壊: `7854b91f-e551-498f-9628-c319a2346526`。
- 10cell採掘: `5b2c7e23-e1a1-4440-9d76-26232442cdef`、766tick、確定10cell。範囲はx202～206、y201～202、z200。
- script: `1d066492-5cb0-4cae-b784-6129b33ff098`、2反復・3命令完了。到着位置と体力維持をrunnerで照合。
- Cancel: `110494a3-6068-4816-975c-c07bdd2f0ecf`。Hazard: `5e1d3d1c-31ea-463a-ae81-15ade51cf49b`。

Hazard初回はCancelによる移動後の座標が開始cell外で、mutation前に[拒否](artifacts/20260928-v2-local/hazard-03.json)された。公開`agent_move`の[到着確認](artifacts/20260928-v2-local/hazard-reposition.json)後、新しい試験directoryで実施した。進行中の失敗jobを再送したものではない。

**試験後の事象:** Hazardは23:24:49 JSTに停止判定を通過したが、23:24:58.773にプレイヤーの落下死亡を確認した。水適用後の高所fixtureであり、水流による移動が原因と考えられるが、停止後の全tickの動きを採取して原因を確定したわけではない。危険停止はworldや水流を巻き戻さず、この試験は停止後の生存保証を認定しない。終了までの遅延を避ける手順を追記した。自動終了や落下防止を備えたfixtureの改善は次回の課題とする。

## ソース検査と復旧

`gradlew test harnessTest adminBridgeTest verifyHarnessIsolation build`成功。Java 1,577件、harness 13件、admin bridge 28件、失敗・error・skip 0。最終の`tools/check-source.ps1 -SkipJava`も成功し、MCP transport 15件、施工台帳・復旧・capability gate・smoke runner 7件等を確認した。これらのmockは上表の実機と区別する。変更したrunnerのconsole出力も実機で正しく表示されることを確認した。

ゲームの保存終了とMinecraft／Prismの停止を確認し、試験後profileをローカル証拠へ退避した。専用profile全体をworld作成前のbaselineへ復元し、**12ファイルの個数・全SHA-256一致**を確認した。[復旧receipt](artifacts/20260928-v2-local/restoration.json)に全相対pathとhashを記録している。新規world・datapack・生成設定・tokenも稼働profileに残らない。通常profileのsave・MOD・設定は操作していない。検証world・候補JAR・再試験baselineはローカルartifactsに保持した。

## 残る範囲

v2全体は引き続き開発中。所持収納のv2実機試験はチェストの成功で代用しない。menu操作、遠方entity接近、moveの障害物処理、追加停止条件、特殊な複数cell設置、他MODの収納profile、遅延応答中の取消、PR全体の差分レビューが残る。itemの使用中取消、Esc／UI OFF／world変更の各実機経路も今回の4フェーズに含めていない。main統合・tag・Releaseの判断は行わない。
