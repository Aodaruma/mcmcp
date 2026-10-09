# v2優先項目・外部データの検証記録

2026-10-09。Issue #84、Draft PR #85。この記録はリモート候補R2時点のもの。その後このPCで最終候補R7の13ケースが成功した。[最新の実機記録](20261010_v2_priorities_local.md)を参照。**このリモート環境の66ファイルの復旧はSSH timeoutのため未確認。**

## 候補と隔離環境

- `priorities-r2` main JAR SHA-256：`5ff49c327e96285d7135ce0087f5b2dac15d91d23e6077ec29bb50485acdd51c`。
- harness SHA-256：`931c0ba6e774c80dc6e8a05444eb0e16f0420f28523629eeafeef2aac87a07e4`。
- 許可済みaod-mimoid Docker、`MCMCP-Validation`、Minecraft 26.2／NeoForge 26.2.0.59。
- 通常「くらふとぶ！」profileは操作していない。computer-useとsubagentは使っていない。
- 開始前にworld 61ファイル、instance設定、options、client config、元JAR 2個の計66ファイルを退避し、hash manifestを保存。
- 固定fixtureを追加し、名前付き原木・石、名前付き／損傷した釣竿、にんじんをサーバー側で配置できた。通常のMCP有効化を省略する処理はない。
- 起動後の公開レシピ照会で、板材・棒・かまど・ニンジン付きの棒・ケーキのv2対応を確認。これは製作成功の証拠とは区別する。

## 自動検証

`gradlew test harnessTest adminBridgeTest verifyHarnessIsolation build` が成功。
Java 1,671＋harness 14＋admin bridge 28＝1,713件、failure/errorなし。
`tools/check-source.ps1 -SkipJava` も成功。Python transport 15件、blueprint 4件、PowerShellのbuilding／capability／v2 orchestration試験を含む。

追加した主な試験：

- 実componentsの比較。同じ整数hashでも異なるcomponentsを統合・ACK判定で同一視しない。
- 材料候補の重複を、残りの製作回数まで含めて割り当てる。
- 未観測を含む三値の複合条件、深さ・個数・型の境界。
- 外部JSON/CSVの厳密解析、相対path／リンク／容量、取消、147,456行の模擬反復。
- 有界な車両経路、整理対象slot、公開schemaの追加分。
- 147,456セル→36 shardの欠落なし変換、CSV改行、入力拒否、hash検証、checkpointの計画結合、上書き拒否。
- 実機runnerのinventory／water／vehicle／MOD break／resource引数を正本schemaへ照合。

利用者の許可によりCodex CLIのソースレビューも実施。降車直前の再検証、最終waypointの再検証、実際の入力開始からのpulse計時、CSV解析中の取消、読み込んだ施工plan自体のhash照合を修正した。

## 実機の残る確認

データ付き材料の2×2／3×3製作、損傷した釣竿、ケーキの戻り容器、取消時のcursor回収、満杯の整理・装備移送、実MODブロック破壊、水中上下／出入口／酸素退避、ボート・トロッコ・馬の乗降と移動、外部データscriptと小規模施工を予定する。

147,456セル全量のゲーム内施工、全MOD／ネットワーク遅延・拒否・切断の組合せは未検証。
隔離環境は検証待ちで起動中。終了後に元world・設定・JARをhash照合して復旧し、containerと今回起動したDocker Desktopを停止する。

ローカル補助artifact：`artifacts/v2-priorities-20261009`（親workspace、Git対象外）。remote側にも同名の実験ディレクトリを使用。認証情報は記録しない。
