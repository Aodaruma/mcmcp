# 共通収納候補の検証（2026-09-26、#70）

状態: 単体・契約試験合格、所持バッグの基本操作を隔離Dockerで部分確認。配布・通常プロファイルへの差替は行っていない。

共通のMenu数量移送へ、所持収納の発見・通常開封・個体照合を接続した。公開Toolは5本のままで、`operate_known_menu` にinspect/take/storeを追加する。対応する最初の連携はSophisticated Backpacksの所持品・胸装備・offhandで、MOD共通の実行部分は既存のexact PICKUP計画を利用する。

対象: Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25。互換性確認に使ったJAR:

| MOD | version | SHA-256 |
| --- | --- | --- |
| Sophisticated Backpacks | 3.25.90 | `9b8b60c087937b141c8ed61c8fea357ac8931f86eda42a26198c231712eb4037` |
| Sophisticated Core | 1.4.99 | `f80b8868d15b59882c642ebaa020100e9d1f59cfbae8bdb6a584140b658fb10e` |

[Backpacks](https://github.com/P3pp3rF1y/SophisticatedBackpacks)と[Core](https://github.com/P3pp3rF1y/SophisticatedCore)の26.2ソース、および上記JARの署名・bytecode構造を読取確認した。upstreamのソースファイルは本repoへ追加していない。storageの拡張stack、別管理のupgrade slot、full/slot独自同期、Vanilla cursor同期を区別し、全slotの照合とfresh cursor証拠を要求する。overflow upgrade等、共通数量計画の保存則を保証できない構成は拒否する。

`test harnessTest adminBridgeTest verifyHarnessIsolation build` が成功。主要な確認は、拡張stackからの指定数取出し・格納、非連続slot配置と保護slot不変、同品目の別個体・参照失効・消費、slot更新だけではcursorを確認しないこと、取消時のconfirmed prefixとUNKNOWN末尾の一度だけの記録、公開schemaと予算である。

2026-09-27の独立Codex CLIレビューで、拡張stackの空cursor PICKUPを通常の64個で切っていた不一致を発見した。Minecraft 26.2の`AbstractContainerMenu.doClick`と`Slot.tryRemove`をbytecodeで再確認し、左clickはslot全量、右clickは半量を持つ規則へ修正した。2560個から1個を移す場合のclickごとのslot/cursor、保存則、14clickで計画不能な数量の事前拒否を回帰試験へ追加した。開封→click→再開封→readbackや取消の境界は実機・結合確認がなお必要である。

初回のSSH試行はAccess認証後にbanner timeoutとなり、当時は実機確認を保留した。9月28日の基本操作確認後も以下が残るため、`release:verification-needed`を維持する。

- MODなしでの起動とoptional mixinの適用。MODありの起動と基本操作は9月28日に確認済み。
- 同種バッグ2個、胸/offhandでの個体・数量一致。所持バッグ1個のinspect→格納→取出し→再開封は9月28日に確認済み。
- 拡張stack、upgrade/保護slot、補充、遅延・欠落した同期、途中取消と遅い開封のcleanup。
- worldに置いたMOD収納、手動open menuの数量指定、未初期化UUID、入れ子・linked・追加装備API・未知versionはこの候補では未対応。

仕様見直し用の比較表は[こちら](../仕様見直し_20260926.md)。#70全体の受入完了とは扱わず、PRは`Refs #70`とする。

## 2026-09-28の隔離Docker実機確認

`aod-mimoid`の専用Docker clone（`MCMCP-Validation`）で、上表のSophisticated Backpacks/Core JARのSHA-256を再照合した。通常の右クリックで初期化した所持バックパックをMCPが発見し、同じ`storage_id`を継続して返した。`inspect`は空、`store`で丸石4個を格納、`take`で2個を取り出し、各操作後に同じバッグを再開封して内容を確認した。所持丸石は16→12→14、バッグ内は0→4→2。各Actionは`succeeded`、終了後は`READY`、`take`の効果台帳は`storage_take`/`confirmed`、source 4→2とdestination 12→14を公開schema内で返した。候補JARのSHA-256は`4E575347392ECB2C9A04DA9E189C7902610D98CEEA3FC24DFBBE1FD5954841E6`。

この実機確認で次の3点を修正した。地上静止中に残る重力由来のY速度で開封前検査が拒否されていたため、接地を別途要求した上で水平速度だけを見る。効果台帳のsubjectに大文字等を含む一時参照をそのまま入れず、非可逆の固定形式識別子を用いる。`storage_take`/`storage_store`を`agent_get_action`の公開出力schemaへ追加する。Java buildとsource check、対象JARを使った上記操作で確認した。

テスト前にworld・mods・options・instanceの89ファイルをSHA-256付きで保存した。完了後はcontainerを停止し、89ファイルすべて元のハッシュへ復元した（不一致0）。結果は隔離labの`eval-artifacts/20260928-pr82-gate-result.json`に保存した。胸装備/offhand、同種2個、拡張stack、upgrade/保護slot、取消や同期欠落、他MODは実機未確認。`release:verification-needed`とDraftは維持する。
