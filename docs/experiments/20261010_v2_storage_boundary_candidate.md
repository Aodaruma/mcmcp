# 2026-10-10 拡張収納の枠境界（R20候補、実機未確認）

R18の通常導入は差し替え担当が13:17 JSTに確認し、地図アート担当へ所有を返した。新session、公開state/status、health20、Actionなし、バッグinspect、End往復と梯子降下が正常との報告を受けた。今回の拒否は解放待ちハングの再発ではなく、拒否後も状態取得・後続Actionが動いている。

## 報告とソースでの再現

既存の赤コンクリートパウダー1,816個、player側448個の状態でstore448が `storage_transfer_unprovable` / `inventory_not_confirmed` となった。confirmed0、unconfirmed=false、release_pending=false、release_confirmed=trueで、公開再観測でも数量は変わらなかった。新しく計画したstore232で2,048個へ、その後store216で計2,264個への収納は成功した。

現場証拠はローカル `_PLAYING/map-art-MFwtpM21wWc-3x3/duper-operation/r18-resumption-and-capacity-20261010.json` の `boundary_rejection` / `boundary_reobserve` / `boundary_workaround`。公開資料にはprivate worldや生のmenuデータを転記しない。

`ExactInventoryTransfer.plan` はクリック前に全数量を計画し、14クリック以内の通常PICKUPだけを実行する。従来はdestinationの順序を固定し、途中で部分的な枠を埋め、余ったcursorをsourceへ返してから次の枠へ移す。この余分な分割・返却で、空き容量があってもクリック上限を超える場合がある。

R18ソースで「source64×7、destination1816/2048＋空枠/2048、pickup上限64、要求448」を再現すると、planは空を返す。新規回帰試験の失敗を保存した。現場の正確なmenu snapshotは取得していないため、同じ計画上の問題は確認できたが、現場原因の最終確定は隔離実機での照合待ちとする。14クリック制限自体は意図した契約であり、2,048個をまたぐこと自体を禁止する仕様ではない。

## 修正

従来の順序で計画できる場合は、その計画をそのまま使う。作れない場合に限り、今回のsourceから移す全量を受け入れられるdestinationを優先する別の有限計画を1回だけ試す。item/components一致、slot容量、source数、数量、14クリック、各探索のstate上限を維持する。

上記のモデルは、最初の3スタックで既存枠を2,008個にし、残り4スタックを別枠の256個として収納する14クリックの計画になる。合計2,264個は変わらない。満杯まで詰めることより、上限内で正確な依頼数量を移すことを優先するfallbackであり、最少クリックの完全探索を保証するものではない。

これは最初のクリック前の内部計画の選び直しである。失敗したActionの再送、部分実行後の勝手な続行、UNKNOWNをconfirmedへ変更する処理は加えない。各クリックのserver snapshot確認とcursor解放の契約は同じ。共通plannerを使う収納・container・inventory move・craft材料配置・trade返却にも適用されるが、単一destinationの呼出しでは順序変更の効果はない。公開schema、上限、MOD profile hashは変更しない。

## 検証済みと候補の分離

- R18相当ソースで追加回帰1件が失敗することを確認した後、修正して成功した。
- 追加5件で、448個の全クリック照合・数量保存、232個では既存枠優先、近傍8種類の容量境界、容量不足／真のクリック上限超過での事前拒否、途中取消の所有cursor返却とconfirmed192個の保持を確認した。
- R18ベースの候補でJava1,712＋harness14＋admin bridge28＝1,754件が失敗・skipなし。`test harnessTest adminBridgeTest verifyHarnessIsolation build` が成功した。
- 製品JAR SHA-256: `7bdfee8c2428b27ea2a8717744f1d1182697b9a14749b4c185bd82d770bbae32`。
- fixture用JAR SHA-256: `7166e162107d2e0d83614a8cf5d2028a28b44c2d17bbf27c3f6f68680fc53435`。通常profileには入れない。

R20はcommit `d5a6630` のR18を基にした `feat/v2-storage-boundary-r18` で作成し、R19の接近先・script診断変更を含めない。R18/R19の既存JARは固定する。R20の実機試験と通常導入は未実施。通常ゲームに再起動・差し替えを要求しない。

## 次の隔離実機試験

新しい固定fixture `storage_boundary` は、拡張ネザライトバッグの収納枠0へred_concrete_powder1816個、playerの9～15番へ各64個を用意する。既存のupgrade・無関係なblack_concreteも残し、その不変を照合する。fixtureは従来どおりopt-inの使い捨てworldに限定し、各準備操作で再認可する。

新baseline／leaseを作り、次を検証する。復元済みのR18試験baselineを使い回さない。

1. R18製品＋新fixtureで拒否を再現し、数量2264・cursor空・release完了・state/status正常を確認する。
2. R20でfixtureを新規準備し、`agent_inventory(operation:"transfer",target:"storage",storage_slot:6,direction:"store",item:"minecraft:red_concrete_powder",count:448,max_ticks:400)` を1回実行する。再openしてbag2264／player0、他item・upgrade不変、cursor空を確認する。
3. fixtureをリセットし、232個→216個の従来分割、途中取消後の数量・cursor解放、容量／数量不足で入力前拒否を確認する。未知の変更があれば再送しない。
4. 各ケースでhealth・READY/jobなしを照合し、最後に新baselineの全ファイルhash一致で復元、container・Docker Desktopを停止する。

[AGENTS.md](../../AGENTS.md)によりaod-mimoid Dockerは23:00～12:00 JSTのみ利用する。本作業ではDockerと通常ゲームを操作していない。現場側は明示的な232＋216の分割で続行できているが、任意のUNKNOWN失敗を分割再送してよいという意味ではない。
