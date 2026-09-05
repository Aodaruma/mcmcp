# 観測更新・チャット中の実行・手動長押しの修正

## 診断

- MCPの状態取得ではclient tickが21,845以降へ進んでも、観測frame `obs-525386e6433f0b26`のframe tickが9に留まった。visual revisionが変わるたびに全周scanを破棄するため、連続更新で完成frameの発行が停止し得ることをコードとunit testで確認した。
- world操作の複数adapterが画面の有無だけで停止を判定しており、pauseしないChatScreenも停止対象だった。
- 利用者は右クリックを離して押し直しても弓・ポーションの長押しができないと報告した。`tickAgentAction`がAction不在でも毎tick `releaseAgentControl`を呼び、その先で手動のitem useを解除する不具合を確認した。MCP操作の有無にかかわらず起き得る。

## 修正と検証

- scanが2周期以内に完成しない場合は、そのtickで許可された全2,048方向を再観測する。通常の分割scanは維持し、無効になったrayは使い回さない。64 / 256 / 512 rays設定の連続revision変更とresetを検証した。catch-up時の実機負荷は未計測。
- 非pauseのChatScreenを共通policyで許可した。その他の画面・overlay・pause・health・hazardと、操作中containerの正確な所有権検証は維持した。
- Action不在のtickから反復する入力解除を除去した。開始前の待機、成功・失敗・cancel後の各100 ticksでclientへ触れない回帰テストを追加した。terminalの解除と未完了解除のretryは維持した。
- `test harnessTest verifyHarnessIsolation build`は成功。unit test 1,065件、harness 13件、admin bridge 21件は失敗0件。catalog/schema検証と配布JAR分離検証も通過した。

## 実機確認の範囲

今回の修正・検証ではcomputer-useを使用していない。修正版JARを読み込んだMinecraftでのチャット中操作、観測更新、手動の弓・飲食は未確認であり、MCP-only受入合格とはしない。元の「くらふとぶ！-v01.2」instanceは変更していない。反映には修正版JARの配置とMinecraft再起動が必要。
