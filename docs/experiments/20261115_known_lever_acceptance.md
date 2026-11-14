# 既設レバーの指定ON/OFF 実機確認（2026-11-15）

PR #80 / Issue #75 の有限`set_known_lever` Actionを、許可済み隔離Dockerで確認した。通常profile・元ワールド・現場JARは操作していない。

## 候補とfixture

- 製品source: `1e713121aa28ff6a60bc54a7696437634211d167`。
- 製品JAR SHA-256: `d6f6961691196590e5a28142bd6557e702be14e6ce64e4b1098d9867dfebf6c9`。
- Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25、既存の隔離Prism profileを利用した。
- dev-only adminの閉じたblock allowlistへ`minecraft:lever`だけを追加し、範囲外拒否と変更block予算の回帰試験を追加した。公開MCP、製品側の操作権限、任意commandの許可は変更していない。
- admin JAR SHA-256: `cd8a04b8b9ef96aeec0215bb00224485837f03ffe216d78979338dfb57399c9c`。製品JARにはadmin classを含めないことを`verifyHarnessIsolation`で確認した。
- `tools/eval/fixtures/known-lever`は既存の有限fixture形式を使う。孤立したsmooth stone床にfloor / wall / ceilingのレバー各1個を設け、所持品を空にしてSurvivalへ切り替えた。宣言変更上限400 block、固定のmutation/player boundsを維持する。
- fixture SHA-256: `c1f59635a75dfd25719763b0fa9ffa8998ea3ee2935a0717f4e93eea4f00f462`。

## 結果

各attachmentで「ON → 同状態ON → OFF → 同状態OFF」を順に実行し、12 Action / 145チェック / 73公開MCP要求がすべて成功した。T0前にfixture準備・MCP手動ONを完了し、試験中は画面観測・物理入力・admin補助を行わなかった。

毎回`agent_get_state`から最新frameを選び、`agent_get_observation`の可視レバーと完全stateを取得してから、その対象・stateを単独Actionへ渡した。操作不要のActionにも新しい可視stateを使い、以前の操作を盲目的に再送していない。

| attachment | ON | 同状態ON | OFF | 同状態OFF |
| --- | ---: | ---: | ---: | ---: |
| floor | 1.064秒 / 1操作 | 0.550秒 / 0操作 | 0.525秒 / 1操作 | 0.504秒 / 0操作 |
| wall | 0.707秒 / 1操作 | 0.497秒 / 0操作 | 0.513秒 / 1操作 | 0.508秒 / 0操作 |
| ceiling | 1.017秒 / 1操作 | 0.505秒 / 0操作 | 0.520秒 / 1操作 | 0.513秒 / 0操作 |

時間は開始要求からterminal応答取得までの外部計測で、通信と検証を含む。切替6件は各1件のCONFIRMED effect、操作不要6件はeffect 0件、全12件のUNKNOWN effectは0件だった。各Action後の新しい可視stateも希望した`powered`と一致し、毎回READY・非pause・HP20を確認。最後は3個ともOFFだった。

過去の実地OFF拒否・約513秒の経過は、今回の孤立fixtureと同一条件ではない。今回の3 attachment・12 Actionでは拒否や長時間待機は再現しなかったと記録し、元の現場条件の原因を特定したとは扱わない。

## ソース検証

Java 25で`test harnessTest adminBridgeTest verifyHarnessIsolation build`成功。admin fixture追加後もbuild・分離検査を再実行し、通常1,349件、harness 13件、admin bridge 28件、合計1,390件で失敗・error・skipは0だった。

## 保存・復旧

開始前に45分の排他leaseを取得し、world 61ファイルを含む66ファイルをhash付きで保全した。終了時は通常SaveQuit後、worldなし・稼働Actionなしを公開stateで確認した。最後の成功Actionは履歴として残るため、履歴の存在を稼働中と誤判定しない。

追加fixtureはsecure admin volumeから撤去し、対象containerを停止した。その後、元のworld・JAR集合・options・instance・client設定を復旧し、全66ファイルとworldの61ファイルの個数・SHA-256一致を確認した。leaseは解放済み。Docker Desktopは試験前から稼働していたため、その稼働状態を保持した。

MCP記録、12 Actionの結果、fixture適用、保存確認、fixture撤去と復元receiptを含む9ファイルの証拠ZIPは、送受信側でSHA-256 `297110F17DEC404216E967F8EEA0A08033692518B21F3DAB33E2786D98602508`が一致した。秘密値・認証設定・元/試験後worldは含めていない。

本試験は既設レバーの状態切替と無操作成功を対象とする。renderer欠測・配送期限失効・未知ACK・取消の分岐は既存単体/契約試験で確認し、今回その実機故障注入は行っていない。接続された装置全体の安全性・出力・自動OFFを保証しない。
