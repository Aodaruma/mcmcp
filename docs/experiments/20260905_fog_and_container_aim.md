# 2026-09-05 fog欠測と額縁付近のcontainer照準

前回報告は[配布自動化と追加観測課題](20260905_release_and_observation_followups.md)を参照。

## 実装

- fogは同一level/camera/entity tickのrenderer sampleだけを使う。sample欠測を1ブロックの霧へ変換しない。local安全情報と音の更新は続け、visual取得・配送済みsurface再観測を待機する。
- 欠測期間も部分scanのcatch-up期限へ数える。長い描画停止からの復帰時は古いrayを混ぜず、最初の新鮮sampleで最大2,048 rayを再観測する。
- chest/barrelの面につき最初のrayと4隅方向の実rayを最大5件保持し、額縁等の可視entity boundsを避ける実rayを配送する。候補の座標・tick・revisionを新しく作らない。
- plannerは配送済みrayから一点を選び、その点までのcamera予算と後続姿勢を既存処理で計算する。全候補遮蔽ならTARGET_UNKNOWN。実行中は通常crosshairのexact block hitだけで開封し、照準到達後40 ticks待っても得られなければCONTAINER_AIM_OCCLUDEDで終了する。
- 額縁の回転・破壊、entityへのuse、遮蔽無視、任意の未観測照準点の生成は追加していない。

## ローカル検証

Java 25で`test harnessTest adminBridgeTest verifyHarnessIsolation build`成功。unit 1097件、harness 13件、admin bridge 21件、合計1131件。

新規の回帰確認は、5 FPS相当の間欠描画、1000tick欠測後の復帰、実際の短距離霧保持、world/camera/tick不一致、同面の額縁を避ける実ray配送、未観測ray非生成、候補選択とcamera/後続姿勢整合、全遮蔽拒否、crosshairのEntity/MISS/隣接block拒否と有限待機を含む。

## 実機引き継ぎ

コード担当はMinecraftやPrism Launcherを操作していない。固定コピーJARのパスとSHA-256を実ゲーム確認担当へ渡し、担当側でバックアップ・差し替え・再起動・再検証を行う。

再確認事項は、従来失敗した額縁付きチェストの非破壊inspect、通常チェストと連続操作、低FPS時のvisual更新、描画復帰時の更新再開、実際の短距離霧が緩和されないこと。ここに記したローカルテスト成功は実機成功の代用ではない。描画の完全停止中に新しいvisual情報を得られる仕様にはしていない。

## 実機担当からの追記

`d0ab256`の固定JARへ差し替え、同じサーバーへ再接続。担当の報告では102収納まで走査でき、その間は全visual情報が0になる現象は再発していない。一方、額縁付近の失敗は一部残った。`(164,67,-300)`はplayer約`(164.7003,64,-302.044)`から`container_aim_occluded`。z=-304の`(167,65)`と`(167,67)`はx165付近で失敗し、x169付近へ移ると成功した。低FPS・額縁対応の安定完了とはまだ判定していない。

コードの読み取りでは、可視entityの候補をLOS判定前に128体へ制限するため対象額縁が観測から落ちる可能性と、同面の有限ray候補・観測位置との差で隙間の照準点が残らない可能性がある。原因は未確定。既存の失敗時記録の`visible_entities_truncated`と対象額縁の収録有無を最初に確認する。現在の失敗codeはEntity・別block・MISSを区別しないため、その区別が必要なら次回の停止後検証で固定分類の診断を検討する。この追記時にゲーム操作・JAR交換・コード変更は行っていない。

## 294収納走査後の残存問題と追加修正

利用者から残存問題の修正依頼を受け、最終checkpointを読み取りで再確認した。担当報告では入口のsingle chestを除く294収納を完了し、428品目・114,454個、空収納113件。持続的な全visualゼロは再発せず、item移動も行っていない。checkpointには`container_aim_occluded`が31件あり、すべてinteraction数0で終了している。これは成功までに発生した失敗の記録数であり、未確認収納数ではない。

`(171,67,-313)`はplayerの立ち位置`(170,64,-311)`と`(171,64,-311)`で失敗し、後者の位置から別halfの`(170,67,-313)`を狙うと成功した。`visible_entities_truncated=true`の観測はあるが、個々の失敗時に対象額縁が収録されていたか、通常crosshairが何に当たったかは保存されていない。そのため今回の各修正を31件の実機根因として断定しない。

ソース上で確認・対処した経路は次のとおり。

- 遠方のentityが列挙順で先に候補枠を埋めると、目の前の額縁を選択判定に使えない。通常のblock interaction range内を先に収集し、残りで遠方を収集する。合計129候補・公開128件を維持し、NeoForgeが通常ループ後に追加するentity partにも追加前の上限確認を適用する。
- 観測rayはdoubleだが、実照準はfloat角度とVanillaの三角関数テーブルを経る。額縁の縁をわずかに避けたrayが実方向では額縁に入る数値例を回帰テストで再現した。選択判定のentity AABBに0.01 blockの余裕を持たせ、表面の外縁付近0.001 blockを避ける。4隅の候補は外縁から0.02 block内側を基準に順位を付けるが、実ray座標・観測時刻・revisionは変更しない。camera予算と後続姿勢は従来通り選んだ一点に基づく。
- 今後の切り分け用に、既存の`container_aim_occluded`に加えて`container_crosshair=entity / block_other / miss / unavailable / world_border`の固定分類を1件返す。公開schemaは変更せず、座標・entity ID・任意文字列は追加しない。
- 観測境界のレビューで、AABBだけがfog内にありLOS確認点がfog外の場合もrayを飛ばせた既存経路を確認した。確認点自体がfog外ならスキップし、近距離優先が可視範囲の拡大にならないよう閉じた。

ローカルでは`test harnessTest adminBridgeTest verifyHarnessIsolation build`成功。unit 1111件、harness 13件、admin bridge 21件、合計1145件で失敗・skipなし。新規14件には、遠方200体に埋もれる手前の額縁、累積上限とentity part、fog境界、Vanilla角度丸め、既知rayだけの選択、plannerのcamera整合、固定診断と任意データ非反射を含む。独立レビューも実施した。

新しいJARでの同じ失敗位置の再確認は実ゲーム担当へ引き継ぐ。ローカルテストだけでは倉庫での全例解消や安定完了を意味しないため、READMEの「額縁付きチェストの安定性」は未完了のままとする。
