# 共通収納候補の検証（2026-09-26、#70）

状態: 単体・契約試験合格、実機未確認。配布・差替は行っていない。

共通のMenu数量移送へ、所持収納の発見・通常開封・個体照合を接続した。公開Toolは5本のままで、`operate_known_menu` にinspect/take/storeを追加する。対応する最初の連携はSophisticated Backpacksの所持品・胸装備・offhandで、MOD共通の実行部分は既存のexact PICKUP計画を利用する。

対象: Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25。互換性確認に使ったJAR:

| MOD | version | SHA-256 |
| --- | --- | --- |
| Sophisticated Backpacks | 3.25.90 | `9b8b60c087937b141c8ed61c8fea357ac8931f86eda42a26198c231712eb4037` |
| Sophisticated Core | 1.4.99 | `f80b8868d15b59882c642ebaa020100e9d1f59cfbae8bdb6a584140b658fb10e` |

[Backpacks](https://github.com/P3pp3rF1y/SophisticatedBackpacks)と[Core](https://github.com/P3pp3rF1y/SophisticatedCore)の26.2ソース、および上記JARの署名・bytecode構造を読取確認した。upstreamのソースファイルは本repoへ追加していない。storageの拡張stack、別管理のupgrade slot、full/slot独自同期、Vanilla cursor同期を区別し、全slotの照合とfresh cursor証拠を要求する。overflow upgrade等、共通数量計画の保存則を保証できない構成は拒否する。

`test harnessTest adminBridgeTest verifyHarnessIsolation build` が成功。主要な確認は、拡張stackからの指定数取出し・格納、非連続slot配置と保護slot不変、同品目の別個体・参照失効・消費、slot更新だけではcursorを確認しないこと、取消時のconfirmed prefixとUNKNOWN末尾の一度だけの記録、公開schemaと予算である。

独立レビュー用Codex CLIはローカル読取基盤の欠落で対象差分を読めず、レビュー未完了。自身の差分確認は独立レビュー合格の代用としない。

隔離DockerへのSSHはAccess認証完了後もbanner timeoutとなった。ゲーム・通常プロファイルへの操作は実施していない。以下の実機確認が残るため、`release:verification-needed`を維持する。

- MOD有無それぞれの起動とoptional mixinの適用。
- 同種バッグ2個、胸/offhand、inspect→取出し→格納→再開封の個体・数量一致。
- 拡張stack、upgrade/保護slot、補充、遅延・欠落した同期、途中取消と遅い開封のcleanup。
- worldに置いたMOD収納、手動open menuの数量指定、未初期化UUID、入れ子・linked・追加装備API・未知versionはこの候補では未対応。

仕様見直し用の比較表は[こちら](../仕様見直し_20260926.md)。#70全体の受入完了とは扱わず、PRは`Refs #70`とする。
