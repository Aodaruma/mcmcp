# R22bの施工期限停止：記録とコードによる切り分け

2026-10-13 09:03 JST。通常ゲームへの照会・入力・接続・再起動、Docker実機試験、製品コードの変更は行わず、施工担当が保存した2ファイルとR22bのコードを確認した。受領済みR23候補と凍結成果物は変更しない。

## 記録

親mcmcpディレクトリの `_PLAYING/map-art-MFwtpM21wWc-3x3/construction/` 内にある以下を読んだ。

- `recovery-045560-offline-20261013.json`
- `blueprint-045039-046575.result.json`

親 `baed907a-4931-4143-ab52-23776df429b8` は `script_command_failed`、1,274.430秒／上限6,264秒、521設置確定、384命令完了、`next_index=45560`。子 `3c783acf-6d17-4e54-a131-1119f8964463` は `construction_deadline`、15.792秒、316／600 ticks、確定設置0、外側のwall上限120秒。`last_command.truncated=false` で子result自体がない。

08:58のstate/statusはworld/sessionなし・MCP OFF・Actionなし。08:19ごろの停止とその後の切断について、時刻や原因を結び付ける記録は今回の2ファイルにない。

## 15.8秒で停止できる理由

期限は入れ子で、外側の残り時間が十分でも、内側の上限で停止できる。

| 層 | R22bの上限 |
| --- | --- |
| 親の施工script | 今回は6,264秒 |
| 子place Action | 今回は600 ticks、wall上限120秒 |
| 1マスの床延長 | 開始から400 client ticks |
| 床延長内の静止施工 | 開始から300 client ticks、床延長の残り期限との短い方 |

`KnownConstructionAttempt.TICKS_PER_ENTRY=300` で、`tick()` は `clientTick >= deadlineTick` のとき `construction_deadline` を返す。床延長のAIM完了時に、`min(床延長期限, 現在tick+300)` でこの施工処理を開始する。

300 ticksは20 ticks/秒なら15秒であり、準備を含む子全体316 ticks／15.792秒はこの設計と整合する。ただし、途中のphase標本がないため「準備が正確に16 ticks」「どの準備／確認で待ったか」までは断定しない。`max_ticks` や親wall上限だけを増やしても、固定300 ticksの期限は伸びない。

参照：`KnownConstructionAttempt.java:27,91`、`MinecraftFloorExtensionAttempt.java:38,162`、`V2BlockJobExecution.java:92`、`AgentJobLimits.java:17`。これらのうち変更済み対象とplace driver/runtimeは、R22の凍結source manifestと現在ファイルのhash一致も確認した。

## 子resultがない理由と切断との整合

`V2BlockJobExecution` は確定配置または既存一致を記録した際に `publishCells()` でresultを作る。最初の1マスがそれ以前に失敗すると空resultのまま終了できる。`McmcpRuntime.v2JobPayload()` は空resultをJSONに含めず、`ScriptChildDiagnostic` は渡されたpayloadを保存する。この場合、`truncated=false` でも詳細のresultは存在しない。

床延長が受け取る内部effectsはplace driverでdrainされるが、未確定effect・内部phaseを公開子resultに保存していない。従って今回の `placed0` は**確定できた設置が0**であり、設置を送信していない証拠ではない。準備待ちと送信後の確認待ちは、この記録だけでは区別できない。これは診断情報の不足として残す。

logout時は `McmcpRuntime.onLoggingOut()` が `disconnect` 理由の停止とsession無効化・MCP lockを行う。既にterminalとなった子は `stop()` がそのまま返すため、後から切断しても `construction_deadline` の履歴は保持される。通信応答が止まったままclient tickが進んで期限になり、その後logoutした場合とも整合する。先に施工の別要因で期限停止し、後から切断した場合とも整合するため、切断原因・因果は未確定である。

## 既存仕様での再開条件

1. 利用者が再接続・MCP ONにした後、施工担当が新session、現在位置、体力、接地・安全支持を新規観測する。履歴上の位置を現在安全の証明にしない。
2. 最終確定 `(-585,79,309)` と最初の未確定 `(-586,79,309)` を観測する。後者が設計どおりの黒なら既存一致として扱える。空気なら現在の安全支持・材料・reachを確認して新Actionで設置する。別blockまたは未知なら自動再送せず切り分ける。
3. 設計図source、dimension、origin、plan hashを照合して `start_index=45560` から新たにplan/executeする。範囲を変えても同じ設計のplan hashは変わらない。最初は短い区間で結果を照合する。
4. `next_index` は完了済み命令の範囲までを示す。失敗した命令の範囲は自動で加算しない。既存一致は `observed_count` として照合され、`confirmed_count + observed_count` が命令内の全件と一致したときだけ設計図の進捗が進む。過去521設置と再開後の既存一致を二重加算しない。

根拠：`BlueprintPlan.progress()`／`confirmedStep()`、place driverの既存一致判定と `V2BlockJobExecution.recordObservedCell()`。失敗Actionそのものを再開／再送する手順ではない。

現時点では内部期限の仕様と復帰手順を説明でき、直ちに期限拡張やR23への追加変更を必要とする証拠はない。再発時の特定には、未確定送信・最後の施工phase／待機理由を公開結果へ残す別候補が有用だが、今回実装・実機検証はしていない。通常ゲームの現在状態と切断原因も未確認である。
