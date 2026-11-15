# v2 長時間入力：短い隔離実機試験の準備

候補は製品commit `633caff0d69562a63b187e7aa22bb8fe5060cf1a`、JAR SHA-256 `f787171893dff9ea3027fb9139c28ad28f8dad0b05ffcc375b8fa304bc9b2e7b`。このfixtureの追加で製品JARは変更しない。2026-09-29に短い隔離実機A–Hを確認した。[仕様](../../../../docs/PUBLIC_API_V2_LONG_EXECUTION_20260929.md)と、無効run・GUI観測の限界を含む[検証記録](../../../../docs/experiments/20260929_v2_long_execution.md)を参照。本手順を配置しただけで新しいrunを合格にはしない。

既存の `MCMCP-V2-Local-20260928` profileと、前回の専用 `New World` を使う。前回終了時にprofileは試験前へ復旧済みなので、試験後のprivate退避から専用worldを戻す準備が必要。通常「くらふとぶ！」profileやサーバーをfixtureとして使用しない。操作開始の新しい合図を得てから、利用者指定の差し替え担当が停止・backup・配置・GUI起動をまとめて行う。

## 配置前後の確認

1. 停止した隔離profileのsave全体、MOD、設定を別途backupし、相対path・file数・hashを記録する。前回の退避を上書きしない。
2. ネイティブPrismが読む物理profileを確認する。表示上のAppDataパスだけではCodexの仮想化領域と区別できない。実体側の候補JAR hash、重複製品JAR不在を確認する。
3. 前回の隔離worldを復元する場合、同じworldへ旧fixtureの変更も含まれるため、今回もsave全体をbaselineへ復旧する。この試験にfixture-admin JAR・admin tokenは不要。通常profileへの導入物は製品JARのみ。
4. このディレクトリの `datapack` を隔離worldの `datapacks/v2-long` として配置する。自動起動するload/tick tagはない。pack metadataは26.2で使用した `107.1`。今回の隔離試験でfunctionの読み込みと実行を確認した。
5. 隔離profileをGUI起動。T0前に `/reload` し、function読込エラーがないことを確認する。試験中のchat・inventory画面は停止条件になるため、下記の準備コマンドは各jobの開始前だけ実行する。
6. MCP ON後、fresh `agent_get_mcp_status` のworld session、control `ready`、running actionなしを確認する。`agent_get_state` のschema 2、位置 `(200.5,201,200.5)`、体力、選択slot、offhandを確認する。前回のsession IDは再利用しない。`-Check`はtool名/数の検査であり、拡張schemaの照合を代替しない。
7. 通常の公開MCP接続先と隔離ゲームの接続先を混同しない。endpoint・隔離profileのtokenファイル・fresh sessionを組にして照合し、不一致ならjobを開始しない。同時に両ゲームを起動しない。

## 補助fixtureの役割

ゲームUIで次のいずれかを実行し、chatを閉じて**キーボード6でhotbarの0始まりslot 5**を選び、MCP ONにする。以後、job終了まで位置・視線・選択slotを変更しない。

| 準備function | 内容 |
|---|---|
| `/function v2_long:prepare` | 平面・空の所持品・姿勢を準備。予約処理なし。一般入力の試験用 |
| `/function v2_long:refill` | slot 5に黒い粉1個。最初の設置から80 server tick後に同じslotへ32個補充 |
| `/function v2_long:late` | 同上、補充は160 server tick後。APIの補充待ち期限は3実秒にする |
| `/function v2_long:wrong` | 最初の設置から80 server tick後、同じslotへdirt 32個を投入 |
| `/function v2_long:stop` | 補助処理のscheduleと試験player tagを解除。job終了後に使用 |

`prepare`は足場 `(198,200,198)..(204,200,206)` と空間 `(198,201,198)..(204,206,206)`、試験playerの所持品・位置・game modeを変更する。初回の `schedule clear`（予約なし）や再準備時のobjective既存メッセージは構文エラーとは区別する。

補助処理は水を使わず、固定cell `(200,201,201)` に置かれた黒い粉だけを検知・除去する。これで同じ視線先へ再設置でき、stack個数と対象block stateが変わる。最初の除去でタイマーを開始し、補充は一度だけ。`#placed`、`#refilled` をobjective `mcv2long`へ記録する。準備から最大1,200 server tickで自己停止し、自動的に再起動しない。pause中はserver tickも止まり得るため、この上限を実時間60秒と断定しない。

これは**材料guard試験用の外部fixture**であり、製品の自動補充・自動破壊・コンクリート硬化機能ではない。製品MCPへcommand/admin権限を渡さない。実機で最初の粉が指定cellへ置けない場合はそのrunを不成立と記録し、取消・入力停止を確認してからfixtureの向き/座標を修正する。job中にUIで修正しない。

## 公開APIの呼び出し

既存の [単発CLI](../../../mcp/Invoke-Mcmcp.ps1) を利用し、新しい常駐runnerを設けない。以下はrepoルートから実行する例。tokenの値を引数やログへ出さず、`$longTokenPath` は隔離profileのファイルpath、`$longEndpoint` は確認済みendpoint、`$longOut` は新しいprivate記録ディレクトリに設定する。

```powershell
# 実機準備とfresh sessionの照合後に、一度だけ開始する。
$longRequest = 'tools/eval/fixtures/public-api-v2-long/requests/detached.json'
pwsh -NoProfile -File tools/mcp/Invoke-Mcmcp.ps1 `
  -TokenPath $longTokenPath -Endpoint $longEndpoint `
  -Tool agent_input_sequence -ArgumentsPath $longRequest |
  Set-Content -LiteralPath (Join-Path $longOut 'start.json') -Encoding utf8NoBOM
$longExitCode = $LASTEXITCODE
$longReceipt = Get-Content (Join-Path $longOut 'start.json') -Raw | ConvertFrom-Json
if ($longExitCode -ne 0 -or -not $longReceipt.ok) {
  throw '開始結果を確認できません。同じ開始要求を再送せず、controlのactive IDを確認してください。'
}
$longActionId = $longReceipt.result.action_id
@{action_id=$longActionId; include_result=$true; wait_timeout_ms=1000} |
  ConvertTo-Json | Set-Content (Join-Path $longOut 'get.json') -Encoding utf8NoBOM
pwsh -NoProfile -File tools/mcp/Invoke-Mcmcp.ps1 `
  -TokenPath $longTokenPath -Endpoint $longEndpoint `
  -Tool agent_get_action -ArgumentsPath (Join-Path $longOut 'get.json')
```

`-WaitSeconds`は開始CLIへ付けない。CLIの終了時刻・exit codeを記録し、そのプロセスが終了した後の別CLIで、同一action IDの進行を2回以上取得する。観測は試験証拠のためで、job継続の条件ではない。観測結果と取得時刻をprivate artifactへ保存する。`queued`だけでは実行済みとしない。

取消は同じIDを一度指定する。取消後もterminalまで取得し、`running_action_id:null`を確認する。

```powershell
@{action_id=$longActionId} | ConvertTo-Json |
  Set-Content (Join-Path $longOut 'cancel.json') -Encoding utf8NoBOM
pwsh -NoProfile -File tools/mcp/Invoke-Mcmcp.ps1 `
  -TokenPath $longTokenPath -Endpoint $longEndpoint `
  -Tool agent_cancel_action -ArgumentsPath (Join-Path $longOut 'cancel.json')
```

通信結果が不明な開始要求は再送しない。fresh controlで稼働IDを照合し、必要ならそのjobを取消する。別session・別worldの稼働jobには触れない。試験を中断した場合も、既知の同一jobの停止・解放を確認する。

## 試験順と合格条件

各行は独立したrunとして準備し直す。pollは通常0.5～1秒間隔、1回の `wait_timeout_ms` は最大1,000程度で十分。job開始直後・各遷移・terminal・終了後のcontrol/stateを残す。API成功だけで物理的な入力解放を断定せず、画面のかがみ解除・設置停止も許可された画面操作時間内に確認する。

| 試験 | 準備とリクエスト | 必須証拠 |
|---|---|---|
| A 秒指定終了 | `prepare`、[duration.json](requests/duration.json) | 実入力後、同じIDが約3秒の期限で `succeeded` / `duration_elapsed`。remaining 0、control解放、かがみ解除。tick列が先に完了して `completed` になった場合は別途記録し、期限到達の合格に置き換えない |
| B 開始CLIから独立 | `prepare`、[detached.json](requests/detached.json) | 開始CLIがexitした後にrunningを確認。数秒の無通信区間後も同一IDのcompleted_operations増加、最終terminalと解放 |
| C 取消 | `prepare`、[cancel.json](requests/cancel.json) | runningかつ入力数>0で取消。`cancelled` / `client_request`、control解放、かがみ解除。次の観測で勝手に再開しない |
| D 枯渇と補充 | `refill`、[refill.json](requests/refill.json) | 初期1個消費→同じIDで `waiting_for_item`、slot 5空、入力数が待機中増えない→同じslotに粉32個→`holding`に戻り入力数・粉の消費が再開。再開後数秒でAPI取消し解放確認。残り期限を足し直さない |
| E 遅い補充 | `late`、[late.json](requests/late.json) | 枯渇から3実秒で `failed` / `refill_timeout`。後の補充到着も観測し、同じterminal、control解放、粉32個不変。補充より前の終了記録だけでは不十分 |
| F 別材料 | `wrong`、[wrong.json](requests/wrong.json) | 枯渇後、dirt到着で `failed` / `item_changed`。dirt 32個を消費せず、以後も同一terminalとcontrol解放 |
| G 非アクティブ | `prepare`、[background.json](requests/background.json) | runningを確認後、画面担当が別アプリへ一度切替。focus喪失時刻、pauseOnLostFocusの既存値、無通信区間後の進行/停止理由を記録。継続か説明できる安全停止かを区別し、成功に丸めない。focus復帰で終了jobが復活しない |
| H 旧上限超え（追加確認） | `prepare`、[extended.json](requests/extended.json) | 1,500tickまで進み `succeeded`。旧1,200tickで打ち切られず、terminal後に解放。24時間連続実測とは扱わない |

補充タイミングはserver tick、guardは実時間。低TPS・pauseによりD/Fが補充前に期限切れになった場合、材料種別の合格証拠には使えない。原因と経過を残し、同じActionを復活させず、新runで準備し直す。外部補助処理の1,200tick停止までに開始できなかったrunも不成立。

`phase:waiting_for_item` と停止した入力カウンタは解放契約に対応する公開証拠であり、物理キー内部状態そのものは公開APIにない。終了後のGUI確認、control解放と合わせて記録する。Escでcomputer-use自体が中断した場合は、製品のEsc停止試験が成功したとは記録しない。

## 終了と引き継ぎ

全jobのterminal・control解放を確認してからMCP OFF、`/function v2_long:stop`、必要なscoreboard記録、保存終了。記録コマンドは `/scoreboard players get #placed mcv2long` と `/scoreboard players get #refilled mcv2long`。遅い補充/別材料のrunではplacedが1のまま、正常補充のrunでは2以上を期待する。これは補助処理側の証拠として、公開MCPの結果と分けて保存する。

ゲームJVM停止を確認し、今回の開始前baselineへsave・MOD・設定を復元してfile数/hashを照合する。後から走るscheduleもsave復旧対象に含む。通常profileへのJAR導入は隔離結果を受けて差し替え担当が行い、実体配置と起動後schemaの両方を記録する。

通常の粉硬化装置ではfixtureを導入せず、現場の準備・材料補充方法を確認して短い運用試験だけ行う。今回の準備やmock/static成功を、粉硬化・非アクティブ運転・24時間運転の実機合格へ読み替えない。
