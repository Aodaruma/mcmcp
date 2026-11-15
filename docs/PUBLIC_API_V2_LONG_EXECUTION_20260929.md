# 公開API v2：長時間・多回数実行（2026-09-29）

**短い隔離入力試験と通常profileへの導入・再起動後schemaを確認済み。24時間連続実測は未実施。** 右クリックだけに限定せず、利用者指定により移動・破壊・設置・操作・所持品・入力・scriptの上限を見直した。`f7871718…`版の[導入・復旧記録](experiments/artifacts/20260929-v2-long-live/deployment.json)を保存した。以前の`03c7943f…`版には以下の拡張はない。通常環境で報告された収納inspectの終了待ちは[別記録](experiments/20260929_v2_storage_cleanup.md)で修正・再確認を追跡する。

## 引数と上限

| 対象 | 既定値 | 明示できる上限・扱い |
|---|---|---|
| move / break / place / block・entity・menu操作 / 収納 | `max_ticks:1200` | `max_ticks:1728000`。item使用・swap・dropは従来の短い既定値を維持 |
| item使用 | `hold_ticks:40`、`max_ticks:max(200,hold_ticks+80)` | holdは1,727,999、maxは1,728,000。既定maxの計算もこの上限で打ち切る。maxはholdより大きくする |
| 移動・接近の総距離 | moveは256、block・entity・収納は従来値（通常64） | `max_distance:4096`。方向移動の`distance`も最大4,096 |
| click | `count:1,hold_ticks:1,gap_ticks:4` | 個別は最大1,728,000、列全体も1,728,000 tick以内。複数clickのgapは1以上 |
| inputのsteps | holdは必須、gap 0、repeat 1 | 最大64 step、合計1,728,000 tick。各hold/gap/repeatも有界 |
| inputの秒数指定 | `duration_seconds`は必須 | 1..86,400秒。`inputs`で全9種類の論理入力を指定。stepsと併用不可 |
| inputの移動距離 | `max_distance:48` | 0..4,096。材料guardを付けた静止useは開始位置から0.25 block以内 |
| script | duration 24,000 tick、calls 1,000、iterations 10,000、work 100,000 | 明示最大は順に1,728,000、1,728,000、10,000,000、100,000,000 |

値の単位は変更していない。`max_ticks`はclient tick側の上限で、**それとは別に実時間の期限**を持つ。tick予算からの期限は`max(120秒,max_ticks/20秒)`、最大24時間。click/input列では合計tickを使う。scriptは`max_duration_ticks/20秒`、秒指定inputは指定秒数そのものが期限。期限は受付時に一度設定し、配送確認後に入力を始める。MCPの開始クライアント終了後も、ゲームが稼働していれば同じjobで続行する。

scriptの子jobは自身と親の期限の早い方を引き継ぐ。次の操作へ移るたびに総期限を足し直さない。短い既定の子操作まで自動で24時間へ引き上げるものではなく、子の予算も必要な場合に明示する。

block範囲4,096 cell、入力列64 step、menuクリック16件、短い確認待ち、未知地形・局所危険の判定、scriptのソース長・構文深さなどは維持する。時間を増やしても視界外へ無条件に進まず、未確認の操作を自動再送しない。大きな建設計画は既存のcheckpoint runnerや有限scriptで分割する。

## 例

`agent_input_sequence`で左クリックを最長10分保持:

```json
{"inputs":["attack"],"duration_seconds":600}
```

粉の設置と、同じ選択slotへの補充を待ちながら最長24時間use:

```json
{"inputs":["use"],"duration_seconds":86400,"item":"minecraft:black_concrete_powder","refill_wait_seconds":30}
```

`item`は秒指定のuse単独に追加できる材料guard。選択slot・item ID・開始時の位置と向きを確認する。stack個数やobject identity、設置先blockのstateは固定しない。空になったらuseを解放し、同じitemが同じslotへ戻るまで最大30秒（明示1..300秒）待つ。補充期限後にitemが届いても自動再開しない。別item、slot変更、空でないoffhand、0.25 blockを超える位置変化、2度を超える視線変化では停止する。inventory移送や補充MOD操作自体を追加したものではない。移動を含む他の入力にこの静止guardを一律適用しない。

`agent_run_script`で1,500回の操作と最大1時間を明示:

```json
{"source":"repeat(1500) { inventory(operation=\"inspect\"); }","max_calls":1500,"max_iterations":1500,"max_work":100000,"max_duration_ticks":72000}
```

これは上限指定の例であり、所持品取得を1時間かけて行う意味ではない。処理が終われば早く終了する。MCPの短いpollやLLMの定期起床は実行の条件ではない。

## 時間と停止

`agent_get_action`の`progress`へ`elapsed_seconds`、`remaining_seconds`、`max_duration_seconds`を追加した。`stop_reason`は通常完了の`completed`、秒指定inputの期限到達`duration_elapsed`、失敗・取消理由を返す。実行中はnull。inputの`result.phase`は`holding`または`waiting_for_item`。terminalでも最後のphaseが残る場合があるため、継続中かは`state`・`stop_reason`・controlで判定する。受付の`queued`は実行成功ではない。

秒指定inputは指定期限または入力列完了で正常終了し、それ以外のjobが作業未完で期限に達した場合は`duration_limit`（scriptは`script_timeout`）で停止する。期限付きinputの正常終了は、粉の設置数やコンクリート生成数を保証しない。キー解放で発生する通常のゲーム効果も巻き戻さない。

期限には単調な実時間を使い、ゲームpauseを除外する入力leaseの時計とは分離した。tick停止後の再開でも、期限切れの入力を新たに発行する前に停止する。入力消費側でも期限を検査するため、古い所有状態やlease再発行だけでは入力を復活できない。Esc（ゲームが受け取ったもの）、OFF、取消、画面・world境界、危険、runtime異常での停止と、解放確認後のterminal公開を維持する。解放失敗時は終了処理を再試行する。

ゲーム切断・JVM再起動後の自動再接続・再開はしない。OSスリープ中は作業できない。ゲームpause中は入力を止め、総期限を延長しない。非アクティブ化や省電力で画面遷移・lease失効等の停止条件に達した場合は停止する。別アプリへOS入力は送らない。隔離singleplayerの`pauseOnLostFocus:false`では背景中の20秒継続を確認したが、通常profileのMOD構成や長時間の背景運転は別途確認が必要。

## 自動試験と残る確認

旧上限を超える1,500 tickの入力・1,500回のscript、121秒後に確認できる移動とblock施工、引数と公開schemaの拡張を回帰試験へ追加。時刻注入で24時間境界、期限後の再発行防止、親子の期限共有、入力解放待ち、取消との競合、補充・補充期限切れ・異なるitem・姿勢変化を確認する。

検査結果・対象JARは[変更検証記録](experiments/20260929_v2_long_execution.md)を参照。短い隔離実機で粉設置・補充・取消・期限、CLI終了後と非アクティブ時の継続、旧上限超えの1,500tick（約75秒）を確認した。移動・施工・scriptを最大予算で動かす実測、通常profile導入後の運用と24時間実運転は残る。短い試験を24時間の「実測済み」とは記録しない。
