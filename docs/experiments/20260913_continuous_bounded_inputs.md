# 対象復帰を待つ有限長押し（PR #35）

Issue #34 / PR #35の有限長押しに、明示した反復モードを追加した。対象はMinecraft 26.2、NeoForge 26.2.0.59、Java 25。

以下は `5587409` の初版記録。開始回数の制約は末尾の「時間指定への改修」で更新した。

## 動作と安全境界

- `repeat_target` 省略・falseは従来の厳密停止。trueでは `max_repetitions:1..64` を必須とする。
- 入力取得時は正しい対象を要求する。以後の対象消失、MISS、entity、別の位置・面・block・stateでは新規入力を抑止し、同じ条件の復帰を同一Action内で待つ。自動照準や期限延長は行わない。
- 初回を含むVanillaの新規採掘・使用開始ごとにinteractionを記録する。採掘は同数の破壊枠を事前予約する。継続、cooldown、待機は反復回数へ数えず、次の開始が上限を超える場合は送信前に停止する。成功した破壊数や回収量を試行数から推定しない。
- 開始済みのAgent useだけは、対象不一致中もVanillaの物理useキーupで解除されない。次の使用開始は改めて対象一致と予算を要求する。terminal cleanup側のreleaseUsingItemにはこの例外を適用しない。
- 姿勢・手持ち・health・reach・Survival・Screen・world/session・安全条件を入力境界で再検証する。Esc、OFF、cancel、期限、watchdogは既存の解放経路を保つ。simulation pause後は更新済みのpause時間で元のactive-time予算を継続する。

公開JSON例と予算の指定は[クイックガイド](../MCMCP_Action_DSL_クイックガイド.md#対象の再生成を待つ有限長押し)に記載した。catalog、DSL、評価runnerの固定hashを同期した。簡易catalog validatorが扱わない条件式は、従来通りDSLのparser/validatorでも強制する。

## 検証

- `gradlew test harnessTest adminBridgeTest verifyHarnessIsolation build` 成功。main test 1,273件、harness 13件、admin bridge 21件、失敗0。
- `tools/check-source.ps1 -SkipJava` 成功。Python MCP transport 14件、capability gateのmock試験11組。
- 追加回帰試験: 厳密モード互換、反復条件・回数・予算不足の拒否、複数の待機/復帰、継続中の非加算、上限超過のラッチ、使用開始前の非所有、pause/watchdog、会計例外、試行数と確認済みeffectの区別。
- ASMでコンパイル対象のMinecraft JARを調べ、採掘開始の経路、使用cooldown後の予算確認、handleKeybindsの単一releaseUsingItem呼び出し、Mixin登録を検証した。
- 独立Codex CLIへ差分と関連コードを直接渡した静的レビューで、ブロッキング指摘なし。subagentsは使用していない。CLIの読み取り専用コマンドがWindowsの実行ポリシーで拒否されたため、ファイル操作を行わないレビューとした。

## 実機で残る確認

利用者の許可に基づく指定Prismプロファイルへの製品JAR上書きと、実機での合格判定は分ける。この記録時点では実機試験を実施していない。`release:verification-needed`を維持し、公開Releaseタグを付けない。

1. 起動ログで両Mixinの適用失敗がないことを確認する。
2. 同じ対象の消失→再生成を数回繰り返し、同じaction_idで再開することを確認する。
3. 別block、MISS、entity、別の面への照準中は新規採掘・使用が出ず、元の対象へ戻すと再開することを確認する。既定モードは停止したままにする。
4. 弓・飲食の開始後に一時的に照準を外し、use継続と終了後の新規開始抑止を確認する。
5. 反復上限、元の時間/tick期限、Esc、OFF、cancel、手持ち変更、world変更で入力が解放されることを確認する。

道具の自動交換、耐久保護、回収量保証は含まない。

## 9月13日の導入記録の訂正

03:52 JST の導入完了報告は誤りだった。MSIX アプリから起動したシェルでは、プロファイルの同じ AppData 論理パスがアプリ専用の LocalCache へ転送されていた。実際の Minecraft は旧 JAR（SHA-256 `171c9b4bd993977ef5c0cf0102e5833c7c14ccef37592e677b92693bffc874ac`）を使用し、公開 catalog に `repeat_target` がなかった。子プロセスの package identity が空でも、ファイルハンドルの物理パスは転送先を指していた。

14:55 JST に Minecraft が停止している状態で、転送を継承しない Windows PowerShell から指定された実プロファイルへ置換した。製品は hidden を含め 1 本、導入後 SHA-256 はビルド成果物と同じ `ca47984f9bdca53bcf32ccda161c98afd12962d208ba4bf3fbad2aa0dab89d0e`。置換前後の物理パスとステージのハッシュを検証し、追加バックアップは作成していない。本体は `558740982bbffb26361cfbe217f6667032b260c3` の成果物。

左クリック不調の調査では、実プロファイルの攻撃・破壊が Enter に設定されていた。利用者の明示依頼により、停止中にこのキー設定だけを左クリックへ復元した。再起動後に利用者が「左クリックが戻った」と確認した。右クリック・メニュー入力の不調は未確認であり、MOD の入力隔離不具合とは断定していない。

14:59 JST の起動中 endpoint への接続診断で、`tools/list` の全 5 Tool がローカル catalog と完全一致し、`repeat_target` / `max_repetitions` の公開を確認した。実ログ内の MCMCP 関連エラー行は 0 件。公開 `agent_get_state` では OFF・Action 不在だった。診断でゲーム操作を送信していないため、反復採掘や継続 use の合格判定は引き続き未実施。

再発防止として[導入ツール](../../tools/install/README.md)を追加した。既存の結果ファイルを上書きせず、製品の重複、稼働中 Java、転送された物理パス、相対パス、ハッシュ不一致を拒否する。PowerShell 7 と転送を継承しない Windows PowerShell 5.1 の両方で回帰試験 11 件成功。実際の転送された AppData の検査も拒否された。独立 Codex CLI の再レビューでブロッキング指摘なし。導入成功は前項の実機合格を意味しない。

## 時間指定への改修

利用者の雪半自動装置では、初版の10秒・200tick・64回上限のActionが `succeeded`、`interactions:34` となり、利用者も採掘を目視確認した。移動・視点変化は0、effectsは空のため破壊数・回収量は未確認。依頼元タスクからの報告で、Action IDは `36200134-8c74-4e65-9a14-d28ee446e605`。先行する8回上限の試験では `BUDGET_EXCEEDED` になった。利用者は時間まで継続する仕様を明示依頼した。

`max_repetitions` fieldを廃止し、`repeat_target:true` と `duration_ticks` で継続する。1 client tickに最大1回の新規開始を認可し、compilerは `duration_ticks` 回のinteractionと、attackでは同数の破壊枠を予約する。時間分に満たない要求budgetやlocal hard limitは実行前に拒否する。開始回数64や記録側2,048による時間前の停止をなくし、最大24時間の記録を保持できる。待機・継続・cooldownでは試行数を増やさず、effects・破壊数を捏造しない。通常Action、kill-zone、effect sequenceの既存上限は維持する。

target guard、開始済みuseの継続、時間・tick期限、simulation pause、Esc/OFF/cancel/world変更・入力解放の経路は維持した。独立Codex CLIへ差分と関連実装を渡した静的レビューでブロッキング指摘なし。旧fieldはschema/DSLの双方で拒否し、catalog、利用例、ガイド、固定hashを同期した。

検証は main test 1,275件、harness 13件、admin bridge 21件が成功し、製品JAR分離とbuildも成功。4,096回のruntime開始と同tick内の二重開始拒否、24時間分1,728,000回の会計、完全な時間予算、local hard limitと通常Actionへの上限漏出防止を確認した。Python transport 14件、capability mock 11組も成功。

15:33 JST、Minecraft停止中に指定実プロファイルの製品1本を置換した。物理パスと導入後SHA-256 `54be15f3acbda749d27e487ec540d10b7b5eb2099928244ded316c15a7bbdda5` がビルド成果物と一致。追加バックアップは作成していない。この版の再起動後の読込みと64回を超える実機継続は確認待ちとし、`release:verification-needed`を維持する。


## 時間指定版の実機追確認と手持ち情報

15:38 JST、起動中endpointの全5 Toolが時間指定版のcatalogと完全一致し、`max_repetitions` 不在を確認した。操作元タスクから60秒・1,200tickのAction `fad6dd73-c997-4dbf-b295-a97afa31e2ed` が成功し、`interactions:226`、位置・視点変化なしとの報告を受けた。64回を超える入力継続を確認したが、この数は入力開始の試行数であり、破壊数や回収量の証明ではない。

同タスクから利用者の依頼として、手持ちの表示名・残耐久・エンチャント等を公開Toolから取得する追加対応を受けた。`agent_get_state.held_items` を追加し、選択slotと両手の個体を区別する。報告された「aod shovel」、残耐久1,367/1,561、Efficiency V / Unbreaking III / Mending、採掘効率+26を単体fixtureへ使った。これは報告時点の例であり、追加版の実機読取り結果ではない。

初回独立レビューが指摘した最終tooltip eventによる非表示と長いitem IDへの対応を追加した。標準componentだけでなく最終advanced tooltipと照合し、非表示・置換・取得失敗・数値丸め・player基礎値の合算を公開根拠に使わない。新しい公開情報の実機読取りは導入後に別途確認する。

追加版はJava 1,285件、harness 13件、admin bridge 21件と製品JAR分離/buildが成功。Python transport 14件、capability mock 11組も成功した。最終表示のスロット区分を照合し、同一表示が複数区分にある属性は省略する回帰も追加。独立Codex CLIの再レビューでブロッキング指摘なし。


## 採掘の面一致を明示的に省略する追加対応

利用者から、同じ雪の観測代表面upと実crosshairのeast等の違いで長押し開始が拒否され、手動で照準を合わせ直す負担があると報告された。採掘に限る面一致の省略、使用では厳密一致を維持する設計を説明し、2026-09-13に利用者が実装を承認した。

`target_guard.match_face` を追加し、省略時はtrueを維持した。falseを許す入力はattack、またはattackとsneakの組合せだけ。useではschemaとDSL validatorの双方がfalseを拒否する。faceは観測記録として必須のままで、初回と毎回のdispatchが共有する照合処理の面比較だけを省略する。実BLOCK hit、同一dimension/座標/block、非null時の完全state、loaded/world border/reach、手持ち、静止、health等は維持し、実際に当たった面を使うVanilla操作を継続する。

従来のtarget_face_or_reach_changedは、非BLOCK hit、座標違い、面違い、block違い、state違い、unloaded、world border外、reach外の固定診断へ分割した。反復中の対象不一致は引き続き新規開始を抑止して同じ対象を待ち、期限や試行数を延長しない。雪の公開例にはmatch_face:falseを含めた。

Java 1,291件、harness 13件、admin bridge 21件、製品分離/buildが成功。全6面、既定strict、別座標/MISS/entity/非一致blockとstateの拒否、不適合hitでstateを読まないこと、横面での初回開始・対象消失待機・別面での復帰を回帰確認した。独立Codex CLIの静的レビューでブロッキング指摘なし。subagentsは使っていない。

本追加版の実機面照合は未確認。利用者の指示により現行版での採掘は操作元タスクが担当し、実装タスクはゲームやJARを操作せず、導入可能な成果物まで準備する。CI、監査、実プロファイルへの導入状況はPR #35の対象SHA付き記録で追跡する。
