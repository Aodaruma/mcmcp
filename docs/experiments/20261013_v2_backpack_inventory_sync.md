# バッグ移送後のプレイヤー所持品同期（R23）

2026-10-13。通常R22bの施工担当から、SophisticatedBackpacksから板材32個を取り出した後、棒64個のクラフト計画が `inventory_server_snapshot_required` / `slot_mismatch` で停止したとの報告。実行・材料消費はまだ行われていない。通常ゲームの所有は施工担当が保持し、この開発チャットは操作・再起動・交換しない。

## 原因と修正

収納MODの独自full/slot受信は収納操作の確認記録に反映される一方、クラフト・数量move・sortが参照する `PlayerMenuSyncSignals` に反映されていなかった。live所持品が更新されても、確定記録には以前の値が残る。

検証済みproviderの受信hookでmenu ID・state ID・payload型・枠数／範囲を確認し、full/slot両方からプレイヤー所持品に属する枠を共通の記録処理へ渡す。収納内の固定offsetに依存せず、実際のcontainer identityとinventory indexでplayer menuへ対応づける。通常のvanilla受信も同じ対応づけを使う。

- 値はサーバー受信payloadから取得し、liveの予測値を確定記録として採用しない。
- 空になった枠とitem/components情報も更新する。収納枠やupgrade枠はplayer枠として扱わない。
- 初期player full packetが未受信なら、部分更新から全体の確定記録を作らない。
- 別menuの更新をplayer cursorの確定証拠に流用しない。world/player変更時の記録破棄も維持する。
- 公開ツール・引数・設定は変更しない。`agent_get_state` の診断は引き続き読み取り専用である。

`inventory_sync.snapshot_tick` / `last_full_packet_tick` はJVM内で累積する受信記録用の時計であり、worldの `client_tick` と同じ起点ではない。大小の比較だけでは旧sessionの残存と判定できない。

## 検証

aod-mimoidの隔離Dockerに固定 `storage_craft` fixtureを追加し、同じharnessで旧R22bとR23を比較する。通常profileや私設serverを試験用に使用しない。

旧R22bでは、バッグからjungle_planks32個のtakeが成功した直後、player menu slot9のlive32／確定記録air0でplanが停止した。バッグの再inspect後も不一致が残り、現場と同種の症状を再現した。失敗Action `340265cf-f0bc-45d0-8334-8f1e1e52fc9e` はplanであり、craft executeは送信していない。

単体試験では、枠対応・他containerの除外・空stack・不変snapshot・cursor証拠の維持・初期full未受信・session変更を確認。Java 1,926件（unit1,884／harness14／admin28）、build、harness隔離、source checksが成功した。

R23では同じfixtureで、板32個take → 棒64個plan/execute → バッグ再inspect → 松明21個take → 数量move7個 → sort → 棒16個store → バッグ再inspectの計10 Actionsが成功した。take/craft/store後の診断はいずれもmatchedで、unknownやrelease_pendingはない。craft executeは `597f3479-9cd9-4c75-a2c3-c15cc1784db3`。

server側の固定reportでも、バッグに板14・松明235・棒16、playerに板0・松明64・棒48を確認した。板32個の消費と棒64個の生成、松明総数299個が一致し、元からあった黒コンクリート32個と全upgradeは保持されている。終了時はHP20・cursor空・READY・実行中Actionなし。実行profileの製品とharnessのhashも照合した。

試験スクリプトの初回はstate sectionsの無効な `player` 指定を修正した。旧版の期待失敗はMCPのisErrorでも返るため、保存済みreceiptから検証を継続し、材料移送を再送していない。これらは製品の追加失敗ではない。

## 配布と制限

- 製品：親mcmcpディレクトリの `artifacts/v2-inventory-sync-20261013-r23/product-r23.jar`
- SHA-256：`1F9FB8772B706D61ACDCFFF9C5F76FCC02EC623DDB80AB6295B7A4E65D58857E`
- harness：`harness-r23.jar`、`ADF546E18DD29BDF0C49F9641169F3F62B617BD3B93C2BEEF35038905652BAC7`。通常環境へは入れない。

R22bの施工高速化・従来の緊急回復等を含む後続候補。通常profileへの導入と長時間の通常server運用は未確認で、交換時期は施工担当が調整する。今回の修正対象は収納移送後のplayer snapshot不一致であり、元報告にあった別件の `known_menu_update_deadline_exceeded` や全MOD収納の互換性を解消したとは扱わない。未確認移送は再送せずfresh inspectで残数を照合する。

06:53 JSTに元66ファイル（world61を含む）をhash一致で復元し、fixtureを撤去した。06:54 JSTにDockerプロセス0・Ubuntu／docker-desktop両WSL停止とlease解放を確認。ローカルに回収した `evidence.zip` のSHA-256は `4DEF2F769514A889E663EC95F357E72CF95DAF18A480F6B4A6CCE2E5EAC40E49` でremoteと一致する。`final-verification.json`、`restoration.json`、`shutdown.json`、`complete.json` に確認結果を保存した。
