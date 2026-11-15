# 公開API v2：通常移動の再計画・停止診断の検討メモ（2026-09-29）

> 2026-09-30追記: 以下は着手前の切り分け記録です。後続実装では既定の再計画、かがみ移動、照準の再取得、`agent_look`を追加しました。現在の仕様は[設計](PUBLIC_API_V2.md)、検証結果は[移動・設備検証記録](experiments/20260930_v2_navigation_docker.md)を参照してください。

Issue #84 / Draft PR #85の試用担当から受けた停止理由の報告を、設計検討事項として記録する。状態は**未実装・原因調査未完了**。この記録ではコード・JARの変更やゲーム操作を行っていない。

静的確認の基準は`63d6cf3c558a5e506df00888c615bd953d70052d`。通常環境へ導入済みの製品JARは`227ae54631d1b09f3fbd75f617bfcd03c1eaf348cef48f4b0e6416ea516a597c`であり、本メモはその修正版の完成を示すものではない。

## 報告と確認範囲

試用担当から、通常移動の`route_replan_required`、上階へ向かう移動の`safety_interrupted`、画面上で見える収納に対する`container_target_not_observed`が報告された。現場の操作記録は非公開資料で保持し、座標・所持品・サーバー情報は本メモに転載しない。本記録では再現試験を追加していない。

以下の分岐はソースで確認した。**経路再計画が必要になった最初の原因、はしごを登れなかった原因、安全判定のどの条件が失敗したかは未確定**である。

| 対象 | 確認できた実装 | 説明上の限界 |
|---|---|---|
| 通常移動の再計画 | `CoordinateMoveJobExecution`の`REPLAN_REQUIRED`で、`routeWasGoal && obstacles == null`なら`route_replan_required`として終了する。この分岐では`step.reason()`を結果に保持しない | すべての経路再計画が即終了するわけではない。部分経路とゴールまでの経路を区別する必要がある |
| 障害物処理付きの再計画 | 同分岐のゴール経路で`obstacles != null`の場合だけ、`last_replan_reason`と`path_replans`を記録し、8回を超えると`route_replan_limit`となる。再計画前には経路証拠の更新待ちがある | `clear_path`の破壊許可と、移動だけで回復できる場合の再計画が結び付いている。破壊許可を停止回避のために有効化することは解決策としない |
| 移動の安全停止 | `tickV2Move`はworld・player同一性、制御mode/epoch、pause、endpoint、累積距離、体力などの判定と`v2InputWorldSafe`をまとめ、失敗時に`safety_interrupted`を返す。後者には画面、局所安全、水・溶岩、ロード範囲などの条件もある | 当該停止結果には失敗した個別条件を保持しない。危険箇所やはしご固有の問題と断定できない |
| 収納の対象確認 | `MinecraftV2ContainerDriver.prepare`はロード、操作可能距離、観測frame、照準証拠、観測と現在blockの一致などを確認する。個別failureなしの準備未成立と接近不可・証拠待ちが続くと`container_target_not_observed`になり得る | 「画面上で見えていない」とは断定できない。可視性とAPIの操作準備成立は別の条件 |

確認箇所：

- [CoordinateMoveJobExecution.java](../src/main/java/dev/aod/mcmcp/agent/navigation/CoordinateMoveJobExecution.java)：`REPLAN_REQUIRED`、203行付近。
- [McmcpRuntime.java](../src/main/java/dev/aod/mcmcp/runtime/McmcpRuntime.java)：`clearPathWith`の接続、2115行付近。`tickV2Move`、3346行付近。`v2InputWorldSafe`、3575行付近。
- [MinecraftV2ContainerDriver.java](../src/main/java/dev/aod/mcmcp/runtime/MinecraftV2ContainerDriver.java)：準備待ちの終了分岐、134行付近。`prepare`、159行付近。

行番号は上記基準commitの目安。後続変更時はメソッド名で確認する。

## 設計検討候補

1. **診断情報を先に保持する。** 経路再計画の要求理由、安全停止の個別条件、収納準備が成立しなかった条件を固定の理由値で結果へ残す。終了・入力解放後にも参照できるようにする。公開field名や複数条件の優先順位は未決定であり、既存の総括理由との互換性も検討する。
2. **通常移動の有界な再計画を破壊許可から独立させる。** `clear_path:false`でも、同じworld・有効な制御・新しい局所観測の下で移動経路を組み直せるか検討する。再計画回数・無進捗・同じ経路の反復を制限し、元のtick・実時間・距離予算を引き継ぐ。破壊・設置の許可は拡張しない。
3. **はしご・動く設備付近の問題は別途切り分ける。** まず詳細理由を取得してから、経路計画、経路実行、局所安全、対象への操作距離・照準のどこで成立しなくなったか調べる。通常再計画を追加すれば登れるとは現段階で約束しない。

再計画候補でも、古い観測の再利用、未確認操作の無条件再送、取消・Esc・OFF・world変更・危険判定の解除は行わない設計とする。入力解放とterminal確定の順序も維持する。

## 実装着手時の確認候補

- `clear_path:false`で、新しい安全な経路が観測された場合だけ再計画し、破壊・設置が発生しないこと。
- 証拠が更新されない場合、経路が反復する場合、回数・元の予算が尽きる場合に有限で停止すること。
- 再計画待ち中も取消・制御解除・world変更に応答し、解放後に詳細理由を保持すること。
- 安全停止の代表条件と、収納の距離不足・照準証拠不足等を結果から区別できること。
- ゴール経路と部分経路、既存の`clear_path:true`の挙動を区別して回帰確認すること。
- 診断を追加した後、隔離fixtureではしご・通路状態変化を切り分けること。現場ゲームの追加操作・導入は担当と調整すること。

これらは今後の設計・試験候補であり、実施済みの試験結果ではない。
