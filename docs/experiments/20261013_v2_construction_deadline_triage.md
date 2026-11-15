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

## R30での再発と部分結果の確認 2026-10-15

施工担当から、worker44164が同じ `construction_deadline` で停止したとの報告を受けた。保存済みの `blueprint-109860-111396.result.json` を共有読取りで確認した。親 `f7fc6158-6209-4a9d-85bf-46adfabc3883` は `script_command_failed`、924.992秒／上限6,264秒。完了済み命令の373床に加えて、失敗した子 `fa84aee5-ae21-42a9-874e-55bc78a68390` の確定5床が残り、親の `placed_blocks=378` と一致する。

子は506／600 ticks、28.402秒／wall上限120秒で停止した。確定5床は `(-822..-826,79,475)` のairから黒コンクリートへの変更で、`observed_count=0`、結果の打切りはない。親の `next_index=110233` は完了済み命令までを指し、失敗した子の5床は含まない。この5床を新規観測と在庫で別途照合すると、次の未確定entryは110238になる。

今回も親／子Action全体の予算は残っており、停止理由は内側の静止施工の期限である。子の28.402秒には先行5床の施工も含まれるため、最後の1床を28秒待っていたとは解釈しない。内側の期限は各床の準備・照準・設置確認・解放などに共通して適用され、保存結果には最後のphaseや待機理由がない。R30で追加した診断は `floor_extension_input_lease_expired` 用で、この `construction_deadline` には付かない。未確定の次セルへ操作を送ったかどうかも、確定5床の記録だけでは判別できない。

担当の `reconcile-110238-verified-20261015.json` は23:01 JSTに373＋5床を照合し、床109,050・松明1,188を記帳したことを示す。再接続後の保存監査では新session・READY・Actionなし・HP/食料20・在庫同期一致を確認できる。続く未施工6床は `cc65ddef-765d-4089-b041-53a56efab5fb` で13.445703秒、全6床成功。23:07 JSTの `reconnected-284-verified-20261015.json` は284行末の床109,056・松明1,188、設計一致・重複なし・在庫実減一致を示す。291行末までの有限worker再開は担当の開始報告であり、その完了を意味しない。

施工workerは `agent_build(wait_timeout_ms=0)` で開始し、`agent_get_action` で結果を取得する。今回、失敗と部分確定結果を保存できたことは確認できるが、通常の照会は `isError:false` でActionのfailedを返す経路である。保存ファイルにも生のMCP envelopeはないため、[同期Actionエラーを保持するクライアント修正](20261015_v2_client_action_results.md)の `isError:true` 分岐を実環境で検証したとは扱わない。

後の切断との因果や待機の根本原因は未特定。通常ゲーム・worker・台帳・JARをこの調査で操作／変更していない。期限延長や自動再送は行わず、今後の原因特定には最後の施工phase、待機理由、送信済み／未確定状態を保持する診断が必要になる。
