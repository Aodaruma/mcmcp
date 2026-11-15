# 有限反復入力・面ガード・取消の実機確認（2026-11-15）

PR #35 / Issue #34 の入力保持を、許可済み隔離Dockerで確認した。通常profile・元ワールド・現場JARには操作していない。

## 候補と実行条件

- 製品source: `972b1c916a67da961caa6cb1f25f3912c95649b6`。
- 製品JAR SHA-256: `8AA3298BC86C4D772BB97BEB33E03363F25DB332059B05C01FB160E7EFEEC806`。
- dev-only harness SHA-256: `355DB948DC2686E49683D4E68A0768C56AB984874DDA28888F9FA65A20B07CEB`。
- 既存の同候補JARをhash照合して再利用した。公開`tools/list`の5ツール全定義も、対象sourceのcatalogと完全一致した。
- 既存harnessの`cobblestone_generator`をT0前の一度だけ実行した。以降の再生成はVanillaの水・溶岩更新によるもので、fixture tick mutationや操作中のblock置換はない。
- 初期工具は耐久250・消耗0の非エンチャント鉄pickaxe。Survival、HP20、固定の安全な立ち位置で実行した。
- 各試験フェーズ中は公開MCPだけを呼び、画面観測・物理入力・admin補助を行っていない。終了後の状態確認も公開MCPを用いた。

## 結果

| ケース | 結果 |
| --- | --- |
| 配送済みcobblestone上面へ`face_known_block_face` | 成功、15tick、移動0 |
| 実際の上面照準に対して、同じblockの観測north面をstrict guardへ指定 | 1tickで`SAFETY_INTERRUPTED / bounded_input_target_face_changed`。interaction・破壊・設置0 |
| 同じ観測north面を`match_face:false`、`repeat_target:true`で90秒保持 | 成功、正確に1,800tick、移動・camera変化0。対象が自然再生成する間も保持を継続した |
| 稼働中repeatを公開`agent_cancel_action`で取消 | 約3.23秒・53tickで`cancelled / CANCELLED_BY_CLIENT`、READY・HP20へ復帰 |
| 新しい有限Actionを150秒・3,000tickで一度だけ実行 | 成功、正確に3,000tick、約150.49秒、移動・camera変化0 |
| 150秒Action後の公開所持情報 | pickaxeのserver同期damage 61→161（100増）、所持cobblestone 44→119（75増）。工具は同じ非エンチャント鉄pickaxeを維持 |
| terminal後3秒の再確認 | READY、工具damage不変。保持終了後に採掘が続かないことを確認 |

正しい予算での面ガード・取消フェーズは15チェック/12要求、追加150秒フェーズは134チェック/130要求が成功した。すべて同じworld session・HP20を維持した。

## 初回試験の補正と会計の意味

初回strict試験では非repeat Actionへ60 interactionの予算を指定し、通常上限によって`INVALID_ARGUMENT`となった。これは面ガード拒否の証拠に使わず、1 interaction / 1 breakの有効な予算へ修正して上表の実face不一致を確認した。

90秒Action自体は成功したが、試験側の`progress.interactions > 64`という期待値は成立しなかった。値は1だった。遅い通常採掘は`continueDestroyBlock`経路で次のblockへ継続でき、この値を新規入力開始回数・破壊数・回収量と同一視できない。追加150秒Actionも`interactions=0`、`blocks_broken=0`、effects 0件で、実際の工具消耗と所持増加とは別の会計である。元の期待値違いを保存記録から除外せず、製品の時間前停止とは区別した。

今回、64を超える実作業の継続は150秒のserver同期工具消耗100・所持増加75で裏付ける。これを「100回の新規startを計測した」とは表現しない。1 client tickあたり1 startと4,096 startsの上限回帰は既存単体試験の範囲である。

9月13日の226 starts成功報告は、今回の計測へ足していない。時間指定版commit `856c90c` と対象HEADの間では、`AgentInputState`、`MultiPlayerGameModeBoundedInputMixin`、`MinecraftBoundedUseMixin`、`BoundedInputLease`のGit blobが同一である。`BoundedInputExecution.reserveInputStart`も文字列単位で同一（SHA-256 `576c1d80a380646650ca523c03e491efa38f30dfa4a8be511ba7a79629d51671`）と照合した。後続の面省略・固定診断等の変更と、start会計の同一性を区別する。旧報告JARとcommitの対応を新たに再現buildしたものではない。

## 保存・復旧

45分の排他leaseと期限時の自動復元処理を使い、開始前にworld 61ファイルを含む66ファイルをhash付きで保全した。終了時は通常SaveQuit後にworldなし・稼働Actionなしを確認し、対象containerを停止した。

元のworld・JAR集合・options・instance・client設定は全66ファイルが事前SHA-256と一致し、worldの61ファイルの個数も一致した。leaseは解放済み。Docker Desktopは試験前から稼働していたため、その状態を保持した。

初回の期待値違いを含む全フェーズ結果、MCP記録、live catalog照合、保存確認、復元receiptの9ファイルを証拠ZIPへ保存した。送受信側SHA-256は `B8EF2789B158E1499FEC3439881313620DADD909495C8B3631F921D45438F91D` で一致。秘密値・認証設定・元/試験後worldは含めていない。

今回は製品・harnessコードを変更しておらず、親側の実機試験PCのRAMを確保するためローカルGradleを再起動していない。source/CI検証は対象PRの記録と、後続のmain追従時のCIで管理する。

雪の瞬間採掘固有の横面配置、Esc/OFF/手動の手持ち変更、world変更中の保持、弓・飲食の実使用継続は今回の故障注入対象外である。面ガードの共通経路・自然な消失/再生成・時間指定・公開取消・終端解放を確認した範囲と、これらの残る試験を分けて扱う。
