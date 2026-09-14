# 単一設置planの描画回復待ち

Issue #54 / PR #55。製品処理の検証対象は `480073ef03f6577b71e52806aa48c18c10f99330`。Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25。

## 問題と変更

松明の支持を配送しcameraが成功した後、単一entryの `apply_known_block_plan` が受付前のTARGET_UNKNOWNまたはknown_surface_changedで散発的に拒否された。外部で支持を再取得しても、受付snapshotとcommit/dispatchの間のrenderer欠測との競合は防げない。

単一entryで既知の完全state支持を指定するplanだけ、既存の支持leaseと有限の描画回復待ちへ含めた。元HTTP締切・配送TTL・総Action予算を保持し、最初の15秒/300tick枠へ待機を算入する。復帰後の現在ray・完全state/item/shape・fog/LOS・姿勢・安全・server確認は省略しない。複数entryと依存支持は対象外。床の外部重複観測削減は維持し、公開Tool/DSL schemaは変更していない。

## ソース検証

`tools/check-source.ps1` 全件成功。Java unit 1,296件、harness、admin bridge、fixture isolation、build、Python 14件、外部施工runnerとcapability mockを含む。

`SurfacePreflightRecoveryTest` 15件では単一設置のcapture/commit/dispatch/JIT欠測・復帰、実block変更・遮蔽・短いfogの拒否、単一entry限定、元300tick予算を確認した。既存の配送期限・有限待機・再検証段階の試験も成功。独立した読み取り専用レビューは対象5ファイルと直接呼出元で指摘なし。レビュー自体はテストやゲームを実行していない。

## 隔離Docker試験

許可済みの使い回し検証cloneで、元save・MOD・instance設定・optionsを保全した。T0前に既存 `construction-materials` fixtureを準備し、maxFps=10 / VSync=falseで試験した。fixtureは14 command、宣言変更973 blocks、hash `3823e247118ab7f59daefc7fdec13c5710f7f8a378b880da3d2de19253af7b97`。

T0以降は既存の公開5 Toolを使う決定論的capability gateだけで操作し、画面観測やadmin操作を挟まなかった。2回目はMinecraftを通常終了・再起動してからfixtureを再準備した。

| 試験 | 雪 | 黒羊毛 | 赤コンクリート | 松明 | 結果 |
| --- | ---: | ---: | ---: | ---: | --- |
| run01 | 6 ticks | 10 ticks | 8 ticks | 8 ticks | 4設置成功 |
| run02・再起動後 | 6 ticks | 10 ticks | 8 ticks | 8 ticks | 4設置成功 |

各設置でCONFIRMED effect、設置先の再観測、材料1個消費を確認。松明は各回16→15個。両回の松明Actionには `RENDERER_RECOVERY: missing=commit,dispatch;revalidated=commit,dispatch` があり、欠測後の再検証と成功が記録された。雪ではdispatch、黒羊毛・赤コンクリートでもcommit/dispatchの回復を確認した。capture/JITの欠測回復は上記unit試験の検証範囲であり、実機traceと混同しない。

- run01 松明: `9f79b965-a277-422a-865c-9bc78cef3be3`
- run02 松明: `d5ae8a41-730f-49b5-8127-5abf7e9f426e`

両回の全Action terminalとcontrol READYを公開MCPで確認した。入力owner自体は公開されていないため、その直接観測とは表現しない。試験後はsave 61ファイル・MOD・設定・optionsのhash復旧と検証clone停止を確認し、元containerは停止を維持した。

製品JAR SHA-256: `c59d694039624ab5e348bc9b8f0304fa9b08252240a43a3cb7093063cd573a88`。検証専用fixture-admin: `d68186df379dc94b73a0fc5a98b1253c8322becbe9037486ce0bef70d99a09e6`。後者を通常プロファイルへ導入しない。

元現場の個別拒否は当時のfog/barrier記録がなく、直接原因の確定には至っていない。元現場への導入・長時間安定性、初見LLMによる全施工自律評価はこの合格範囲に含まない。公開Release/tagは作成していない。
