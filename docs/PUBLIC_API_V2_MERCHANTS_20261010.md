# v2 取引操作

`agent_interact` に `inspect_offers` と `trade` を追加。トップレベルの公開ツール数は15のまま。対象は観測参照で特定できる村人・行商人で、通常の使用入力と標準 merchant menu を使う。金床・エンチャントはこの追加には含まない。

## 候補の取得

```json
{"target":"entity","operation":"inspect_offers","entity_ref":"<観測で取得した参照>","max_ticks":1200}
```

接近・照準・開封・server候補の取得・閉鎖を同じjobで行う。`agent_get_action(include_result:true)` の `result.offers` に価格、完成品、在庫、`offer_ref` を保存する。参照は相手・session・dimension・候補の価格とcomponents・在庫に結び付き、発行から2,400 client ticks有効。保持数は256候補まで。

手持ち品を相手へ適用しないよう、開封には空の手を選ぶ。通常インベントリに空き枠が必要。`advance` は既定true、`max_distance` は既定64、`max_ticks` は既定1,200。

## 費用上限付き実行

```json
{
  "target":"entity","operation":"trade","entity_ref":"<同じ相手の参照>",
  "offer_ref":"<inspect_offersが返した参照>","count":3,
  "max_total_cost":[{"item":"minecraft:emerald","count":9},{"item":"minecraft:book","count":3}],
  "max_ticks":1200
}
```

`count` は完成品数ではなく取引回数（1～4,096）。`max_total_cost` は全回分の支払上限を1～2種類で指定する。同じIDの重複指定を拒否し、2種類の支払品を同時に検証する。品のcomponentsは候補参照と実際の所持品を照合する。

再開封した候補の価格・在庫・内容が変わっていれば、購入前に `merchant_offer_changed` で停止する。予算不足は `trade_cost_limit`、在庫不足は `insufficient_trade_stock`。材料不足・容量不足・参照失効も停止理由になる。通常の候補選択による自動投入後、余分な支払品を同期確認付きで戻し、入力枠を厳密に1回分にしてから結果を回収する。

`result` は `confirmed_trades`、`confirmed_count`、`consumed`、`unconfirmed`、`release_pending` と必要時の `failure` を返す。確定の根拠は新しいserver slot/cursor同期と支払・完成品の収支。未確認の購入を再送しない。取引した後の古い `offer_ref` は、再利用せず候補を取り直す。

scriptの `interact(...)` からも同じ引数で呼べる。取消・Esc・UI OFF・world変更では次の購入を止め、確認済みcursorを返却して所有menuを閉じる。終了は購入の巻き戻しではない。相手の使用入力には予測ACKがないため、開封前取消で返答が来ない場合は遅延画面を回収する権限を保持し、未解放のまま次の操作を始めない。

## 検証状況

候補 `merchants-r1` をaod-mimoidの隔離Dockerで実行し、以下を確認した。

- 村人の候補取得・閉鎖。2種類の支払による3回の取引でパン6個、エメラルド9個・本3冊の消費。
- 予算不足・在庫不足・取引済み候補の再利用を、追加購入なしで拒否。
- 候補取得後の価格変更を購入前に拒否。
- 支払い準備中の取消で、数量整合とmenu/cursor解放を確認。

単体試験では候補の相手・session・dimension・期限、components変更、整数境界と費用合算も検証した。行商人、すべてのMOD取引、ネットワーク切断・遅延の全組合せは実機未検証。試験runnerは `tools/eval/Invoke-McmcpV2MerchantSmoke.ps1`。この追加JARは通常の「くらふとぶ！」へまだ導入していない。
