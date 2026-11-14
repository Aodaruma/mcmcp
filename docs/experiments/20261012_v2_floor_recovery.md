# 退避後の床施工：足場への復帰と待機

2026-10-12。通常ゲームは施工担当が所有し、この開発チャットでは操作・JAR交換を行わない。昼間のaod-mimoid Docker利用は、今回に限る利用者の明示許可を受けた。

## 報告と切り分け

R19で設計図entry21858..21871の最初の設置が `mob_interference` → `resumed` → `block_not_confirmed` で停止した。親 `8990f84d-64c8-4878-ad3d-9d15b79d5473`、子 `2d8ccfd4-ff0a-4c89-92c0-040e8f38e650`。退避105 ticks／1.18466 blocks／stable100／interaction0。確定設置0、次の2床は観測でair。報告資料は施工担当の `construction/recovery-021858-mob-20261012.json` 等。

開始feet `(-719.522069,80,248.468380)`、停止feet `(-720.432218,80,247.945293)`。支持は `(-720,79,248)` の黒コンクリートとその上の松明。隔離fixtureで同じ相対位置から開始すると、R19と同じ動作の診断版でも2床の施工に成功した（8.38秒）。したがって、座標ずれだけが原因とは断定しない。

行商人が押し出した後も支持上に残るfixtureでは、退避は成功するものの、近傍の無関係な足場へ探索を続けて1800 ticksの施工予算を使い切る挙動を再現した。これを今回の修正対象とする。元報告の106 operationsでの短時間停止そのものは厳密再現できていない。

## R20の変更

- `advance=true` の通常フルブロック床施工で、同じ高さ・目的地点の6ブロック以内にいる場合、隣接する既知の立ち位置を到達先にする。地形と視認できるentityを反映した既存の経路地図だけを使う。
- 支持に進めない間は無関係な探索へ移らず、再観測して待機する。空いたら再計画する。親Actionのtick・実時間・移動予算と取消は維持し、永久待機にはしない。
- 足場到着後の照準・材料・当たり判定・server確定は既存の設置処理で再検証する。送信済み未確認の設置を再送する変更はない。
- `block_not_confirmed` だけだった設置失敗に、段階・経路失敗理由を付ける（例：`place_approach_evidence_timeout`、`place_floor_witness_timeout`、`place_staging_not_confirmed`）。公開toolと引数は変更しない。

## 検証

自動テスト1921件（unit1879、harness14、admin28）、`verifyHarnessIsolation`、build、補助source checksが成功。新しい回帰試験は、負座標での足場復帰、entity占有中の探索抑制と解消後の到達、未知・斜め・別階・別dimensionの不採用を扱う。

中間候補の隔離実機では、押し出し→退避→行商人が離れる→2床確定が15.03秒、最低HP20・反撃0で成功。行商人が居残る場合は同じ位置で待機し、予算終了で停止することも確認した。

最終製品JARは `product-r20-final.jar`、SHA-256 `6E2CE1E2A35BC51E5BFAF63FD699FE9BA1E309C92B53AF279B45F61ED23185AA`。実行profile上のhashも照合した。fixture harnessは `EC9C16F0AD6A6F6A514500642023D581726D8F2F836B7B4EA394C4F2AAF47756`（通常環境へ導入しない）。最終sessionは `a0141bf3-57b0-4179-b0f9-1ba9882fad5f`。

| 最終JARの試験 | 結果 | Action ID |
| --- | --- | --- |
| 押し出し→退避→足場解放→設計図10床 | 30.65秒、10/10確定、最低HP20、無反撃 | `8f3ebd0b-f60e-4373-93fd-d62a970a40d3` |
| 設計図の退避81 ticks時点で取消 | 4.40秒、親子ともcancelled/client_request、設置0 | 親 `6a8d3e72-4627-4aeb-bc2d-665f45906d33`、子 `5e2eeff2-8517-44aa-8a5a-dc77c8295548` |
| 報告と同じ相対位置ずれから2床 | 8.38秒、2/2確定、HP20 | `0af11701-df9d-4125-91f3-f356a4d6db1e` |
| 行商人が足場を占有し続ける | 移動探索せず待機、600通常ticks＋105退避ticksでduration_limit、35.35秒、設置0・HP20 | `517bd3f0-55ff-4d92-b531-899edb6ad422` |
| 自動補充＋松明＋色替え | 16.90秒、黒4／松明1／灰4、HP20 | `c1318d32-6561-43b7-8098-255effbb5040` |

10床の実world状態、全試験後のREADY／Actionなし、取消の子結果を別途照合済み。証拠は `artifacts/v2-floor-recovery-20261012-r20/` の `final-*-summary.json`、`final-verification.json`、取得した `evidence.zip` を参照する。

占有中の復帰後移動は、同じ1800 ticks予算で、修正前の診断版ではサンプル距離86.11 blocks、中間修正版では0.073 blocks未満。最終版600 ticksでは0.038 blocks未満だった（`occupied-support-travel.json`）。微小な慣性収束だけで待機しており、周辺探索を抑えられている。

13:07:48 JSTに元66ファイル（world61）をhash一致で復元、fixtureを撤去。13:08:13にDocker Desktop停止、13:08:26の取得時にlease解放・Docker関連process0・Ubuntu/docker-desktopのWSL停止を確認した。証拠zipは110ファイル、SHA-256 `4719DBB560E867C7966AAAE2843421D7947D2BC594B852C56E0316F9C6864971`。R20は更新候補として準備し、通常環境はR19のまま。

## 残る確認

通常環境の元報告、および従来の松明末尾の偶発停止について「完全修正」とは扱わない。通常施工で再発した場合は、新しい段階別failureと同時刻の座標・支持・材料・確定結果を使って原因を絞る。占有が予算内に解消しない場合は、破壊や中立モブへの攻撃で強制的に進まず、確定済みの施工結果を残して停止する。
