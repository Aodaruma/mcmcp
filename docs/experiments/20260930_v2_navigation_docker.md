# v2 経路再計画・上階・狭い通路・設備・視点操作

2026-09-30。利用者指定の隔離Docker、Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25で検証した。通常の「くらふとぶ！」のworldへ試験設備は配置していない。

## 変更内容

- `agent_move`は`auto_replan:true`を既定とし、新しい局所観測から最大8回再計画する。`false`で従来の停止に変更できる。元のtick・実時間・距離予算を引き継ぎ、破壊許可`clear_path`とは独立。結果に`path_replans`と`last_replan_reason`を保持する。
- 移動開始時に最初の水平経路へ視点を合わせる。`sneak:true`は通常のかがみ入力で姿勢を準備し、実際の体の高さの観測から計画する。斜め経路の支持・現在revision・各tickの衝突判定は維持し、立位固定だったcorridor高さを実際の姿勢に合わせた。
- block操作にも`sneak`を追加。トラップドア等の通常操作で匍匐姿勢を作れる。動く設備で照準が外れた場合は、使用前に観測し直して照準を合わせる。収納も開封前に照準を合わせ、`prepare_reason`を返す。使用・開封後の未確認操作は再送しない。
- `agent_look`を追加。小数を含む座標へ視点だけを向ける。共通job、配送確認、取消、期限、解放を使用し、scriptの`look(...)`からも呼べる。公開14ツールになった。
- 連続移動でsession mapが4,096 edgeを超えると計画が即`limit`になる不整合を修正。計画側を保存側と同じ8,192 edgeへ揃え、A*の2,048展開・64候補の上限は維持した。

## 候補と検証結果

最終候補r2のSHA-256: `1ca9409140ceab974ccbdf2f6f6f267b1d44682b04e39514923c563bdd3987c0`。

試験場は[再現用datapackと手順](../../tools/eval/fixtures/public-api-v2-navigation/README.md)。試験準備は管理用Vanilla function、評価対象の操作は公開MCPのみで行った。全ケースで`clear_path`は省略（false）。

| ケース | 実機結果 |
|---|---|
| 途中で進路に障害物が出現 | 1回再計画し7.25秒で迂回・到達。`route_edge_changed`を保持 |
| 同条件で`auto_replan:false`、新session | 1.10秒で`route_replan_required`。再計画0回、障害物手前で停止。後続stateでも位置一致、READY・実行中Actionなし |
| はしごと上階 | 6.69秒でy=101からy=105へ到達。上端にredstone dustとrailを配置。続くchest inspectで石3個を確認 |
| 1.5 block高の通路 | `sneak:true`で通路内部x=318へ4.78秒で到達 |
| 1 block高の通路 | `sneak:true`のtrapdoor操作でopen=true→falseをserver確認。続くmoveで通路内部へ5.36秒で到達 |
| 周期iron doorの向こう | 移動なしでleverのOFF→ONとchest内の石3個を確認。別moveで2.50秒で通過 |
| 上下2段の周期sticky pistonの向こう | 遮蔽状態から待ってleverのOFF→ONを2.25秒で確認。chest内の石3個を確認。別moveで2.70秒で通過 |
| 視点専用 | 0.49秒で成功。前後のpositionとhotbar一致 |
| 視点操作の取消・期限 | 取消は0.20秒でcancelled。`max_ticks:1`は入力予算1回でfailed、`look_reason:primitive_tick_budget_exhausted` |
| scriptの視点操作 | `look(...)`2回が成功、calls=2 |

上記の移動後は体力20を維持。1 block通路はVanillaの姿勢変更を利用し、プレイヤーの体やblockのcollisionを書き換えていない。fixtureで確認した配置の成功であり、元の現場の全形状・全MOD設備の成功を保証するものではない。

[公開用の結果抜粋](artifacts/20260930-v2-navigation/results.json)にはfixtureの座標、終端結果、経過時間等だけを保存する。認証値、元worldのファイル、アカウント名が写った画面は公開しない。

## 途中で見つかった問題と無効run

初期候補r1は再計画後、経路mapの上限不整合で目的地直前に`limit`となった。r2で修正し、満杯の8,192 edge mapから短距離の経路が得られる回帰試験と実機の到達を確認した。

無効化比較の初回は、前のrunで観測した迂回路を初めから選び、再計画要求なしで成功した。このrunは停止機能の検証に数えず、新sessionからやり直した。準備時のチャット未成立・screen残留による受付拒否も製品の移動成功には数えていない。

## ソース検証と残る範囲

Java 1,627件、harness 13件、admin bridge 28件（計1,668件）、`verifyHarnessIsolation`とbuildが成功。PowerShell/Python source checksも成功。新たに再計画の有効/無効・回数と元の予算・局所再観測待ちの取消、実姿勢の斜めcorridor、満杯map、lookの引数・配送/取消/未配送破棄を検証した。

再計画は無制限には続けない。局所安全が未確認のまま、元の予算超過、危険、制御解除、world境界などでは停止する。移動中の画面変更、挟み込みや被ダメージを許可する変更ではない。任意の機械周期や特殊なMOD collision、元の現場の再現試験は追加確認として残る。

任意MOD menu/収納provider、クラフト・取引・独自ボタン、複合条件・任意entity状態条件、縦方向の障害物施工、耐久・エンチャントを含む道具選択、詳細effect台帳、menuを開いたままのscript合成、新品backpackの初期化は今回の対象外。24時間連続実測も未実施。PR #85はDraftを維持し、main統合・タグ・Release公開はしない。

## 復旧・導入

試験後にMinecraftを通常終了し、検証containerを停止した。元world 61ファイル、設定・JARを含む退避65ファイルをすべてhash一致で復旧し、world内のfixture不在を確認した。[復旧receipt](artifacts/20260930-v2-navigation/restoration.json)。通常profileへの導入用JARは指定の差し替え担当へ渡し、SHA-256一致を確認済み。その後、通常ゲームの操作担当から作業中断と導入保留の連絡があったため、**今回の候補は通常profileへ未導入**。新しい開始指示まで差し替え・再起動・通常ゲーム操作を行わず、候補を保持する。
