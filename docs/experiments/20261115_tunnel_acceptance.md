# 坑道候補の追加受入（2026-11-15、#21 / PR #26）

状態: **直線 16 は未合格、Draft を維持する。** 床欠損に対する短い安全停止は確認したが、正式 gate の証拠検査も失敗しており、全体合格として扱わない。製品コード変更はない。

## 対象と境界

- source: `8a0f51bcf2a6fff04702155db44c07ec6251e114`。
- 今回の clean build 製品 SHA-256: `a4468060b351b0668514b7f6afbcdb2568eef1d7473707a48bf31adf04ee63f6`。dev harness: `a94e1a0a2c353d395294cff9c4f337b4ea924c3f409cace1604286001b96a6f2`。
- Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25、隔離 Docker clone。通常 profile には適用していない。
- 直線と hazard を別々の有限 lease / JVM で実行し、それぞれ world・MOD・設定の 66 ファイルを hash 付きで事前保存した。source の `tunnel_straight16` / `tunnel_hazard` 初期配置を使用。
- 初期 oracle の 22,168 block、範囲 `(256,196,248)..(418,203,264)`、22 forced chunks、512 rays/tick、正しい world session / setup ID、player / inventory / 開始姿勢の `ready=true` を T0 前に照合した。harness は初期配置とリソース所有権の監視だけで、実行中の gameplay 地形更新はない。
- 公開 Tool 5 本の完全な定義を source catalog と照合した。各 T0 後から terminal までは既存の `Invoke-McmcpTunnelCapabilityGate.ps1` が公開 MCP だけを呼び、画面・手動入力・管理コマンドで補助していない。

## 直線 16 — 未合格

Action `fe50189f-a0c1-4987-b872-9ecc7a4d84c4` は 1193 tick / 約 59.7 秒で `failed / SAFETY_INTERRUPTED`、evidence `tunnel_server_denied` となった。

- 公開進捗: 14 cells / 14 moves / 29 server-confirmed breaks。`drop_collection=not_asserted` を維持。
- HP20 / READY、全 Action terminal、位置 `(271.583306764975,200,256.5)`。予定の終点は feet cell `(273,200,256)`。
- post-terminal world oracle: completed / prefix 14、partial 1、範囲外変更 0、未知な内部 state 0、`pass=false`。公開確認済み prefix と world の実測は一致した。

正常系の 32 破壊 / 16 進行は完遂していない。server denial の根本原因はこの試験から断定せず、blind retry や残りの別 Action による完了補完を行っていない。

## 床欠損 — 限定した安全停止の確認

Action `36e0f786-2c83-4236-8904-64cc56b6747c` は 298 tick / 約 15 秒、3 moves / 8 server-confirmed breaks の後、`failed / SAFETY_INTERRUPTED`、evidence `tunnel_unsafe_floor` で停止した。

HP20 / READY、崖手前 `(260.5346661155109,200,256.5)`。world oracle は 4 個の掘削済み column / prefix 4、partial 0、範囲外変更 0、`hazardPrefix=true`、`poseMatch=true`、`pass=true` を返した。先に 4 番目の column を露出させてから前進せず止まるため、掘削済み column 数 4 と移動済み数 3 を同一視しない。

両 run の正式 gate は terminal 検査で `tunnel evidence omitted a required field` を返した。そのため `gate-result` / 結合 `acceptance` は **failed** のまま保持した。後者の world oracle と短い安全停止が正しくても、正式受入全体の合格や直線正常系の合格とは扱わない。

## 保存と復旧

各 Action の終端と同一 session の READY を確認後にのみ、dev の read-only oracle と明示 `tunnel_finish` を実行し、`resourcesRestored=true` を取得した。通常 UI で保存して world を抜け、実行中 Action 不在を確認して container を停止した。

**両 run とも 66 ファイル / world 61 ファイルを全件 SHA-256 で復旧照合、不一致 0。lease を解放し、試験前から稼働していた Docker Desktop は保持した。**

秘密を含まない公開応答・oracle・復旧記録の archive SHA-256 は、直線 `7028066326042F044C21F36A9A88A754F61FE5125A7563C2A6142ED42AE9B59F`、hazard `D7C2CDF312A897E5B5031A34191557D9D306DD99F479D6504616E4B65A5FA1C3`。それぞれ remote / local の hash が一致した。

安全な直線 160、枝坑道、renderer 欠測、ACK 不明、容量・道具不足、Esc / OFF は今回は実機試験していない。未公開 legacy opcode の修復を他 API の配布合格へ推移させず、本 PR の正常系不合格と残件を保持する。
