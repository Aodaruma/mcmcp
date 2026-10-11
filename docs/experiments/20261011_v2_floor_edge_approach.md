# 水平な床の延長時の端寄り（2026-10-11）

## 現場報告と現状

nav-r14で既存の支持blockに隣接する床を `agent_place_block`（advance=true、max_distance=6、max_ticks=240）で設置すると、支持block上面は観測できるが必要な側面が見えず、duration_limit・placed=0になった。体力は20。現場座標・world・private logは調査資料へ流用しない。

担当側では支持block中央からしゃがんで端へ寄り、短いforward+sneak入力後に静止してからadvance=falseで設置すると1個成功した。成功Actionは `e8570dd1-d51e-46da-a677-2c45adb03247`。支持blockと体のAABBが重なる姿勢を維持し、体力20だったとの報告。これは複数の公開操作を組み合わせた回避策であり、place単体の修正確認ではない。

続く公開scriptの実測は、端寄せとplaceによる8床が約39秒、既存支持列に沿うmoveとplaceの8床が約28秒。黒・灰のバッグ自動補充も収納減数と手元数量維持で確認された。担当は有限65床までの施工を進めている。147,456床への単純な時間比例は長時間になるため、要求範囲内で端寄せ・設置確認・新床へ前進を単一job内で反復する方式を優先する。この実測は現場からの報告であり、最終面積の完成時間の保証ではない。

## コード確認

- `MinecraftV2PlaceDriver.beginObservedTarget` は現在観測できる支持面を選んだ後にしゃがむ。面を露出するための端寄りは持たない。
- `MinecraftV2PathDriver` の `bridge_block` は同じplace処理をadvance=false・max_distance=0で使用する。材料指定だけでは未観測の側面を露出できない。
- 旧実行器の `MinecraftFloorExtensionAttempt` には有限の端寄りがある。通常の完全立方体上で、しゃがみ・停止距離・AABB支持の重なり・現在位置の補正・健康状態・server設置確認を検証する。旧DSLを再公開せず、この幾何条件と通常入力の手順をv2へ適用できるか検討する。

## 実装・検証の条件

利用者との仕様検討後、advance拡張とscript施工を先に実装・検証・JAR交換し、その後に専用blueprint toolを実装する順序が承認された。現在は製品コードと隔離fixtureを実装中。同期診断だけのJARには含まれず、実機検証済みの新候補を別途固定する。

1. 現在確認済みの支持blockとその上面を起点に、1個の隣接targetへ向けた短いしゃがみ移動だけを許可する。面の情報を作り出さず、移動後に実際の側面hitを取得して通常placeへ戻す。
2. advance=falseでは移動しない。既存の時間・距離・入力所有・取消・Esc・world境界を維持する。端寄りの移動距離も既存予算へ算入する。
3. 通常の支持床が変わる、しゃがみが解除される、補正や損傷が起きる、支持AABBの重なりが不足する場合は停止する。足場のしゃがみ下降、氷など摩擦の異なる床、半block等へ一律適用しない。
4. 4方向、連続2個、開始位置ずれ、材料不足、advance=false、取消、距離／tick期限を独立した隔離fixtureで確認する。既存面からの通常設置とbridgeも回帰確認する。
5. 範囲jobへ接続する際も1個ずつserver確認後に次の支持床として使う。範囲外への設置、未確認配置の再送、予算の再設定は行わない。途中の部分結果・入力解放・材料切れ・取消と、同一fixtureでの8床の時間を確認する。

通常ゲームは地図アート担当の所有を維持する。合格後の交換は利用者の指示に従い、担当間で操作権を引き継いで行う。

## 実装した契約

- `advance`は引き続き既定false。trueで条件が合う隣接床に対し、既存v1の有限な床延長実行器を再利用。開始位置が支持block中心からずれている場合のみ、同じ支持block上をしゃがんで中央へ調整する。
- 実際の体の支持重なり、通常の摩擦・移動属性、空の通路、損傷・位置補正・sessionを毎tick確認。cell中心だけの局所危険判定に代えて、この処理専用の厳密な位置検査を適用する。全体の距離・時間・取消条件は維持。
- 範囲内の各床はserver確認後に踏み込み、次の支持床に使う。確認済みの設置後に前進が失敗／取消されても、その1床を結果へ1度だけ残す。一般のinteractの未完了結果を設置確認と誤認しない。
- scriptの`place(...)`も同じ実行器を使う。親scriptのresultに`commands_started`、`active_command`、`completed_commands`、`placed_blocks`、`broken_blocks`、`already_matching_blocks`を追加。累計は子命令の返却時に更新し、停止した子の確定済み変更も含む。全座標の無制限な結果蓄積は行わない。
- `compile_v2_blueprint.py --execution script`はJSON/CSVを同じ材料の連続X方向最大8床の区間へまとめ、1ファイル最大4096床に分割する。共有sourceと各区間ファイル、呼び出し引数、SHA256付きmanifestを生成。既存の1床ずつの形式は維持する。

実機結果はこの後に追記する。128×128×9の全量ゲーム内施工は今回の短い隔離試験の対象外。

## R1の隔離結果と連続施工の修正

source `d864f7a3a61db176a309191f645c98ba423892c9`、CI 38120704139、製品SHA256 `29eb517fdc2b0c35e8c2599c018400787945eb43e673fdcdc42d248249c1fac9`。公開16ツール全定義一致を確認後、固定fixtureのeast8を実行。

開始(310.5,106,310.5)から約1秒でX=311.005、約2秒でX=311.457へ進み、(311,105,310)の黒コンクリート1個を確定。約3秒でX=310.456へ戻り、1個確定のまま3200 ticks/160秒で`block_not_confirmed`停止。体力20、終了後Actionなし・位置安定。失敗を再送せず後続caseを停止した。通常profileには未導入。

コードでは、1床終了時のしゃがみ解除で目線が変化し、次の支持床のcurrent-eye UP witnessがまだ無い場合に通常plannerへ切り替わる経路があった。合法な隣接床の幾何条件を先に確認し、fresh witnessを入力なしで最大80ticks待つよう修正。通常approach中もこの床延長入口を先に確認する。実機で同じ8床を再検証する。

併せて旧処理の固定8度/tickを設定済み視点速度の上限以下に抑えた。source `c5d9db014a6715ed312a371c945763db0506fae7`の速度修正だけのR2はCI 38121450568成功だが、実機試験を省き上記修正を含む次候補へ進む。全自動1860件が成功。

## R3の隔離結果と材料切れ停止

source `5e3f9b546957fd7053f0c8d837e7392ca6f30c68`、CI 38121696310、製品SHA256 `2585254cabda93a3a078940a69484b408c277a56a28ae04125a6215ed0f57d56`。公開16ツール全定義一致。

- east8は8床すべて成功、約15.5秒、終点X=318.523。15回の公開位置サンプルでは後退0。west/north/south各2床、開始中心からずれたoffset2も成功。
- inlineの黒4→灰4→松明は3命令・累計9個、外部 `.mcs`＋JSONは2命令・累計4個で成功。灰色はhotbar外からのstagingを含み、終了後inventory同期も一致。
- advance=falseは設置0・無移動。ゼロ件ではresult自体が省略される現契約に合わせ、検証側のみ公開progressと変更なしの記録で判定を補正（再送なし）。距離制限は設置0、取消は設置1を保持、障害物は設置0・無移動で有限停止。全ケース体力20、終端Actionなし、停止後位置安定。
- shortage4は2床確定後に開始側X=310.560へ戻り、160秒でduration_limit。不合格。隔離ログにmoved wrongly/too quicklyや位置補正の該当行なし。材料不足の例外が通常approachへのfallbackに分類されるコードと整合する。

R4 `e36dfc35ab88baca7225bcb3bd86327ba2380abe` は材料不足を専用例外として区別し、`floor_extension_material_unavailable`でその場停止。begin時の失敗理由も共通job結果へ伝える。scriptの子例外時に過去の成功を失敗診断へ残さず、終端resultのactive_commandを除去する。全自動1862件とbuild/harness分離成功。最終JARで材料不足とscriptを再確認してから通常profileへ引き継ぐ。

## 最終R4の確認

CI [38122700669](https://github.com/Aodaruma/mcmcp/actions/runs/38122700669)成功。製品SHA256 `f01b1b0dd70003f70c865354bf711d706890796f0afe902bd3be3ca9aea0a8c2`、catalog SHA256 `62a4ba38e78a8ec04996e4abc9a3218cc4a38b97ffac30c583f54cbccb2e764a`。隔離起動後に公開16ツールの全定義一致を確認。

- shortage4: action `ff45043b-5b52-490f-9082-d352f729b0e9`。2床確定、約4.44秒で`floor_extension_material_unavailable`停止。X=312.490の新床上で静止し、開始側への後退なし。
- external-sections: action `b62e6268-97d5-467f-a7d6-111b2bd2f809`。共有`.mcs`とJSON、2命令・4床が約8.35秒で成功。inventory_syncはmatched。
- 材料不足script: 累計2床と2命令目の失敗を保持し、3命令目は開始しない。終端でactive_commandを残さない。

3件とも体力20、実行中Actionなし、終端位置安定。R3から幾何・入力処理は変更せず、材料不足の分類と失敗診断だけを変更した。128×128×9の全量施工は自動試験の分割・模擬実行のみで、ゲーム内の全量完了ではない。隔離の元環境復元・停止確認後に通常profileへ引き継ぐ。

script停止caseのactionは`870e4732-79db-498d-bcfb-7165ceba375f`。commands_started=2、completed_commands=1、placed_blocks=2。2命令目の部分確定1個を累計へ含め、active_commandなし、3命令目の灰色64個は未使用。入力解放の実機根拠は公開Action不在と静止位置、および最終MCP OFFである。

復元は2026-10-11 16:59:03 JSTまでに完了。元66ファイル／world 61ファイルのhash一致、fixture削除、追加したscript/data 2ファイルの回収、container停止、Docker process 0／engine接続不可、docker-desktop WSL Stopped、lease解放を確認。45分leaseの期限17:07:21を延長していない。

距離停止caseはmax_distance=0.25で設置0・safety_interruptedになったが、実測水平変位は約0.385だった。tickで停止を判定するため、指定距離以内への厳密な位置停止はこの試験では確認していない。通常profileの施工には試験fixtureを使用しない。
