# Issue #90: このPCでの弓cleanup受入（2026-11-15）

## 対象と隔離

対象は `d59dd41a6108e4f15c2f0ecbf49f77b32bad3b06`（PR #92）。製品コードは `a44089b` と同一で、差分は実機故障注入用harnessのみ。製品JAR SHA-256は `8869a94a940e83ee8caadc81702de3df424898b8cb94f49ca3e67db4d736b802`、harnessは `5dda44142ff6aed6c7643d5b258a0cf9ea4c33ea4ed610535ca4ab2193fb26b9`。

Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25、このPCのPrism検証profileのコピーで実施した。元profileを保全し、作業コピーに新しい平坦worldを作成。通常profile・通常world・常用MCP登録は変更していない。Sophisticated Backpacks/Coreは検証コピーだけに追加した。元profile復旧は同じ保守作業の最終確認で照合する。

固定fixtureはprivate integrated server・単独owner・専用JVM flagを必要とし、bow送信callbackの直前で一度だけ例外を注入する。製品JARにfixture・故障注入コードは含まれない。準備後からterminalまでは公開17ツールだけで操作し、画面・private oracleを判断材料にしない。固定reportは準備前またはterminal後だけ読む。この試験は有限の機能受入であり、LLMの自律成功率評価ではない。

## 結果

| ケース | 公開Action終端 | fault到達 | 終端MCP | 入力・local使用 | fault後の矢/弓耐久 |
| --- | --- | --- | --- | --- | --- |
| 正常な反撃・装備復帰・移動再開 | succeeded | 注入なし | READY | 解放済み | 通常の3回発射を確認 |
| cancel_first_runtime | failed / recovery_interrupted | cancel_first | OFF | 解放済み | 64→64 / 0→0 |
| cancel_second_runtime | failed / recovery_interrupted | cancel_second | OFF | 解放済み | 64→64 / 0→0 |
| fire_runtime | failed / recovery_exception | fire | OFF | 解放済み | 64→64 / 0→0 |
| cancel_first_linkage | failed / recovery_interrupted | cancel_first | OFF | 解放済み | 64→64 / 0→0 |
| cancel_second_linkage | failed / recovery_interrupted | cancel_second | OFF | 解放済み | 64→64 / 0→0 |
| fire_linkage | failed / recovery_exception | fire | OFF | 解放済み | 64→64 / 0→0 |

全例で `input_owner_none` / `input_channels_none` はtrue、`velocity_tracked` / `local_using_item` はfalse、cursorは空、実行中Actionなし、world/session同一。通常例はHP20、diamond chestplateへの切替確認、危険消失後100tick安定して元の移動を再開した。故障例は意図どおり安全に失敗してOFFとなり、失敗した発射の再送を行わなかった。

取消例の `agent_cancel_action` は注入した例外に対して `INTERNAL_ERROR` を返す。requestCancelは先に記録され、保留cleanupとOFF lockを経由し、同じ既知IDの `agent_get_action` で終端を確認した。開始操作を再送していない。最初のdriverはこのエラーで終了したため、その例だけ既知IDの照会とterminal後reportを別ファイルで回収した。後続driverは取消応答失敗を保存して同じIDの照会を続けるよう修正した。

準備時の最初のfixture要求は新worldのcommands無効で拒否された。worldを保存・終了後、この使い捨てworldだけでfixture準備を許可し、新しいnonceで再準備した。拒否された準備を製品の実機不合格・合格へ数えていない。

## 証拠と限界

非公開のローカル受入artifactに、公開MCP応答、固定準備/終端report、JAR照合とprofile保全manifestを保存した。token・helper出力・private logは公開しない。Java全1,942件（通常1,898 / harness16 / admin28）と隔離build、PR #92のGitHub CIは成功済み。

確認したのは送信callbackの例外経路と、その後の実ゲームの入力解放である。物理ネットワーク断、任意server MOD、全てのJava Errorを実機で再現したものではない。local stop自体の失敗はソース回帰試験で確認し、この実機では注入していない。Issue #91の狭い岸への着地は別の受入を必要とする。
