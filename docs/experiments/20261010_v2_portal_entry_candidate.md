# 明示的な自然Endポータル進入の候補

2026-10-10。Issue #84／Draft PR #85内の独立した変更。**通常R18へは未導入、実機未検証**。通常ゲームの入力・再起動・JAR差し替えは行っていない。実機未検証候補を差し替え担当へ導入依頼しない。

## 問題と公開仕様

通常R18では、ポータル下に支持がないと、有限forward入力も通常地上の落下防止で止まる。自動sneakが原因ではない。入力jobの成功は入力の消化であり、移動量や転送の成功を意味しない。地図アート担当は8tickの追加試験でも移動0を報告し、装置OFF、health20、jobなしで別の安全な足場へ退避している。

専用の意図を既存 `agent_interact` に追加する。ツールは15個のまま。

```json
{
  "target": "portal",
  "block": "minecraft:end_portal",
  "x": 0,
  "y": 64,
  "z": 0,
  "expected_dimension": "minecraft:the_end",
  "max_ticks": 200
}
```

- `target`、`block`、整数x/y/z、`expected_dimension`は必須。対象は可視証拠を持つ近傍のEndポータル1cell。開始時はSurvival・接地・横距離1.75block以内、足元の高さはportal cellの上面付近を必要とする。まず普通の移動で入口付近へ来る必要があり、この操作は遠方への経路探索を行わない。
- Overworld→End、End→Overworldに限定し、行き先dimensionを明示する。End帰還先がMOD等で別dimensionになった場合は到着不一致とする。Netherポータル・MODポータル・creative／spectatorの入口操作は含まない。
- `max_ticks`は20～200、既定200。開始から同じ長さの実時間上限（最大10秒）も持ち、forwardの発行は最大40tick。ジャンプ、スニーク、block変更、teleport command、独自packetを使わない。送信前のdelivery確認と通常の取消を維持する。
- 初版は単独のaction。scriptからの呼出しは拒否する。既存scriptのworld境界での停止を変更せず、到着後は新sessionから新しいactionを始める。

## 移動の証明

通常の `agent_move`／`agent_input_sequence` の落下判定は維持する。専用jobだけが、player／level同一性と現在のworld revisionに束縛されたportal intentを所有する。通常の移動証明へ戻る、入力を解放する、期限が切れる場合はintentも失効する。

各tickの衝突予測で、対象が現にロード済みの `minecraft:end_portal` であること、身体の通過範囲・衝突・流体・窒息・接触ダメージ・弾む床を検査する。通常の支持証明が通らない部分では、身体の水平投影が**指定した1cell内に全て収まる**場合だけ、ポータルへの接触を着地の代わりに使う。対象へ近づく動きに限定し、上昇、遠ざかる移動、範囲外・深い落下、未知の証拠では許可しない。別cellの穴にこのintentを流用しない。

初回の可視証拠だけで継続せず、進入中も対象blockを再照合する。消えたportal、frameだけの未開通portal、対象の変更は停止条件。server側だけで転送が無効になっている場合は、到着が確認できず期限で失敗する。

## world境界と結果

最近の実際の接触を確認したjobだけを到着確認へ移す。world境界の既存評価lease終了を先に実施し、camera・移動・所有入力を解放してから、入力を持たない到着確認として保持する。評価leaseを新worldへ引き継がない。

到着待ちは同じ接続内だけ。新しいworld_session_id、明示したdimension、生存したplayer、所有入力なしを照合してから成功にする。切断／再接続、死亡、取消・Esc・OFF、期限超過、同じsession、別dimensionは成功にしない。到着待ち中に移動入力を再開しない。通常のON意図引継ぎも既存の接続・停止epoch・解放条件を通し、到着待ち以外のjobが残る場合は除外しない。

`agent_get_action(include_result=true)` には、入口座標と種類、source session/dimension、expected dimension、phase、contact_observed、inputs_released、arrival_confirmedを残す。新worldを確認した場合はarrival_session_id／arrival_dimensionも付く。contact_observedだけでは到着成功ではない。取消や失敗は既に起きた移動・world移動を取り消さない。転送直前にserverでportalが消える競合を原子的には防げず、その場合の物理的な落下を巻き戻す機能でもない。

## 自動試験と隔離実機の計画

自動試験では、1cellへの身体包含、近傍・高さ・進行方向の境界、通常入力へのintent非伝播、明示引数・schema、delivery前／queued／接触後の取消、入力解放失敗、接続変更、誤ったdimension／同じsession、進入・到着待ち双方の期限、消失後の非再試行を扱う。既存のworld境界・評価lease・入力解放の試験も全件実行する。合格件数とCIの対象commitは候補の検証記録に保存する。

ローカルではJava 1,747＋harness 14＋admin bridge 28＝**1,789件**、build・harness隔離が成功した。候補はR19/R20と追加診断を含む統合ブランチからのもの。入口変更だけをR18へ移植したJARではない。固定済みR18/R19/R20の配布物は変更しない。

実機はaod-mimoidの**23:00～翌12:00 JST**の隔離Dockerだけで行う。新しいbaselineとleaseを取得し、通常「くらふとぶ！」を使わない。元world・設定・JARのhash一覧を取り、試験後に全て復元しcontainer／Dockerを停止する。

1. 専用fixtureで、北側の支持床からportalへ降りる構成を作る。portal下は普通の地上判定では拒否される2block落下、さらに下には落下を止める床を用意する。入口・出口chunkをロードし、End到着台と帰還地点を別々に記録する。試験管理側の準備操作と公開MCP操作を分ける。
2. fresh観測でportalを確認。通常のforwardとmoveは従来どおり縁で止まることを対照確認してから、専用actionを実行。server側到着、公開session/dimension、入力解放、job結果、healthを照合する。両方向を独立に試験する。
3. 未観測座標、ただのAIR、portal frame、遠方、非対応mode／dimension、誤った期待dimensionで成功しないことを確認する。未知の穴に進入しないことをserver位置でも確認する。
4. 開始前・照準中・進入前にportalを消すケース、入力中の取消／Esc／OFFを試験。最大40tickと実時間期限を超えて入力が残らず、同じ操作を再送しないことを確認する。落下を伴う競合は捕捉床のあるfixtureだけで行う。
5. 接触後に転送されないfixture、別dimensionへの転送、同dimension respawn、切断／再接続で成功にしないことを確認。評価leaseのworld境界停止も従来どおり確認する。
6. 通常の歩行、梯子、飛行、raw入力にportal intentが残らないことを確認し、元環境を復元する。

この計画を記載しただけで実機合格とは扱わない。通常導入や自動往復の再開には、上記の隔離試験結果を別途確認する。
