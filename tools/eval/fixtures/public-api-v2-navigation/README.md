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
| `v2nav:flight` | 301.5,101,301.5、creative | move up 9 → `{x:313,y:110,z:301}` → down 9。3ケースとも`auto_replan:false`で到達すること |
| `v2nav:flight_ledge` | 301.5,111,301.5、creative | move `{x:313,y:101,z:301}`。足場から離れ、10 block下の目的地へ飛行で降下 |
| `v2nav:flight_wall` | creative、301.5,105,302.5へtp後は落下 | move `{x:312,y:105,z:302}`。x=306の壁を回避する。開始直前の実際の高さをstateで記録 |
| `v2nav:spectator_wall` | 301.5,105,302.5、spectator | move `{x:312,y:109,z:302}`。壁を通る空中経路、到着高度を確認 |
| `v2nav:flight_tunnel` | 314.5,101,310.5、creative | move `{x:318,y:101,z:310}`。高さ2 blockのトンネル内で離陸・移動 |

落下中からの開始は`v2nav:flight`の後、通常チャットでsurvival→creativeへ切り替え（飛行を解除）、`/tp @s 301.5 130 301.5`を実行する。直後のstateで落下後の高さを記録し、move `{x:313,y:112,z:301,max_ticks:1200}`で再離陸と到達を確認する。

飛行中の停止確認は長いmoveを開始してAPI取消、`max_ticks`、物理Escを個別に使い、終端後にstateを2回取得して位置と所有入力の解放を照合する。モード変更は`/schedule function v2nav:flight_survival 20t`の直後に長いmoveを開始する。functionは試験の落下ダメージを避けるslow fallingを付けてsurvivalへ変更する。終了後の重力移動はAgentの残留入力と区別する。

再計画無効の比較は`auto_replan:false`を加える。**それぞれ新しいworld sessionで開始する**。既に通った迂回路の観測が残る場合、最初から別経路を選び、再計画要求が発生しないことがある。そのrunは無効化の停止試験として数えない。既定の試験は`result.path_replans >= 1`、無効の試験は`route_replan_required`と回数0を要件にする。

block操作では`target:"block"`、収納では`operation:"inspect",target:"container"`が必要。背後への操作には`advance:false`を使い、移動せず見えるタイミングで操作したことを確認できる。開始応答は完了ではなく、`agent_get_action`を同じIDでterminalまで取得する。

視点専用の試験は`agent_look`の前後でposition/hotbarが一致し、yaw/pitchが指定点へ変わることを確認する。逆方向へのlookを取消、`max_ticks:1`で期限停止、scriptの`look(...)`2回も確認する。

準備し直す前にAction終了と解放を確認し、menu閉鎖が描画へ反映されるまで待つ。試験終了時は`/function v2nav:stop`で予約functionを解除し、ゲームを終了して退避元を復旧する。

実施結果: [移動・設備](../../../../docs/experiments/20260930_v2_navigation_docker.md)、[飛行・降下](../../../../docs/experiments/20260930_v2_flight_docker.md)。
