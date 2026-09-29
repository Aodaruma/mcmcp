# v2 移動・設備 fixture

Minecraft 26.2の隔離singleplayer専用。元world・設定・JARを退避し、試験後にhash照合付きで復旧する。通常worldでは実行しない。

`datapack`をsaveの`datapacks/v2nav`へコピーする。管理用の通常チャットで`/reload`、`/forceload add 298 298 326 324`を実行し、ロード完了後に下表のfunctionを実行する。各準備後に公開`agent_get_state`で開始位置を確認する。MCPをONにし、チャットを閉じてから試験する。

functionによる配置・teleport・定周期電源切替は試験設備の準備。評価対象の移動、視点、レバー・収納操作は公開v2 MCPのみで実行する。製品APIから任意commandを送る機能は追加しない。

| function | 開始位置 | 主な公開API引数と判定 |
|---|---|---|
| `v2nav:ladder` | 304.5,101,307.5 | move `{x:308,y:105,z:305}`、container inspect `{x:309,y:105,z:305}`。上階へ到達、石3個を確認 |
| `v2nav:low` | 313.5,101,310.5 | move `{x:318,y:101,z:310,sneak:true}`。1.5 block高の通路内へ到達 |
| `v2nav:crawl` | 313.5,101,316.5 | interact `{target:"block",x:313,y:102,z:316,block:"minecraft:oak_trapdoor",sneak:true}`で閉じ、move `{x:318,y:101,z:316}`。1 block高の通路内へ到達 |
| `v2nav:door` | 304.5,101,320.5 | 2秒ごとのiron door。背後のlever `{x:308,y:102,z:320}`をON、chest `{x:308,y:101,z:320}`の石3個をinspect、move `{x:307,y:101,z:320}`で通過 |
| `v2nav:piston` | 304.5,101,320.5 | 上下2段のsticky piston。`v2nav:power_on`で遮蔽状態から開始できる。doorと同じ背後操作と通過を確認 |
| `v2nav:replan` | 300.5,101,300.5 | move `{x:310,y:101,z:300}`。x=302付近への進行後にx=305へ障害物を追加。再計画・迂回・無破壊で到達 |

再計画無効の比較は`auto_replan:false`を加える。**それぞれ新しいworld sessionで開始する**。既に通った迂回路の観測が残る場合、最初から別経路を選び、再計画要求が発生しないことがある。そのrunは無効化の停止試験として数えない。既定の試験は`result.path_replans >= 1`、無効の試験は`route_replan_required`と回数0を要件にする。

block操作では`target:"block"`、収納では`operation:"inspect",target:"container"`が必要。背後への操作には`advance:false`を使い、移動せず見えるタイミングで操作したことを確認できる。開始応答は完了ではなく、`agent_get_action`を同じIDでterminalまで取得する。

視点専用の試験は`agent_look`の前後でposition/hotbarが一致し、yaw/pitchが指定点へ変わることを確認する。逆方向へのlookを取消、`max_ticks:1`で期限停止、scriptの`look(...)`2回も確認する。

準備し直す前にAction終了と解放を確認し、menu閉鎖が描画へ反映されるまで待つ。試験終了時は`/function v2nav:stop`で予約functionを解除し、ゲームを終了して退避元を復旧する。

実施結果: [2026-09-30記録](../../../../docs/experiments/20260930_v2_navigation_docker.md)。
