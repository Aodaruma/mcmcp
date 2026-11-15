# v2の床延長とスクリプト施工

この文書は2026-10-11の設計図R2の仕様。18:22 JSTに通常「くらふとぶ！-v01.2」へ導入し、実JVMのJARと公開17ツール一致を確認した。実機結果は[状態記録](PUBLIC_API_V2_STATUS_20260928.md)を参照する。

専用`agent_build`も同じJARへ含めた。[設計図tool仕様](PUBLIC_API_V2_BLUEPRINT_TOOL.md)を参照。

## 床の連続延長

`agent_place_block`の`advance:true`は、届く支持面までの移動に加え、通常の完全立方体の端へしゃがんで寄り、側面を実際に狙って設置し、server確認後に新しい床へ進める。`advance:false`が既定で、位置を動かさない。

```json
{"x":311,"y":105,"z":310,"dx":7,"block":"minecraft:black_concrete",
 "advance":true,"max_blocks":8,"max_ticks":3200,"max_distance":32}
```

これはX方向8床の例。`dx:-7`なら逆方向。開始時には先頭床に隣接する既存の安全な支持床が必要。氷・足場・半block・障害物のある通路へ、この床延長手順を一律適用しない。未ロードの設計図全体を観測済みと扱わず、現場で1床ずつ照合する。

支持床上の松明・レール・redstone dustなどは無衝突かつ非危険なら通行する。火・水・感圧板・tripwire等は除外する。バッグの自動補充には、server設置確認と、新たに届いた当該手持ち枠のpayload一致を使う。実機確認状況は[設計図・補充検証](experiments/20261011_v2_blueprint_tool.md)を参照。

R22bは、端で減速する間に照準合わせを開始して待ち時間を少し短縮する。実際の設置前には従来の静止・支持・照準確認が必要で、予算を下げて早期終了させる変更ではない。[同条件の速度比較](experiments/20261012_v2_build_speed.md)を参照。2026-10-12に施工担当が通常profileへ導入し、翌13日には約185分で床4,602個＋松明33本の施工完了と、終端のHP20・接地を確認した。松明回避策を維持した結果であり、通常環境での高速化倍率や回避策なしの再発解消は未確定。

返却した`action_id`を`agent_get_action`の`include_result:true`で確認する。`confirmed_count`は今回確定した設置数、`observed_count`は既に一致していた数。設置後の前進で止まった場合にも確定済みの設置は残る。取消は`agent_cancel_action`。

R30候補では、`floor_extension_input_lease_expired`で停止した場合の`result.failure_diagnostics`に、停止段階・検査位置（tick開始／heartbeat）・期限超過ms・最終更新からのtick差・当該tick内の実行msを最大5件の短い文字列で返す。script／設計図の親では`last_command.action.result`にも保持する。入力期限の最大2秒は変更せず、失効後に更新して動作を再開しない。設計図の`next_index`は成功した子命令までの位置であり、失敗した子の確定済み設置は`confirmed_cells`と現場の再観測を突き合わせて別途扱う。

R24候補では、届く位置で現在見えている対象が設計と一致するとき、新規設置用の支持面が露出していなくても既存一致として先へ進む。遮蔽・色違い・未確認の対象を一致扱いしない。[既存セルからの再開試験](experiments/20261013_v2_existing_placement_resume.md)を参照。通常profileへの導入状況は状態記録で確認する。

松明付近の待ち時間は、設置に加えて区間ごとの記帳・支持観測・位置合わせ・次施工の受付にも依存する。2026-10-14には、確定記録を保持したまま大型台帳の変換を短縮するworkerを導入し、通常環境で384床の試行、さらに4,608床＋33松明の施工が成功した。松明後の区間境界の推定中央値は旧38件30.197秒→新33件6.999秒へ短縮した。製品JARとカメラ速度の変更はない。別行・時刻の比較であり施工全体の短縮率ではない。[計測・導入・実機結果](experiments/20261014_v2_torch_worker_speed.md)を参照。

## 緊急退避時の装備

R29候補は、安全な乾いた床へ退避した後、通常所持品に準備した胸防具へエリトラから交換できる。バッグの防具・弓・矢は安全な時に公開inventoryで取り出し、盾はoffhandへ準備する。射線の変化による武器の選び直しと、観測した敵の通常非貫通矢への盾使用を追加した。交換のserver照合と取消・失敗時の解放を含めた[仕様・実機結果](experiments/20261013_v2_emergency_equipment.md)を参照。水面・狭い岸へのエリトラ緊急着地は未解決で、通常profileへの導入状況は状態記録で確認する。

2026-10-14にR29cを通常profileへ導入し、折返しを含む6床の施工成功を確認した。胸防具・弓・矢と空き枠を準備済みだが、盾の所持は未確認。通常環境で緊急回避を発火した検証ではない。[導入後の記録](experiments/20261013_v2_emergency_equipment.md#通常環境への導入後2026-10-14)を参照。

## スクリプトから実行する

`agent_run_script`内の`place(...)`にも同じ床延長が適用される。移動・視点・入力を1床ごとに外部から組み立てる必要はない。色を変えたいところで次の`place(...)`へ進め、通常の松明などは同じscriptから個別に設置できる。

```javascript
place(x=311,y=105,z=310,dx=3,block="minecraft:black_concrete",advance=true,max_ticks=1600);
place(x=315,y=105,z=310,dx=3,block="minecraft:gray_concrete",advance=true,max_ticks=1600);
place(x=318,y=106,z=310,block="minecraft:torch",advance=false);
```

親actionの`result`は実行中にも取得できる。

| 項目 | 意味 |
| --- | --- |
| `commands_started` | 開始した子命令数。失敗・取消した命令も含む |
| `active_command` | 実行中の命令名と1始まりのindex。待機中／完了時は省略 |
| `completed_commands` | 成功して返却された子命令数 |
| `placed_blocks` / `broken_blocks` | 子命令が返した確定済み設置／破壊の累計。失敗した子の部分変更も含む |
| `already_matching_blocks` | 設置時に既に一致していた累計 |
| `last_command` | 最後に返却された子のID・結果・停止理由。容量上限と打切り表示あり |

累計は子命令が終わるたびに更新される。全座標のreceiptや再起動後の自動再開記録ではない。材料切れなどで停止した場合は子の結果を確認し、残りを新しい有限の計画として指定する。未知の効果がある操作をそのまま再送しない。

## JSON/CSV設計図を区間scriptへ変換する

```powershell
python tools/building/compile_v2_blueprint.py design.json output-project --execution script
```

座標配列・CSV・palette形式は既存compilerと共通。同じY/Z・block/item/propertiesで連続するX座標を最大8床ずつまとめ、下層から行単位の折り返し順にする。airは設置対象から除外し、暗黙の破壊には使わない。複雑な建物・回路の支持順や昇降経路を自動生成するものではない。

出力には`place-sections.mcs`、`plan-00000.json`などの区間データ、対応する`request-00000.json`、source/dataのSHA256付き`manifest.json`が含まれる。各dataは最大4096床かつ2 MiB。128×128×9＝147456床なら36ファイルになり、同一色が連続する場合は合計18432区間。全体サイズとは別に各jobと読取り量の上限を維持する。

1. 出力一式を、このゲームで設定したscript resource rootへ置く。subdirectoryへ置いた場合はrequestの`source_file`と`data_files.blueprint.path`にその相対prefixを付ける。
2. 現在のworld/session・dimension・施工原点とmanifest、材料と先頭の支持床を確認する。
3. requestファイルのJSONを`agent_run_script`の引数にする。1回でそのファイル内の全区間を実行する。
4. 親actionの終了と部分結果を照合してから次のファイルへ進む。従来の`Invoke-McmcpV2Blueprint.ps1`は`--execution steps`用であり、このscript形式には使わない。

JSON/CSVの内容はデータとしてのみ読み、コード評価しない。任意path・OS・networkアクセスは許可せず、登録したsourceとdataは開始前に不変snapshotとして読み込む。全体の形状や総量を小さな固定寸法へ制限するのではなく、区間に分けて処理する。
