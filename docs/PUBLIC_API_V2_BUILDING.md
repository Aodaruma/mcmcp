# v2の床延長とスクリプト施工

この文書は2026-10-11の開発候補の仕様。通常profileへの導入状況と実機結果は[状態記録](PUBLIC_API_V2_STATUS_20260928.md)を参照する。

## 床の連続延長

`agent_place_block`の`advance:true`は、届く支持面までの移動に加え、通常の完全立方体の端へしゃがんで寄り、側面を実際に狙って設置し、server確認後に新しい床へ進める。`advance:false`が既定で、位置を動かさない。

```json
{"x":311,"y":105,"z":310,"dx":7,"block":"minecraft:black_concrete",
 "advance":true,"max_blocks":8,"max_ticks":3200,"max_distance":32}
```

これはX方向8床の例。`dx:-7`なら逆方向。開始時には先頭床に隣接する既存の安全な支持床が必要。氷・足場・半block・障害物のある通路へ、この床延長手順を一律適用しない。未ロードの設計図全体を観測済みと扱わず、現場で1床ずつ照合する。

返却した`action_id`を`agent_get_action`の`include_result:true`で確認する。`confirmed_count`は今回確定した設置数、`observed_count`は既に一致していた数。設置後の前進で止まった場合にも確定済みの設置は残る。取消は`agent_cancel_action`。

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
