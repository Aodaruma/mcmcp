# R18 水バケツのblock interactと通常クリックの差

2026-10-10。通常R18の地図アート担当から受け取った保存結果と、手元のMinecraft 26.2／NeoForge 26.2.0.59のコードを調査した記録。開発担当によるゲーム入力・再起動・差し替えは行っていない。**この記録では製品コードを変更しておらず、agent_interactの修正は未実装**。

## 保存結果

証拠は `_PLAYING/map-art-MFwtpM21wWc-3x3/duper-operation/conversion-nw-water-repair-20261010.json`。対象はEnd `(98,49,-3)` のoak_leaves。

- 最初の水入り化 `330c49e4-adff-4d84-bd64-3c6b43a27386` は `block_target_unavailable`。この結果だけでは操作経路の不備を特定しない。
- bucketをhotbarへ準備した後の `agent_interact` `76164bce-9192-4718-8be2-2dfcf830b91d` も `block_not_confirmed`。後続のfresh観測では葉waterlogged=false、水bucket1個だった。
- 続いて `agent_look` `2c738772-ca78-4522-8c91-6176b2eef39d` と、exact block条件付き `agent_click` right1回 `f5e66bc2-5d09-44c7-af58-a491971affac` を担当が実施。別のfresh観測 `97cb8cff-e7c3-4a51-bd8e-07f088b924d0` で葉waterlogged=trueと空bucket1個を確認した。
- これは別操作の成功と後続観測であり、元のinteract失敗を成功へ変更しない。clickの成功だけで水入り化の成立を判断せず、葉と手持ちの変化を照合している。

この葉に面で隣接するNW角では、粉の静止設置も硬化へ変わった。[同じ角の変更前後の試験](20261010_concrete_powder_code_review.md)を参照。

## コードで確認した差

`MinecraftV2BlockInteractDriver.dispatch` は、対象・可視面・reach・選択slot・手持ちitem・姿勢・直前block状態を確認した後、`MultiPlayerGameMode.useItemOn` を1回呼ぶ。prediction sequenceが1つ進み、戻り値がconsumesAction=trueであることを要求する。falseなら確認待ちへ進まず失敗する。`MinecraftV2MenuDriver` も同じopenerを使う。

通常の `Minecraft.startUseItem` は、blockに対する `useItemOn` がSuccessなら終わり、Failなら停止する。それ以外で手持ちが空でなければ、続いて `MultiPlayerGameMode.useItem` を呼ぶ。`useItemOn` はblockの利用と `ItemStack.useOn` を処理するが、この `useItem` への後段を内包していない。

`BucketItem` は `useOn` をoverrideせず、継承した `Item.useOn` はPASSを返す。水の配置・waterlogging・空バケツによる回収は `BucketItem.use` にある。したがって、**現行block interactにはバケツのuse処理へ進む経路が不足している**。この欠落は現場のクリックとの差と整合する。現場actionの内部戻り値やprediction番号は保存されていないため、特定actionの全実行段階を確認したという意味ではない。

## 修正時に保持する条件

バケツの `use` は渡されたblock hitを使うのでなく、目線・現在の向き・blockInteractionRangeからOUTLINE rayを再計算する。水入りバケツはFluid.NONE、空バケツはSOURCE_ONLY。水を受け入れられるblockならそのcell、それ以外ではhit面の隣接cellを配置先に選ぶ。そのため、汎用の `useItem` を無条件に追加するだけでは指定cellへの作用を保証できない。

まず水入り化を対象とし、ネイティブのrayと配置先が指定cellに一致すること、水を受け入れられる直前状態、同じ手持ち・姿勢・session・入力許可を追加送信直前にも確認する案を検証する。既に消費・拒否されたblock操作に追加使用を送らず、menuを開く経路との責務も維持する。手持ちが変わった場合や、MOD hookが予期しない副作用を返した場合は再送しない。

`useItemOn` と `useItem` はそれぞれpredictionを発行する。2回を1回分として扱わず、各発行を捕捉し、最後のACKと指定blockのfreshなserver状態で完了を判断する。タイムアウト・例外・結果不明に対して同じ使用を再試行しない。

隔離fixtureでは、乾いた葉への水入り化、既に水入りの葉、rayのずれ、受け入れ不能blockと隣接air、Success/Fail後の追加送信なし、途中取消、2つのACK順序を確認する。空バケツ・一般の液体設置・MODバケツまで対応済みとは扱わない。通常profileを追加操作する必要はない。

## 根拠

JDK25のjavapで開発用patched JARから `Minecraft.startUseItem`、`MultiPlayerGameMode.useItemOn/useItem`、`BucketItem.use/getFluidContext`、`Item.useOn/getPlayerPOVHitResult` を読み取った。作業用bytecodeはローカル `artifacts/v2-powder-code-20261010` に保存し、原ソース全体は公開しない。対象JARのhashは[元のコード調査](20261010_concrete_powder_code_review.md#根拠の保存)に記録済み。
