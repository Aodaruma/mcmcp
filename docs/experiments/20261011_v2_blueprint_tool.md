# agent_build と床施工の補充対応（2026-10-11）

## 固定候補

- 製品source: `c539f517f84fe9a99abd29c0c9bcc77333f80979`
- 成功CI: [38125642551](https://github.com/Aodaruma/mcmcp/actions/runs/38125642551)
- 製品SHA256: `8ccd97f963662e02d41608e433266f2ddb6cee7eb681e0f48d1a241fdd8936ee`
- JAR内catalog SHA256: `eec47278db5d69bc87dca7ed0d1f60f947407443b9686eb541e1332eb238024e`、公開17ツール。
- fixture source: `b16273280357613db27bb98b489bc172f3b61f59`。製品変更はなく、隔離harnessだけに`floor_refill`を追加。

全1,874件のJava/harness/admin試験、build、harness分離に成功。transport17件と設計図変換6件を含むsource checksも成功。候補14ケースの実機成功、隔離環境の復元、18:22 JSTの通常profileへの導入まで完了した。

## 実装範囲

`agent_build`はJSON/CSVを読み取って固定したデータから既存script実行器へplace/break命令を渡す。previewはworld操作なし。executeはpreviewのhash・world session・dimensionを確認し、共通のdelivery gate・進捗・取消を使う。入力のairセルから暗黙に破壊せず、breakには対象blockを明記する。

1,048,576 entriesまで読取り、1 batchは65,536 entriesまで。同じ材料の連続X方向を最大8セルへまとめる。中断した命令の一部が設置済みなら、設置数には保持し、再開indexはその命令の先頭へ戻す。再開時は既設の一致を観測して照合する。128×128×9の分割と座標の過不足は自動試験済みで、ゲーム内全量施工は未実施。足場の自動生成・材料の自動調達は含まない。[利用仕様](../PUBLIC_API_V2_BLUEPRINT_TOOL.md)。

床の追加修正は[床延長の記録](20261011_v2_floor_edge_approach.md)を参照。支持床の松明・rail・dustを通行可能にし、補充後のserver inventory payloadを照合する。serverで確認済みの床は、所持品同期やadvanceが未完了で止まっても部分結果へ残す。

## 隔離試験計画

新45分leaseでaod-mimoidの許可済みDockerを使用し、元66ファイル・world61ファイルをhash照合で復元する。通常profileには試験fixtureを導入しない。

- `floor_refill`: 実MODのnetherite backpackへrefill upgrade、black/grayのfilterと各64個を登録。旧R4と候補を同一条件で比較し、公開結果、実床、位置、手持ちとバッグ内の数量を照合する。
- 通常floorの8セル範囲と単発8回、支持床上に松明を置いた状態からの延長。
- 設計図preview、hash/session拒否、色替え、CSV、明示break→place、batchと再開、材料不足の部分確定。
- 各jobで体力・終端Action不在・静止を確認し、同じ不明な操作を自動再送しない。復元時間を確保し、期限を延長しない。

同一tick内の補充で新たなinventory packetが届かない場合、候補でも同期待ちが残る可能性がある。その場合は成功として扱わず、実際の確認状態と部分結果を記録する。実MODの補充間隔によって旧版で再現しない場合も、修正による改善とは断定しない。

## 準備時の修正

45分leaseは17:54:17開始、期限18:39:17 JST。既知のDocker Inference一時ソケット障害を記録済みの手順で復旧した。最初の補充fixtureはupgrade枠が未初期化で`IndexOutOfBoundsException`になり、製品操作を一度も送る前に停止した。バッグのinventory初期化をupgrade設定より先に行う修正をharnessへ加え、14件のharness試験・build・製品分離を再確認。元の失敗記録を残し、新しいfixture requestで準備成功を確認した。製品JARとlease期限は変更していない。

## 補充ありの旧版対照

同じ`floor_refill`、同じ起点から単発placeを最大8回、各300 ticksで比較した。補充元は実MODのバッグ内部で、手持ちへの補充もMOD自身が行う。

| 製品 | 結果 |
| --- | --- |
| 通常導入中のR4 | 1〜5回目成功。6回目は支持中心から約0.649で停止し、15秒のduration_limit。手持ち64・HP20・終端idle。7回目以降は未送信 |
| 候補`8ccd97f9…936ee` | 8回すべて成功。各confirmed_count=1、新床着地、手持ち64、HP20、inventory同期一致、終端静止 |

新候補で条件付き補充確認が機能することを確認した。全MOD・全通信遅延・inventory packetが省略される条件への一般的な保証ではない。

バッグ内部の初期数量は各64、設置後の手持ち64維持は実測したが、バッグ内部の終了時残数は取得していない。補充元の減少数量まで照合済みとは扱わない。

修正は自動補充MODの設定や移送を操作するものではない。通常の「材料が1個減った」という確認経路に加え、サーバー側の設置状態と操作ACKを確認済みで、使用中のhotbar枠を維持し、操作開始後に届いたその枠の受信データが現在のアイテム・個数・componentsと完全一致する場合に、補充後の同期完了として受理する。64→63→64で途中の減少を観測できなくても、この条件を満たせば次へ進む。数量が同じという理由だけ、古い受信データ、クライアントの予測表示だけでは成功にしない。

最初のblueprint previewはtorch数量32→33という環境変動が混入したため判定不能として保管。直前のtorch fixtureを撤去した際のdrop拾得が疑われるが、直接のitem追跡はしていない。所持品が安定してからの読み取り専用previewは全チェック成功。以降はfixture完了後に位置・所持品を連続確認してbaselineとする。実数量差を単なるJSON順序差として処理していない。

## 候補14ケースの実機結果

安定後preview、hash拒否、world session拒否、JSON色替え、palette、CSV、batch再開、明示break→place、shortage、取消、補充あり単発8回、torch支持床、補充なし8床一括、補充なし単発8回が成功した。

- JSON色替えは4床、next_index=4。
- batchは同一hash/sessionで2床ずつ、next_indexが2→4へ進む。
- 明示break→placeは破壊1・設置1。
- shortageは確定した2床を保持し、未完了命令の先頭index 0に留まる。
- 拒否ケースはworld・所持品の不変を確認。終端の静止・Action不在と体力も照合。

通常向けJARは上記固定候補を使用し、現場担当の停止・所有権引継ぎを経て差し替え担当が導入する。隔離の復元は別PCで並行し、通常ゲームにfixtureや検証入力を持ち込まない。

## 隔離環境の復元

18:14:33 JSTの最終receiptで、候補14ケース・27 actions、公開17ツール全定義一致、元66ファイル（world 61を含む）のhash一致復元、追加resource回収、Docker/WSL停止、lease解放を確認。期限18:39:17を延長していない。補充fixtureの初回準備失敗と最初のpreview判定不能は14成功ケースへ混ぜていない。

主なaction IDはJSON色替え `c6e20bbb-5458-422a-9ace-42853def9914`、palette `da433332-c821-40f9-b324-57daddec66d1`、CSV `51e61d87-091c-4902-85f0-38ba317a0864`、batch `b9cc62c2-c6fc-43dd-afd1-f00fe75f31fc`／`669d2aba-8d94-4128-8765-571bccb8f42e`、break→place `be8dba1d-b54c-46f3-82ff-f314a232be1c`、shortage `4e7b1950-daf6-4a2e-8dd6-33d74426ba4f`、取消 `aad31f0b-f85e-4441-a49c-9d7d132ef1d5`。

harness SHA256は`536a3996e3ce59569c698b3234ec9fd3fcf24d9dd9d2bcea31da74bb9e241e17`。全actionと復元の詳細はlabの`eval-artifacts/20261011-v2-agent-build-r1/final-summary.json`に保存。約1.88 GiBの再生成可能cache・診断資料の整理条件は同directoryのREADMEへ記録した。

## 通常profileへの導入

18:22:08 JSTに、Explorer UIから起動したPrismと新JVMで実際に開かれたJARのnative実体path・上記製品hashを照合。公開17ツールの全定義と入出力schemaがcatalogに一致した。READY・非pause・Actionなし、体力20・位置・経験値2582/level38を維持し、18:23に現場担当へ所有を返却した。旧R4をbackupし、製品JAR1個・テストMODなし・他MODと設定不変を確認。差し替え担当は施工Actionを行っていない。

現場の149,040 entries（床147,456＋松明1,584）、9,086,126 bytesのJSONも既存のscript resource rootへ配置し、同一ファイルハンドルでnative実体pathとhashを確認した。配置時点ではpreview・施工未実行。通常の短区間施工と全量作業は現場担当が所有する。導入証跡は`artifacts/v2-blueprint-r2-install-20261011/normal-verification.json`、`handoff.json`等に保存し、補助タスク4件も終了・削除済み。

所有返却後、現場担当から通常環境のnative `agent_run_script`によるindex 285〜290の6床連続成功を受領。親actionは`f0f6861d-7bf2-4970-ab76-9e766bd0389f`、所要13.5259秒、confirmed 6、final guard=true。松明のある支持床から出発し、自動補充ONのまま各新床へ前進した。現場記録`construction/native-000285-000290.result.json`へ保存し、施工台帳は床291・松明25へ更新された。これは現場担当の実行報告であり、本開発担当から通常ゲームへの追加入力は行っていない。

続いて同担当から、上記9,086,126 bytes・149,040 entriesの公開`agent_build` previewと、16-entry区間（床15＋松明1）の成功を受領。plan SHA256は`423ac33d24249ee4c833e16feab42542bbedecf80fe132639a46c3a98163cfbf`、親action `d1ae6e1c-358d-48f6-9553-4ddaa4ba0fba`、start=316、end=332、next=332、batch_complete=true、placed_blocks=16、already_matching=0、29.960秒。終端後は体力20・空腹20・READY・Actionなし。現場記録`construction/blueprint-000316-000332.preview.json`と`.result.json`へ保存され、台帳は床306・松明26まで確定した。以降の全量施工は現場担当が継続する。
