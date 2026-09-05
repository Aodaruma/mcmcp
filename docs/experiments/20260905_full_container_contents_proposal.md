# コンテナ全品目の構造化取得案

2026-09-05。利用者から「要約される部分を全部出すオプション等」の提案を受領した。これは検討案であり、公開Tool・schema・MODの動作は変更していない。ゲーム操作・設定変更・JAR差し替えも行っていない。

## 現状と必要性

正面棚 `(156,65,-300)` と `(154,64,-300)` のinspectで、公開NODE_EVIDENCEの `container_items=...` が末尾 `,...` となり、全品目比較ができないと報告された。

省略は2段階ある。MinecraftPhaseFiveInventoryPort.availableItemEvidenceがserver同期済みコンテナの集計を27種類へ制限し、KnownContainerAttemptも最大27件を要求する。その後runtime.containerItemsTraceが約252文字で切り、AgentActionStoreのNODE_EVIDENCEも256文字へ制限する。前段のtruncatedはtraceへ伝わらないため、末尾に省略記号がない結果も全件とは限らない。trace自体も256イベントのringで古い記録を失う。文字列の上限だけを増やしても解決しない。

## 推奨する公開形式

正規inspectで開き、server full-content同期・対象menuの所有権・終了時cleanupを確認した結果を、**traceとは別の構造化コンテナ結果**として保持する。既存の短いtraceは互換用に残す。item IDが同じstackは合計し、耐久値や名前などcomponentの差は区別しないことを明記する。

現在対応するコンテナは最大54枠なので、1回の結果は最大54種類で有限。まず**1コンテナ内は全品目を返し、複数コンテナの結果をページングする**形を推奨する。品目単位のページングは現状では不要で、1チェストが複数ページへ分かれることによる誤読を避けられる。

新Toolを増やさず、既存agent_get_actionのオプションで全件結果を要求する案とする。以下は未実装のフィールド案で、現在のToolには送れない。

- 入力：`container_results` の表示指定と、結果ページのcursor / limit。
- 各結果：単調増加の`result_seq`、`node_id`、repeat時の実行回番号、対象dimension/position、取得tick・revision、`items: [{item_id, count}]`。
- 件数：`total_item_types`、`returned_item_types`、`truncated`。完全結果なら両件数が一致しtruncated=false。
- ページ情報：`returned_results`、`next_cursor`、全結果数、保持件数、既に失われた結果の件数/sequence範囲。次ページがあることと、内容を欠落したことは別々に示す。
- 空コンテナ：成功した結果でitems=[]・総品目数0・truncated=false。未確認・失敗・期限切れ・未保持は明確な別状態とし、空配列を代用しない。

取得時点のimmutable snapshotをページングし、読み取り時にはゲームへ再アクセスしない。cursorはaction・結果sequence・snapshotに結び付け、期限切れや別Actionへの使い回しを明示エラーにする。記録は再操作の認可には使わず、内容の変化を知るには新しい正規inspectが必要となる。

## 保持上限と完全性

AgentActionStoreは現在、最新Actionと直前のterminalを保持する。新しいActionを重ねると過去Actionを読めなくなるため、結果取得の期限もTool descriptionへ明記する必要がある。

repeatを含む長いActionで結果を無制限に保存しない。保存件数・総byte数・応答byte数を定める。全件取得を明示要求したActionでは、実行前にinspectの最大実行回数が保持枠に収まることを検証して、不足する場合は小さいActionへ分割するようエラーを返す案が確実。実行後の黙ったring evictionを「全件取得」と呼ばない。通常モードで古い結果を破棄する場合も欠落metadataを必ず返す。

この保存方針にはstart_action/inspect側の明示オプションが必要か、全inspectを有限の保存枠へ常時格納するかの選択が残る。既存Actionの受け入れ条件を不用意に厳しくせず、既存trace利用者との互換性と合わせて決める。具体的な枠数は出力・メモリbudgetを測って定める。

## 実装時の変更箇所と検証

1. inventory adapterのinspect結果を全54枠から集計し、総品目数・完全性を保持する。transfer用の既存要約契約は分けて扱う。
2. KnownContainerAttemptのtyped resultへ完全なitem集計と取得証拠を渡す。cleanup確認前に成功結果を公開しない。
3. runtimeとAgentActionStoreへnode実行回ごとのimmutable結果、保存上限、ページ取得を追加する。文字列traceに完全データを埋め込まない。
4. 正本Tool catalog、input/output schema、wire mapper、固定hash、設計書・クイックガイドを同期する。Tool数は現行5個を維持する。
5. 28〜54種類、同IDの合算3,456個、空コンテナ、文字列要約に収まらないID、repeat/batchの結果識別、後続node失敗時の先行結果保持をテストする。cursor境界・不正cursor・失効・保持上限・response byte上限も検証する。
6. 未開封・未同期・所有権不一致から結果を作れないこと、cleanup保留/失敗時に成功と誤表示しないこと、raw NBT・component・隠れたworld情報を含まないことを検証する。

現行監査は変更せず進め、実装する段階で公開契約の詳細を確定してbuild・unit・harness isolation・schema検証を行う。
