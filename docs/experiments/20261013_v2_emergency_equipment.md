# 安全床での胸防具復帰と弓・盾：R29

2026-10-13。通常R24のゲームと施工workerは施工担当が所有し、このチャットから照会・入力・再起動・交換を行っていない。aod-mimoidの専用Dockerに45分のleaseと期限時の復元・終了処理を設定して検証した。

## 変更した動作

- 緊急退避中、乾いた安全な支持床と退路を現在の局所観測で確認し、接地・減速・被弾停止・player inventoryのserver同期が揃うと、装備中のエリトラを準備済みの胸防具へ交換する。飛行、水、溶岩、乗車、火、窒息、直近の被弾中は開始しない。自動回復と `emergency_item_use` が有効な場合の処理。
- 胸防具は通常所持品0～35から選ぶ。nativeのCHEST用・交換可能・防御値が正の装備に限り、残耐久10以下や独自Item subclassは除く。余分な空き枠1つ、残り100ticks、操作予算2を要求する。バッグは平常時に公開 `agent_inventory` のinspect/takeで準備する。敵に襲われてからバッグを開く処理は追加しない。
- 交換はnativeの装備使用を1回だけ行う。cursorを使う二段階移送ではなく、持った防具と胸のエリトラを交換する。server ACKだけで成功にせず、胸枠とエリトラ返却枠の両方について、交換前と同じ数量・コンポーネントのserver記録を要求する。手持ちへ出す際に所持品の位置は変わり得る。
- 取消・拒否・90ticks以内の確認不能では入力を解放し、未確認使用を再送しない。結果は `result.recovery.equipment` の `confirmed` / `skipped` / `unconfirmed` / `interrupted`。既に成立した装備交換や所持枠変更を巻き戻したとは報告しない。
- 弓は射線や周辺の他entityを毎tick見直す。初めに射線が塞がって剣を選んだ後も、射線が開いたら弓へ選び直せる。引き絞りの中止は発射を伴わない手持ち変更を使う。
- offhandに準備したVanilla盾は、現在観測できる敵対mobが放った、見えているVanilla通常矢・光の矢のうち非貫通で身体へ向かうものに対して構える。攻撃者を認識する前の初撃や未知のMOD投射物は対象外。防御には向き、盾の遅延、有限の入力・操作・camera予算がある。`shield_uses_dispatched` は構えた回数で、防げた回数ではない。

公開tool数・引数は増やしていない。`agent_get_action` のcatalog説明を結果の意味に合わせた。既存のエリトラ再装備は通常所持品内も対象としており、防具へ戻した後の飛行開始にも使う。

## 隔離試験

固定fixtureでバッグに名前付き・damage17のdiamond chestplate、胸にdamage21のElytraを用意し、公開inspect/takeで防具を取り出してから危険を発生させた。盾も公開inventory moveでoffhandへ移した。fixtureは通常JARに含まれず、private統合server・所有者・期限の確認を経る。

| ケース | 確認結果 |
| --- | --- |
| Normal-2 | 行商人との接触から退避、胸防具交換、HP20。防具とElytraの全コンポーネント一致、各1個保持、cursor空。行商人HP20。 |
| Full-3 | 36枠が埋まっていると `equipment_spare_slot_required`。胸Elytra維持、所持品保持。 |
| Water-5 | 着水225ticksはElytra維持。岸へ脱出した後だけ胸防具へ交換、HP20。 |
| Cancel-6 | 手持ち準備後・装備使用前の取消。`interrupted`、装備使用dispatchなし。全コンポーネント保持、cursor空。 |
| Bow-4 → Bow-7 | 射線が塞がった時に剣の選択が残る不具合を再現。R29b修正後は弓3射でスケルトン撃破、退避終了。旧Bow-4は成功集計に含めない。 |
| Shield-8 | 構え6回に対しserverの盾防御イベント4件・計12ダメージ、盾damage0→16。操作予算8以内。全矢を防いだとは扱わない。 |
| Flight-9 | 既存の広い着地可能床のfixtureで78ticks滑空。飛行中Elytraを維持し、接地後だけ胸防具へ交換、HP20。 |
| Shield-10 | 構え途中に取消。結果とserver終端の双方で構え解除、cursor空、READY。 |
| Normal-11 | 防具をバッグに残すと `chest_armor_not_prepared`。危険中のバッグ操作なし。 |
| Locked-12 | 束縛の呪いでnative交換拒否。ACK受領は成功と扱わず `unconfirmed`、期限内終了・再送なし・両装備の全コンポーネント保持。 |
| Flight-13 | 初めに胸防具を着用。落下後にElytraへ交換、68ticks滑空し、乾いた接地後に胸防具へ復帰。全server標本の装備遷移を照合、HP20。 |
| Bow-14 | `agent_move` 中の接触→防具切替→弓3射で撃破→元の移動へ復帰し、x330付近へ到達。親Actionは `succeeded`、recoveryは `resumed`。 |

12ケースの期待結果を `verify-results.ps1` で照合。全ケースでcursor残留なし、飛行・水中でのElytra取り外しなし、危険な胸防具への遷移なし。完了fixture報告はAgentのmovement/attack/use/pick/goal所有がない時だけ取得する。戦闘ケースでは最低HP17.816となった。混在する行商人はHP16となるため、戦闘中の周囲のNPC無傷を保証する試験ではない。

raw入力を危険のトリガーにしたケースは、親の入力Actionが `failed/preempted_for_recovery` で終了する契約。救助成功は別の `recovery.status=stable` で判定した。通常Actionの復帰を確認したのはBow-14の移動。施工再開・通常サーバー上の襲撃・MOD投げ斧の通し試験を今回実施したとは扱わない。

## 製品と未解決範囲

最終製品は親ディレクトリの `artifacts/v2-emergency-equipment-20261013-r29/product-r29c.jar`。

- 製品SHA-256：`51CFAA9C633FBF7215A7DC64BE98811227C6904007E9001BDB8DDF9C7DBD9EF8`
- 隔離harness SHA-256：`A1259BFE828126DDC723522C0509EEE712ED15732D2DF78776DD1E318B0D0426`。通常profileへは入れない。
- Java1,932件、build、harness隔離、source checks成功。R29bとR29cの製品class1,359個はhash一致し、R29cは公開catalog説明を補ったもの。最終JARでLocked-12、Flight-13、Bow-14を実行した。

R25/R26から保持した再観測・着地時刻の修正を含む。R27/R28の不採用着地試案は含まない。この候補は防具・通常矢への防御の限定更新であり、[水面と狭い岸の緊急着地](20261013_v2_water_flight_recovery.md)は未解決のまま。広い床のFlight-13成功から、元の岸へ戻れない問題が解消したとは判断しない。MOD防具・特殊投射物・自動バッグ準備・通常サーバーでの実運用は未検証。

23:53 JST、保存終了後にworld session/Actionなしを確認し、元66ファイル（world61）のhash一致で復元した。Dockerプロセス0、Ubuntu/docker-desktop両WSL停止、lease解放・LEASE_COMPLETEを確認。復元・停止・配布範囲の最終判定は成果物の `final-metadata.json` に記録する。通常ゲームへの導入は施工担当が調整する。

## 通常環境への導入後（2026-10-14）

01:09 JSTの交換担当の引継ぎ記録は、通常「くらふとぶ！-v01.2」の新JVMが開いた製品JARのhashが上記R29cと一致し、catalog・設定・設計を保持したことを示す。この段階では手動並べ替え後の所持品同期が未確認で、全条件合格とはされていなかった。

施工担当は本人の起動・移動の説明を受けた後、同じ新session `612fc3c9-f56b-4e4b-867f-e3123f41064a` で支持床を再確認し、公開バッグinspectにより同期不一致7件を0件へ戻した。胸防具1を通常所持品へ取り出し、弓1・矢64・余分な空き枠（試運転前9枠）を準備した。盾の所持は未確認。

01:14 JSTの試運転はentry57459..57465、Action `cc3eaf98-8c37-42f6-9deb-0601c462f2ee`。折返しを含む6床を13.6209224秒で設置し、Actionと全6entriesが成功。終了後の公開再観測はHP/食料20、接地、既設black concrete支持、次セルair、所持品同期matched、実行中Actionなしを示す。床56,838個・松明627本から、worker44176で151行末へ続行するとの報告を受領した。

この開発チャットは保存済みの次の証跡だけを読み、通常ゲームへ照会・入力・再起動・交換していない。ゲームの所有は施工担当とworkerが保持する。

- 親作業ディレクトリ `artifacts/v2-emergency-equipment-r29c-install-20261014/handoff.json`
- `_PLAYING/map-art-MFwtpM21wWc-3x3/construction/r29c-root-ready-20261014.json`
- 同ディレクトリの `r29c-pilot-verified-20261014.json` と `blueprint-057459-057465.result.json`

緊急回避を意図的に発火する試験は行っていない。通常環境での緊急着地・襲撃・反撃後の施工復帰は未確認のまま。6床の試運転から高速化率や長期安定性は推定しない。前節の未導入・未検証表記と凍結成果物のmetadataは作成時点の記録として保持する。
