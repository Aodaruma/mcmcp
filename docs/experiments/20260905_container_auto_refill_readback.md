# 2026-09-05 自動補充とcontainer読み戻し

## 実機報告

`v0.1.0-rc.1`で、従来失敗した額縁付きチェスト `(164,67,-300)`、`(167,65/67,-304)`、`(167,65/67,-308)`、`(171,67,-313)` のinspectが成功した。最後の対象はplayerのx170・171の両方から成功し、`visible_entities_truncated=true`でも確認できた。W01/W02の54収納を確認。`(149,66,-300)`はTARGET_UNKNOWNが1回あったが、同じ立ち位置から反対halfのx150で成功した。

その後の整理ではサトウキビ80個を64＋16で移し、take/storeの4 Actionがconfirmedで成功した。松明64個を空のW01-C06-L03 `(147,66,-300)` へ一時収納するAction `8d55e56e-2071-4aa5-93a2-32a8aeed21f0` が、interaction 3回・tick 8616で`inventory_safe_open_hand_changed`、container_store UNKNOWNとなった。

以後の書き込みを停止し、独立した両halfのinspectで収納内の松明64個を確認した。保存記録では開始前とtick9485のplayer inventoryがともに64個で、収納は0→64個だった。利用者により、これは**バックパックの自動補充**と確認された。増殖として扱わない。バッグ内部の補充元数量は未観測である。

記録は実ゲーム担当の`work/sort-rc1-checkpoint.json`。baseline、54収納のbefore/current、5件の移動Action ledger、after stateを読み取りで確認した。コード担当はゲーム・MCP・Prism操作やJAR交換を行っていない。

## 原因と修正

- interaction 3回は初回open・QUICK_MOVE・readback openに対応する。開封済みのOPENING_READBACKでも`openHand.ready`を要求していたため、同じ選択slotへ補充された松明だけで、full-contentの厳密な結果照合より先に停止した。
- 手持ちの安全判定をAIMINGと送信直前に限定する。開封後はこの判定を評価せず、画面・slot・view所有権、session、cursorとserver full-contentの検証を継続する。
- 送り元64→64・送り先0→64は、現在の厳密な転送保存則ではconfirmedにしない。`container_ambiguous`に固定診断`container_transfer_readback_mismatch`を添え、UNKNOWN effectに実際のsource/destination前後個数を残す。自動補充を含む全体消費量や補充元を推測しない。
- `transfer_readback_observed`で読み戻し済みを区別する。未読のafter初期0は公開せず、観測した0は公開する。次の転送準備時にはflagをresetする。再試行や追加転送は自動で行わない。
- この結果には大チェストの個数も入るため、公開effectObservationのsource/destination上限を2,304→3,456へ整合させる。入力goalの上限と1回の転送量は維持する。

`agent_get_state.inventory`はclient threadで毎回player inventory全43slotを集計しており、過去のMCP frameのキャッシュではない。通常chest full-contentはVanillaによりplayerの通常36slotにも適用される。外側inventoryの集計はバッグ内部を含まないため、外側と収納だけを足して全所有数の増減とは解釈しない。

## 回帰確認

開封後の手持ち判定非実行、次回AIMING・送信直前の拒否維持、自動補充時のfalse confirmed防止、未読0と観測0、次clickのflag reset、UNKNOWNの前後個数保持、任意reasonの非反射、大チェスト個数のschema境界を確認する。

Java 25で`test harnessTest adminBridgeTest verifyHarnessIsolation build -Pmod_version=0.1.0-rc.2-SNAPSHOT`成功。unit 1118件、harness 13件、admin bridge 21件の計1152件で失敗なし。独立レビューで見つかった大チェストの出力schema上限も修正した。catalog raw SHA-256は`5734bd231a2c8c818d3aaf8b36640491cd85a1084f75df17c953559d8c4e22ca`へ更新し、evaluator2本とprotocolの固定値を同期した。

このJARは次の実機確認用の開発版であり、公開済み`v0.1.0-rc.1`のタグやJARは変更しない。利用者は自動補充をOFFにして既存JARで整理を再開しており、修正版の差し替え時期とゲーム操作は実ゲーム担当へ任せる。
