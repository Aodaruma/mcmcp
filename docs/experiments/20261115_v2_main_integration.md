# v2とmain観測・レバー修正の統合候補

- 専用branch: `integrate/v2-main-20261115`
- v2基点: `19570604563bd183fbd17e5464f892bc668b6b67`
- 初回merge元: `5015bb7f49ec0619ef45d73528d4cfb8530a0a79`（#88 cursor release、#89 atomic latest、#80 lever）
- #35/#86/#87はこの初回候補に含まない。#91のflight source、常用設定、ゲームには触れていない。

## 競合と接続

6fileの競合を解消。公開catalogはv2の17toolと軽量state/default compact/NBT/各既存operationを残し、cursor releaseとnullable latestの閉じたschemaを合成した。通常pageだけにdelivery receiptを作り、release responseにはreceiptもblock-entity補足も作らない。compactもframe_id=nullでstore内のatomic latest選択へ渡す。

v2のレバー操作は既存agent_interactのexpected_after_properties.poweredを目的状態として解釈する。通常reach内のfresh可視対象が同値ならuse/aim/hotbar/posture前にSKIPPEDし、before=afterをobserved_cellsへ残す。confirmed_countを増やさず、同値でも空hotbarを要求しない。変更時は既存通常useを1回だけ送り、server ACKとpowered以外のproperties不変を確認する。dispatch前に同値へ変わった場合もuseを省略する。明示条件なしの通常useは従来どおり。自動OFF・未知結果の再送を追加していない。

legacy-action-tool-v1.jsonは非配布の共有実行器regression fixtureであり、mainのlever node/exampleだけを同期した。旧Action DSLを公開17toolへ戻していない。

## 検証

catalog/fixture JSON parse、PowerShell parse、競合marker/未解決indexなし、diff --checkを確認。Javaの新規回帰は、releaseでNBT readerを呼ばず混在fieldを拒否すること、レバー目的状態の同値/向き不変、no-opをconfirmed mutationへ数えないことを追加。既存atomic-latest/store/cursor test、compact/NBT/lightweight state/17tool testsを保持する。

親の指示でこのPCのGradle/Java buildと実機は未実施。親review後、隔離build/CIと実機受入が必要。SkipJava source check結果と最終catalog hashはレビュー報告に記録する。

SkipJava source checkは成功。Python transport 23、building 6、build-worker 6件とbuilding/capability/v2 runner mocksが成功した。最終catalog SHA256は484628a340682fdbe739e061bd51360a16b99f829c5cc3145bf5c47abb5bfeed、Tool surface SHA256は66406fcb52fb7d1678bc0f74585ccebe7ecc8e7ea7619c2b8e71e880e847179f。Java testsは未実行。
