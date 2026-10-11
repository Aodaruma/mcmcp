# v2移動・結果返却：隔離検証（2026-10-11）

Issue #84 / Draft PR #85。通常ゲームは地図アート担当が所有し、本試験では操作・差し替えを行わない。利用者の許可済みaod-mimoid Docker、固定fixtureだけで実施した。

## 実装と自動試験

- 同じ高さの1マス穴を越えるcardinalジャンプ、最大5blockの隣接落下を、共有観測へ混入しないAction固有の局所経路として追加。通常の入力所有・各tickの衝突照合を維持する。
- `max_fall_damage` はmove全体の累計。回復・再計画では返還しない。Vanilla属性・落下耐性・防護・耐性を予測し、packetの損傷原因と体力・吸収の減少を照合する。
- 短いActionはHTTP応答を開始してから最大20秒待ち、結果を返す。長いscript/範囲操作は既定でID。`agent_get_action` は既定でresultを含む。通信切断の取消を実TCPでも確認する。
- 小数座標と `coordinate_mode` を追加し、最後の位置合わせ・到着判定・結果座標に反映。
- 明示portal進入をNetherへ拡張。到着の安全が0.5秒持続することと入力解放を確認する。待機中のvanilla移動tickがAgent所有を復活させないよう修正。
- `a8c1283` で予測air後の破壊ACK待ち、`02e2856` で足場内部の衝突context・上端／長い柱の経路・自動しゃがみ抑止・End表面の可視分類を修正。`4ef943e` で馬の小数到着を低速入力へ変更。
- R10製品ソースは `664fa99a39a0876a4e9de31081d996ed2ea99caa`。ローカル自動試験はJava 1,803、harness 14、admin bridge 28（計1,845）、失敗なし。`verifyHarnessIsolation` とbuild、source checks（transport 16件・blueprint 4件・PowerShell mock/checkpoint等）が成功。[同一ソースのCI](https://github.com/Aodaruma/mcmcp/actions/runs/38091841419)と[PR CI](https://github.com/Aodaruma/mcmcp/actions/runs/38091843870)も成功した。
- `385aadc` で足場上の設置時に、保持していないしゃがみ入力の移動証拠を要求していた例外を修正。`664fa99` は長い足場柱の観測途中を上端の到着床と誤認しないようにする。

## 実ゲームで確認済みの経路

証拠はローカル `artifacts/v2-storage-arrival-20261011` と、隔離labの同日同名runrootに保持する。認証情報・private logは配布しない。

| 条件 | 結果 |
|---|---|
| 1マス穴、同じ高さ | 成功、1 jump、損傷0 |
| 2マス穴／頭上の低い穴 | jumpせずtick_limit、損傷0 |
| 3block落下、既定0 | 成功、損傷0 |
| 4block落下、既定0 | 落下せずtick_limit、損傷0 |
| 4block落下、上限1 | 成功、実測1 |
| 5block落下、上限2 | 成功、実測2 |
| 落下耐性IV、4block、上限0.52 | 成功、実測0.5200004578 |
| 落下耐性IV＋防護IV、4block、上限0.36 | 成功、実測0.3600006104 |
| 4block落下3か所、累計上限2 | 2回まで進み、3回目の前で停止、実測2 |
| 同じ3か所、累計上限3 | 成功、3 drops、実測3、予算消費3 |
| 穴越え後 `(307.85,106,305.15)`、tolerance 0.1 | 成功、実位置 `(307.7957500611,106,305.1748281851)` |
| 上記moveの同期返却 | 42操作・約2.15秒でsucceededとresultを直接取得 |
| IDだけを渡すget_action | include_result省略で結果を取得 |
| wait_timeout_ms=0、途中取消 | queued即時返却、cancelled終端、READY/jobなし |

R1は `nav-r1-*.jsonl`、R3は `nav-r3-*` に保存。R3 product SHA-256は `9cf9b5f38fad0fa83551d01fb8b138d60179a9f4f5e9412ec9ad22522c618ec3`。

## Portalの追試で見つかった問題

R3ではNetherへの新session到着と入力解放は成功したが、約10秒後にfixture外からの溶岩が流入して死亡した。到着自体の誤検出とは区別し、固定fixtureを密閉した。R4では安全確認待ちに `portal_arrival_input_owned` で停止した。AgentInputStateが待機tickでも追跡identityを再作成する原因を修正し、自動回帰試験は成功した。

R3の一部成功を最終候補の全項目合格とは扱わず、後続候補で追試した。初回End帰還のクレジット、MOD独自Portal、MOD独自損傷式、遠隔サーバーの大きな遅延は未確認。

## R5／R6での追試

R5は `6dd1c7c` のCI製品JAR（SHA-256 `2bbbe2b8e691ba412482e08bda0feababffc1bf7b0d8dcefae233eacfb2536ce`）。Nether到着は `04e8655b-a3f4-47ca-98c1-acf2e60d2ebd`、77操作／6.22秒で成功、到着安全・入力解放を確認し体力20。入口から離れた位置での帰還要求は近傍条件で拒否され、その後近い位置からの往復は成功した。通常床の同一マス小数移動・半ブロック小数Yも成功した。

R6は `4eaf4f1` のCI製品JAR（SHA-256 `bffdc4063665ec0e9e86bfcfc648490857998ed22637bcbcc96368b47634fd51`）。creative・spectatorの小数到着、gap後の小数到着、累計落下3を確認。creativeは77操作／3.887秒、Y103.411273（目標103.6、許容0.20以内）。spectatorは117操作／5.909秒、位置 `(308.665548,103.535186,305.287868)`。公開16ツールの定義は、実行中JARのcatalogと正規化後に完全一致した。

R6で新たに判明した不一致は、Endポータルが非MODEL描画のため観測に出ないこと、足場柱の上昇失敗、上面の移動／設置時の下降、馬の小数目標付近の往復による距離予算切れ。`02e2856`／`4ef943e` はこれらの修正候補であり、最終実機結果を以下へ追記する。ピストン上の破壊failedはR6の静止対照では再現せず、通常環境での因果は未確定。

remote Windowsでソースを組み立てた際、admin bridgeのowner-only token ACL試験1件が環境条件で失敗した。ACLを緩めず、製品JARはLinux CIの試験成功済みartifactを使用した。ローカルWindowsとLinux CIの28件成功とは分けて記録する。

## R8の修正確認と残った不一致

`4ef943e`、製品SHA-256 `27c88eb7897094aab0be2150b477f98f873ff6813cb8aa14b0be6986fe84cafb`。

| 条件 | 結果 |
|---|---|
| Endポータル観測→明示進入 | `2b1c1433-6a23-439f-9586-47b82ff8eec7`、21操作／2.91秒。到着・安全・入力解放true、End `(100.522617,49,0.5)`、体力20 |
| 足場3段の柱を上端へ上昇 | `ab37908e-66c9-4f22-b70d-9e0331f56cee`、50操作／2.54秒、Y104、体力20 |
| 馬 `(309.75,101,306.25)`、tolerance0.1、距離予算12 | `73917cfa-9140-40d2-9a79-456aa3541923`、51操作／2.53秒。実位置 `(309.760808,101,306.240248)`、残留移動false、体力20 |
| 足場上の粉破壊 | `96847178-5d74-4e2d-b77d-3287db81a312`、1個確定、Y104維持 |
| 足場上への再設置 | 入力を保持しない経路で誤って移動証拠を要求し、3操作で `block_runtime_failed`。Y104・体力20維持。`385aadc` で修正 |
| 足場上面の横移動 | fixture直後はY102まで下降しtick_limit。4個の足場の存在とY104静止を確認後の独立試験は33操作で成功し `(308.51827,104,303.5)`。初回の失敗は保持し、準備直後の同期との因果は断定しない |

R8でも公開16ツールと実行JAR内catalogは完全一致した。R9単独での検証は行わず、長い柱の修正も含めたR10で追試した。

## R10の追試

CI製品JARのSHA-256は `d7a68deeadefab418221de2289e6061fc7a934d226ae02280973e85e8318afc2`。上記CI artifactと同じJARを使用する。

足場試験の前提確認で、行動開始前に上端が消失し、Y104から103へ落ちて足場itemを拾った状態を検出した。固定fixtureが初期distance=7の足場を一括配置していたため、支えの情報の伝播より先にscheduled tickで崩れる可能性があった。harnessのみ `a27bc4d` で柱distance=0、横distance=1/2/3を明示し、通常の更新後に安定した状態を確認して試験する。製品JARは変更しない。R8の横移動失敗との因果をこれだけで断定せず、前提不成立の試行と安定したfixture上での結果を分けて保持する。

- 独立に再準備した足場上面の横移動は2回とも33操作で成功。ID `2ad7de3f-1e97-448e-8ba3-8ea97cf5ec69` / `c78eb98b-a3f6-474f-a29c-bc43f6c8f3d2`、到着 `(308.518272,104,303.5)`、体力20。3段の上昇 `b339c0f8-e948-4091-83d7-2d3269e02206` と、そのまま横移動 `1a631eb9-420a-473e-bdc8-ea3dfa633f3c` も成功した。
- 8段の柱も `ab922427-9ac0-46fc-8801-39492153f603`、91操作／4.62秒でY109まで成功。そのまま横移動 `926ff663-26e6-459f-9e3e-de1aebf65650` は52操作／2.64秒で `(308.518272,109,303.464102)`、体力20。足場上で粉を破壊 `4badd170-ed7a-41d6-bf77-f3df61d32186` →新しいpiston_head上面観測→灰粉を設置 `0be8c134-3a53-45ee-a803-8de859efdd2b` はそれぞれ1個確定、設置1秒後もY104・体力20を維持した。
- End進入 `92f6a429-0e1d-463a-9929-c2d1ceca4729` は到着・安全・入力解放を確認。馬 `5d408a6b-7fb1-449b-bc00-974203017904` は小数目標へ成功、残留移動false。gap後の小数到着 `6512f4ad-ef3d-44e6-95fd-c2e2cb76cce8` と累計落下3 `c5cf0493-ea4b-452a-953f-b44d5e874403` も成功（損傷3・体力17）。公開16ツール全定義と製品内catalogは一致。
- ボート `0ac79dbc-063e-48e2-9cac-1a45f6c5db90` は小数目標 `(309.25,101.523262,306.75)` の周囲を旋回し、250tickで停止。水平誤差約0.151、体力20、残留移動true。水上での降車は `dismount_landing_unverified` で拒否されたため再送せず、次の固定fixtureで状態を戻した。
- 通常レール上のトロッコ `e9fc7429-f02c-4f67-b474-ada6cd6df3e4` は目標 `(307.25,101.0625,306.5)`、tolerance0.1に対し、最終 `(307.214355,101.0625,306.5)`。近くても速度が残り `tick_limit`、残留移動true、体力20。降車は成功。この精度での停止は未解消の制限として残し、成功判定を緩めない。

## R11：ボート精密移動

製品ソース `bdb8dad78375c6672a335d85162c6ecc1b7815f2`、SHA-256 `fcf343b396f100aa60fcc73ba5c531b348c3f79bcfa0707450ce3ccce93a7c9b`。Vanillaの水上での旋回減衰と後進の小さい加速を使い、目標付近を短い後進入力で位置合わせする。速度や座標を直接書き換えない。

Java 1,804・harness 14・admin bridge 28、計1,846件が成功（失敗・skipなし）。32種類の開始位置／向きから線形・角度の慣性を含める回帰試験を追加。必須Gradle全task・build・隔離確認、[同一ソースCI](https://github.com/Aodaruma/mcmcp/actions/runs/38092463754)と[PR CI](https://github.com/Aodaruma/mcmcp/actions/runs/38092446500)が成功した。

実機 `a53cd2f8-5a3d-4d69-8d1e-b29aa09e7426` は148操作／7.39秒で `vehicle_no_known_path`。車体中心Z305.893は水上でも、経路の開始bodyをセル中心Z305.5へ移動してから確認することで、存在しない岸との衝突を判定していた。体力20、残留移動false、降車は成功。R11を検証済みの配布物とは扱わず、`e51794d` で最初の区間を実際の車体位置から照合するよう修正し、水際AABBの回帰試験を追加した。

## R12：実位置からの車体経路

製品ソース `e51794d333bc03ca3bcf13e82dd59a1f14153dde`、CI製品SHA-256 `f6b2fa6fa63bffb846063dc4641e08823a8c501d54ca9607794895135438ccb2`。Java 1,805・harness 14・admin bridge 28、計1,847件が成功、必須Gradle全taskとbuild・隔離確認も成功。[同一ソースCI](https://github.com/Aodaruma/mcmcp/actions/runs/38092924125)と[PR CI](https://github.com/Aodaruma/mcmcp/actions/runs/38092926851)も成功した。

旧runの復元後、新しい `20261011-v2-navfinal` runrootとbaseline・期限付き独占leaseで追加試験を行う。旧baseline／復元済みsnapshotは変更しない。

実機 `4f2bd926-50dc-40ad-b1b2-cb0f885b9729` は78操作／3.90秒で `vehicle_coasting_corridor_unverified`。位置 `(308.777980,101.523260,305.855836)`、体力20。回頭時の通常LEFT/RIGHTだけでも前進加速が入り、岸へ弧を描く動きを安全判定が止めた。成功にはせず、後続試験を増やさずに66/66ファイルを復元、Docker停止・lease解放を完了した。

## R13：回頭時の並進抑制

製品ソース `00a41fb51db3bcae83fc82a496837e7c7e397725`。回頭中はBACKを交互に併用し、旋回入力に付随する前進加速を抑える。通常入力と慣性・衝突判定は維持する。静止／初速あり64開始条件の回帰と、水際へ大きく流されない条件を確認。Java等1,847件と必須Gradle全taskは成功した。

追加試験はさらに独立した `20261011-v2-navfinal-r13` runrootとbaselineを使用する。[同一ソースCI](https://github.com/Aodaruma/mcmcp/actions/runs/38093652578)と[PR CI](https://github.com/Aodaruma/mcmcp/actions/runs/38093655594)は成功。試験するCI製品JARのSHA-256は `213e9d22fb981fc86c40735db1752f7149b80d00cb83e39aade752b52e744889`。

| 最終候補の条件 | 結果 |
|---|---|
| ボート `(309.25,101.523262,306.75)`、tolerance0.1 | `5522c471-7d2e-4db3-adeb-f5f8c447f6bf`、173操作／8.65秒、succeeded。実位置 `(309.224891,101.523260,306.729654)`、残留移動false、体力20 |
| 馬 `(309.75,101,306.25)`、tolerance0.1 | `cbd7d58a-6b80-4c6b-8e45-dd4fd5bd3d84`、49操作／2.45秒、succeeded。実位置 `(309.760849,101,306.240501)`、残留移動false、体力20 |
| 徒歩で短いlook | `8f46ec85-d320-4ef8-ac58-8ccd78b61427`、succeeded。先行する騎乗中lookは `SAFETY_PRECONDITION/unsupported_locomotion` でID生成前に拒否され、その記録も保持 |
| 足場上の粉破壊→新しいUP面観測→灰粉設置 | `8bb1acb5-63b4-4175-a89e-55f3e81fcf3d` / `5825d0ce-4af6-4ccd-9670-a7020c7997ef`、各1個確定。設置1秒後もY104・onGround・体力20 |

公開16ツールの全定義が、最終製品JAR内のcatalogと一致した。先行ケースをすべて最終JARで再実行したとは扱わず、各表のソースと製品hashに結果を結び付ける。最終runのcase ID・座標・復元証跡は、非公開runrootの `validation-summary.json` に保管した。

最初のDocker起動は一時ソケットのWindows error 1920で失敗した。ゲーム変更前の66ファイル未変更を確認後、一時IPCのみのdirectoryを可逆退避してengineを復旧し、上記試験へ進んだ。[復旧と再発時の確認手順](20261011_docker_runtime_socket_recovery.md)を参照する。永続Dockerデータや設定は変更しない。

## 接続と復元

06:34 JSTにSSHのCloudflare認証が失効。既知の空lock退避と単独quiet loginではまだ再認証できず、許可済みaodのtmp診断チャットからhostname・サービスRunning・baseline66件・対象container稼働を確認した。通常ゲームには触れない。

R11終了後、旧 `20261011-v2-storage-arrival` runは元のworld・設定・JARの66/66ファイルをhash一致で復元し、container・Docker Desktop停止とlease解除を確認。維持用SSHも `LEASE_COMPLETE` / exit 0で終了した。R12追加試験の新baselineも66/66一致で復元済み。

R13も08:28 JSTまでに独立baselineの66/66ファイルをhash一致で復元した。worldは61ファイル、製品JAR集合も元どおり。対象container停止、Docker Desktop終了、engine接続不可・残存プロセスなし・lease lock不在と `LEASE_COMPLETE` / exit 0を確認した。通常ゲームは変更していない。Dockerの退避済み一時IPCと検証証跡は保管し、削除や永続設定の変更は行わなかった。
