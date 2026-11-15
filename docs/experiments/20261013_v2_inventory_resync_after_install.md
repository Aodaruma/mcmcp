# R24導入後の所持品同期不一致と公開APIによる復旧

2026-10-13。施工担当が124行末で通常profileへR24を導入した後、新session `47d989ec-6d8c-4eb8-95e7-c7431f917b8f` の診断が `inventory_sync.reason=slot_mismatch`、不一致22枠になった。この開発チャットは通常ゲームの照会・入力を行わず、保存済み記録とR24のコードを確認した。製品ソース・JAR・凍結成果物は変更していない。

## 確認した状態

installerの `artifacts/v2-existing-placement-r24-install-20261013/after-state.json` と施工担当の `_PLAYING/map-art-MFwtpM21wWc-3x3/construction/r24-root-resume-20261013.json` を読み取り確認した。いずれも親mcmcpディレクトリからの相対path。

- player menuの記録は存在し、live／snapshotとも46枠。`snapshot_missing` ではない。
- server／live cursorは空で一致。READY・Actionなし。
- 表示される先頭8不一致はmenu slot 9～17の通常所持枠。診断は最大8件なので、全22枠がこの範囲に属するとは断定しない。
- 携帯ネザライトバッグはhotbar slot 8にある。人間の停止中に所持品・経験値・食料等も変わっているが、不一致の原因を人間の操作や特定MODと断定する証拠はない。

## 公開操作で復旧する手順

現在の操作担当が、所有返却・同session・READY・Actionなし・画面なし・cursor空・安全な接地静止とバッグ枠を確認した後、対応バッグの内容確認だけを1回実行する。

```json
{"operation":"inspect","target":"storage","storage_slot":8,"storage_item":"sophisticatedbackpacks:netherite_backpack","max_ticks":600}
```

ツールは `agent_inventory`。slot 8は今回の現場値であり、一般手順では現在の所持枠を使用する。1つのActionの完了とmenu解放を確認し、続いて `agent_get_state({"sections":["inventory"]})` を取得する。移送・整頓・投棄・クラフトは復旧のために要求しない。

コード上、`MinecraftV2StorageDriver.begin()` と `MinecraftKnownMenuPort.preflight()` はplayer所持品全体のmatchedを開封前提にしていない。既知providerによる通常の開封で受信したserver full payloadから、R23以降の `KnownMenuProfileSupport.recordStoragePacket()` が各player枠を `PlayerMenuSyncSignals.menuSlot()` に反映する。containerの実体とinventory内のindexを照合し、clientの現在値をserver証拠へ昇格させずに更新する。Vanillaチェスト等の受信にも `PlayerMenuSyncSignals.full()` の同様の写像がある。

これはplayer menu全46枠を強制再取得する専用refreshではない。バッグmenuに含まれない装備・offhand・craft枠やcursorの不一致、完全snapshotの欠落を必ず直すとは限らない。`last_full_packet_tick` はplayer menuのfull受信時刻なので、バッグ経由の更新で変わらなくても異常ではない。更新後の `snapshot_revision`／`snapshot_tick` と一致結果を確認する。

通常のstate取得とplayer対象のinventory inspectは読み取りのみで、serverへの再同期要求ではない。`craft`、数量 `move`、`sort` は既存の全枠一致を開始条件とするため、同期復旧目的で呼ばない。

## 施工再開の判定と現場報告

今回の復旧確認では、inspect成功・menu解放に加え、同session、READY・Actionなし、`active_menu_is_player=true`、`reason=matched`、`mismatch_count=0`、`cursor_matches=true`、両cursor空を確認する。そのうえで現在の支持床・材料・再開座標と健康状態を新規観測する。全所持品matchedは施工開始に常に要求する一律のコード条件ではなく、今回の不一致解消を確認する基準である。

施工担当から、Action `43c318b4-2c6e-42e1-8ee7-6bbd05e723d6` の内容確認が成功し、`release_confirmed=true`／`release_pending=false`、直後の診断がmatched・不一致0・両cursor空・Actionなしになったとの報告を受領した。take／store／craftは行っていない。HP20・食料20・接地Y80、既設終点と次のair、複数読取り間の静止も担当が確認している。追加の人間操作や強制再同期を必須とはせず、次の自然な6床を確認して有限施工を継続する方針を返答した。

この復旧成功は担当からの報告であり、この開発チャットによる通常MCPの直接確認ではない。元の22枠不一致が生じた原因・再発防止の修正完了とは扱わない。失敗や不一致継続時には同じ操作を無条件で再送せず、Action結果と最新の不一致配列・打切り表示・snapshot revision/tickを保存する。
