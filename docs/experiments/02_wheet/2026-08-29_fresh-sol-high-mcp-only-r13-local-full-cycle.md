# 元の小麦畑でのfresh gpt-5.6-sol high full-cycle評価 R13（local PC、2026-08-29）

- 実験ID: `wheat-original-v1-r13-local-full-cycle-sol-high`
- artifact: `C:\Users\aod\Documents\mcmcp-eval-artifacts\2026-08-29-r13-local-full-cycle-sol-high`
- baseline: `wheat-original-v1-r13-local-3ac44f4-66d3382f`
- repository commit: `3ac44f435642451c74656e1a6b664570c31988a0`
- production JAR SHA-256: `EC420381A45444FF4D13A5779A3E97DB4F4A4864F608EF3303901E95C0F3D594`
- model: `gpt-5.6-sol`、reasoning effort `high`
- prompt profile: `full-cycle`
- T0: `2026-08-29T20:52:47.2740011+09:00`
- turn完了: `2026-08-29T21:09:19.5076237+09:00`
- runner terminal: T0から17分13秒
- T0からturn terminalまでのoperator介入: なし
- 判定: **未達（小麦7 / 64、耕作44 / 72、現在播種34 / 72）**

## 条件

このPCのPrism Launcher profile `MCMCP-Validation`、save `tester (1)`を使用した。固定空中arenaではなく、saveに既存するoak fence囲いの畑72区画を対象とした。

T0前にprofile全体を次へ退避した。

`C:\Users\aod\Documents\MCMCP-Validation-Baselines\2026-08-29-pre-r13-eval-guard-monitor\MCMCP-Validation`

fixture `wheat-original-v1`を適用し、playerを空inventoryと開始poseへ戻し、chestへnetherite hoe 1と小麦の種64を配置した。既存畑の72区画はdirt、cropなし、gate閉鎖とし、落下itemを除去した。fixture SHA-256は`66d3382fa788edcb00dd767ab0495d4a16ff6af23b5783ded1171fee1d78e782`、評価world sessionは`eb211516-79da-4740-bd44-b33bc0ff0b50`だった。成熟待機だけを短縮するため`randomTickSpeed=3000`を復元付きleaseで適用した。

preflightはready、非pause、world / observationあり、inventory空、512 rays/tick、visible entity 0、Action idleを満たした。production promptは次の一文だけで、座標、Action例、過去runのcontextは与えていない。

```text
チェストに小麦の種と鍬が入っています。これを取り出し、この畑の区画にある耕作可能な土をすべて耕して、すべてに小麦の種を植えてください。成熟後はすべて収穫して植え直す工程を、小麦を1スタック（64個）以上所持するまで繰り返してください。
```

T0からturn terminalまでは画面観測、computer-use、keyboard / mouse、shellによるgameplay補助、追加prompt、admin bridge操作を行っていない。評価モデルに見せたToolは公開5件だけである。

## 結果

trace監査は921 message、bridge 575 record、dynamic request 188件を検査してPASS、violation 0だった。evaluation-turn leaseも`turn_completed`で正常解放され、`inputs_released`、`input_owner_none`、`all_actions_terminal`、`release_http_confirmed`、`process_identity_bound`はすべてtrueだった。一方、production goalは未達であり、`full-cycle`を1周完了したrunではない。

| 項目 | 結果 |
|---|---|
| chest確認、鍬・種取得、gate開放 | 成功 |
| 上側36区画の耕作 | 36 / 36 |
| 下側36区画の耕作 | 8 / 36 |
| 現在播種済み | 上側28、下側6、合計34 / 72 |
| 収穫 | 最初の成熟8株だけ |
| 再播種 | 収穫済み8区画を含め未完 |
| 最終inventory | netherite hoe 1、wheat seeds 38、wheat 7 |
| 全Action terminal | 成功。active Action 0 |

Action開始は57試行、受理41、受付拒否16だった。受理Actionは38成功、3失敗で、41件すべて1回の`agent_get_action`でterminalを取得した。40 Actionが1 node、1 Actionだけが2 nodeであり、受理41 Action全体でもtop-level nodeは42に留まった。

| Tool | model request | MCP forward | deadline拒否 |
|---|---:|---:|---:|
| `agent_get_state` | 40 | 39 | 1 |
| `agent_get_observation` | 50 | 49 | 1 |
| `agent_start_action` | 57 | 57 | 0 |
| `agent_get_action` | 41 | 41 | 0 |
| `agent_cancel_action` | 0 | 0 | 0 |

MCP forward 186件は169成功、domain error 17だった。受付拒否16件は`NO_KNOWN_PATH` 6、`TARGET_UNKNOWN` 5、`PROGRAM_BUDGET_UNPROVABLE` 4、`INVALID_ARGUMENT` 1である。runtime失敗3件は、plant batchの`PATH_BLOCKED / batch_aim_raycast_unavailable` 1、till batchの同失敗1、till batchの`BUDGET_EXCEEDED / batch_target_budget` 1だった。

## 所要時間の分析

T0からturn完了までは992.234秒、16分32.2秒だった。runner全体の17分13秒には、T0前5秒、turn後のtrace監査35秒、終了処理約1秒が含まれる。したがって17分表示のうち、production goalへ使えたのは16分32秒である。

MCP HTTP処理やMinecraft Action自体が時間の大半を占めたわけではない。

| 区分 | 累積時間 | turn比 |
|---|---:|---:|
| MCP forward 186件のHTTP占有 | 16.725秒 | 1.69% |
| 41 Actionの実行tick | 1,498 tick、約74.9秒 | 7.55% |
| 上記以外のmodel orchestration等 | 少なくとも約900.6秒 | 90.8%以上 |

MCP完了から次のMCP開始までの185 gapは累積937.2秒、平均5.07秒、中央値4.90秒、p95 10.16秒、最大22.16秒だった。5秒超が90件、10秒超が12件あった。`agent_get_action`の`wait_timeout_ms=25000`は40件で指定されたが、実HTTP待機は合計8.05秒、中央値6.9 ms、最大1.83秒だったため、25秒pollは主因ではない。

公開app-server eventではcompleted reasoning itemが164、公開monitorではまとめられた推論要約eventが100、commentaryが8だった。細かな`state / observation -> 判断 -> Action組立て -> terminal確認`を直列に繰り返したことが、最大の全体ボトルネックである。

### 工程別

| 工程 | 時間 | 主な結果 |
|---|---:|---|
| T0から鍬・種取得 | 55秒 | supply取得完了 |
| 畑mapping、入口から初回耕作 | 75秒 | 区画認識と入口処理 |
| 上側作業 | 389秒 | 36耕作、36回播種後に8収穫、最終28株 |
| 下側作業 | 457秒 | 8耕作、6播種だけ |
| deadline判断とfinal | 16秒 | 最後の2 readを安全headroomで拒否 |

上側では耕作・播種72 mutationを389秒、1 mutation平均5.4秒で進めた。下側では14 mutationに457秒、1 mutation平均32.6秒を要し、約6倍遅かった。下側だけでread 44件、navigation 12試行、till batch 7試行を使い、navigation受付失敗5、till受付失敗3、runtime失敗2が発生した。公開要約にも水路横断、dirtの可視性不足、成長済み作物によるcamera / ray occlusion、位置調整が反復している。最大の局所ボトルネックはこの下側区画の経路・視認・照準証拠の再取得である。

最初の8株は播種terminalから約31秒で成熟を観測し、その間も別区画を作業していた。明示的な`wait_ticks`も1秒相当1件だけであり、作物の成長待機は主因ではない。8株収穫後は`collect_visible_item_batch`が2回`NO_KNOWN_PATH`となり、single collect 3件へfallbackするまで約62秒を要した。

### 遅延要因の優先順位

1. 188 Tool requestとほぼ1 nodeずつの41 Actionへ細分化されたmodel側の直列orchestration
2. 下側区画でのnavigation、visibility、camera occlusion、aim証拠の再取得
3. Action開始57試行中16受付拒否と、受理後3失敗に対する再観測・再計画
4. mutation batch上限と、mutation後のfresh observationによる細粒度化
5. drop回収batchのsafe route不成立とsingle fallback
6. 17分deadline内のterminalization headroomによる最後の2 read拒否

17分は現状の実効速度に対して不足している。ただし単純延長だけでは、初回の全区画耕作・播種すら終わらなかった根本原因を隠す。次の性能改善は、公開DSLのmulti-node / batchを1 Actionへまとめること、block座標をLLMにnavigation座標へ変換させず安全な接近anchorを返すこと、filter済み観測から区画単位の未処理targetを安定取得できること、`NO_KNOWN_PATH` / `TARGET_UNKNOWN`後の再観測範囲を限定することを優先する。deadline延長はこれらを実装するまで診断用の暫定措置として扱う。

## 終了後に判明した問題と修正

### 物理キー復帰

run terminal後、Minecraftで物理キー操作が直ちに復帰しない現象を確認した。評価leaseのterminal receiptが証明する`input_owner_none`はAgent所有入力だけで、Vanillaの物理`KeyMapping`再同期までは証明していなかった。

`InputIsolationController`は隔離中に毎tick`KeyMapping.releaseAll()`を実行する一方、隔離解除時に`KeyMapping.setAll()`を呼んでいなかった。このため解除境界で押されていたキーが、次のfocus / mouse-grab変化まで論理releaseのまま残り得た。Escだけは隔離中も緊急停止とVanillaの双方へ通す契約なので、他キーと症状が異なり得る。

commit `71b1ebd`で隔離状態の`active -> inactive`を追跡し、falling edgeで物理keyboard状態を1回再同期するよう修正した。同一client tick内のlease取得・解除も閉じるため、runtime pre-tickの前後で遷移を確認する。Esc契約とfail-closedな隔離条件は緩めていない。

### monitor文字化け

`live-monitor.log`はBOMなしstrict UTF-8として正常だったが、可視Terminalだけが文字化けした。redirectされた子`pwsh`がCP932でstdoutを出し、monitor hostがUTF-8としてdecodeした文字コード境界の不一致が原因だった。

commit `300cb59`でrunnerの最初のmonitor出力より前とhostのTerminal出力前に`Console.OutputEncoding=UTF-8`を固定し、hostのstdout / stderr decoderも同じencodingへ統一した。CP932を明示した実child processから日本語monitor行を出し、raw bytesをstrict UTF-8で完全復元する回帰testも追加した。

## 修正後の内部検証

- Java `test`: 724 / 724、failure 0、error 0
- Java `adminBridgeTest`: 21 / 21、failure 0、error 0
- Java `harnessTest`: 20 / 20、failure 0、error 0
- `gradlew test adminBridgeTest harnessTest verifyHarnessIsolation build`: `BUILD SUCCESSFUL`
- 公開monitor / evaluation lease self-test: 68 / 68 PASS
- app-server trace audit self-test: 60 / 60 PASS
- `git diff --check`: PASS

これらは入力遷移、byte-level UTF-8境界、schema / trace / harness isolationの内部試験である。ユーザー指示に従い、修正JARを使った追加の実ゲーム試験は本作業では行わない。

## 終了と復旧

turn terminal後にMCP操作を明示OFFとし、worldを正常保存終了してMinecraftとPrismを閉じた。run後のprofile全体は次へ保全した。

`C:\Users\aod\Documents\MCMCP-Validation-Baselines\2026-08-29-post-r13-full-cycle-timeout\MCMCP-Validation`

その後、開始前profileを同じ`MCMCP-Validation`位置へ復元した。復元後のSHA-256は開始前と一致する。

- `level.dat`: `383211CC4A92E8EEBC5ECBEEBF49BB4746D3F0CA989CE42C674E2E1BD6B7FE46`
- `instance.cfg`: `7459518355CD6601E3176DC7980E952A0D2389D395A573C24FBD9A2F5A75244D`
- `mcmcp-client.toml`: `8B086D9AAB339335623FD61674E1484C0D9FB4DCF3633C225267066D2AFDC449`

主profileが未達だったため、`short-regression`は実行していない。ユーザー指示により、R13で実ゲーム試験をいったん終了する。
