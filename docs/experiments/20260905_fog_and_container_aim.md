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
