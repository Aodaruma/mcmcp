# 公開API v2の隔離smoke test

`Invoke-McmcpV2Smoke.ps1`は実装者が実行する決定的な機能試験です。fresh LLMによる自律評価ではありません。mock試験の成功も実ゲーム合格とは扱いません。

## 準備と復旧

1. 使用を許可された隔離Docker、または独立したローカル検証profileで実施します。12:00～23:00 JSTはローカルPCのみ、23:00～12:00 JSTはaod-mimoidも利用できます。稼働中の他担当・通常profileへ割り込みません。
2. 停止中の検証instanceについて、save全体、MOD、instance設定、optionsと変更予定のfixture設定を退避し、相対path／SHA-256／file数を記録します。source commit、candidate JARとharnessのhash、実際の導入先hashを照合します。
3. fixture-adminへ`public-api-v2`と`public-api-v2-water-stop`のmanifestとsetupを導入します。`status → validate → apply`でbase fixtureを適用します。`prepare-ui.mcfunction`はrestricted loaderへ渡さず、隔離ゲームのcommand UIでT0前に一度だけ実行します。cowを重複召喚せず、再試験はsave復旧から始めます。
4. T0前のfixture確認とUIのMCP ON後、`get_mcp_status.world_session_id`を取得します。runnerはこのsessionと範囲を毎回照合し、MCPから開発用commandを実行しません。
5. 試験終了時は全jobと入力の停止を確認し、ゲームを保存終了、containerまたは検証用JVMを停止します。save全体・MOD・設定・追加fixtureを元へ戻し、元のfile数と全hash一致を記録します。水源は周囲へ流れるため、変更cellだけの復旧では不十分です。

ローカルで新規worldを作る場合も、fixture適用前に対象chunkをロードし、適用後の足場・座標・体力・持ち物を確認します。試験前に保存終了してsave全体のbaselineを取り、失敗後の再試験はそのbaselineを復元します。通常profileの認証情報や設定を検証資料へコピーしません。

## 実行

PowerShell 7.4以上と同じcommitの`tools/mcp`／catalogを使います。tokenはファイルpathで指定し、値をログへ出しません。各回に新しいArtifactDirectoryを指定します。

```powershell
pwsh -File tools/eval/Invoke-McmcpV2Smoke.ps1 `
  -TokenPath '/data/prism/instances/MCMCP-Validation/minecraft/config/mcmcp/mcp-token' `
  -ExpectedWorldSession '<get_mcp_statusのsession ID>' `
  -ArtifactDirectory '/data/eval-artifacts/v2-smoke/item-entity' -Phase ItemEntity
```

同じ形で`Core → Cancel → Hazard`を実行します。ItemEntityは開始位置から見えるcowを使います。Coreでは観測、inventory交換・投棄、収納take/store、gateの通常使用とraw右クリック、2cellの設置・破壊、10cellの連続採掘、script内の移動・inventory、入力反復を確認します。Cancelは実行が始まった移動入力を取り消し、その後の位置が静止することを確認します。

Cancelでプレイヤーは開始位置から動くため、Hazardの前に`agent_move({x:207,y:201,z:200})`を一度実行し、成功と指定cellへの到着を確認します。Cancel後の位置がそのままHazardの開始条件を満たすとは限りません。

Hazardはcell `(207,201,200)`内でかがみ入力を開始し、`hazard-ready.json`をatomicに作成します。独立したfixture controllerが、そのファイルの出現とMCPの同じrunning actionを確認してから、別のfixture-admin endpointで`public-api-v2-water-stop`をvalidate／applyします。runnerへadmin tokenを渡しません。30秒以内の`safety_interrupted`、同一action IDのterminal、制御の解放を確認します。入力保持中の空振りtimeoutは合格ではありません。

Hazardのterminal取得後は速やかにゲームを保存終了します。入力を解放しても水流は止まらず、この高所fixtureでは試験後にプレイヤーが押し流されて落下し得ます。2026-09-28の実機試験では停止判定後、終了までの間に落下死亡を確認しました。停止処理の確認と、その後の生存保証は区別します。水だけ消すのではなくsave全体を復元してください。

`result.json`に開始引数・action ID・terminal・readback・失敗を残します。開始応答を失ったmutationは再送せず、既知のaction IDだけを取消に使います。未確認の終了・復旧は未完了として記録してください。

runner単体の失敗経路検査:

```powershell
pwsh -File tools/eval/Test-McmcpV2Smoke.ps1
```

この検査は誤session、応答消失、job失敗、照会失敗を模擬し、勝手な再送や誤った成功判定を検出します。実ゲームの成功証拠には含めません。

## 所持収納と使用中停止の追加試験

製品JARに加え、対応済みのSophisticated Backpacks 3.25.90.2084／Core 1.4.99.2265を専用profileへ導入します。組込みprofileが要求するSHA-256との一致を確認し、通常profileの設定や収納dataは流用しません。

T0前に`prepare-storage-stop-ui.mcfunction`をゲームUIで実行します。hotbar slot 2に空のbackpack、slot 3にshieldを配置します。新しいbackpackは通常UIで一度開閉して個体IDを初期化します。storageの出し入れはMCP試験に任せ、snow blockを所持品に3個以上用意します。この準備はrestricted admin loaderの対象外です。

| Phase | 試験と外部操作 |
|---|---|
| `Storage` | slot 2の空収納inspect → store3 → 再inspect → take2 → 再inspect。各数量とプレイヤー側の差分、未確定数量なしを確認 |
| `ItemCancel` | shield使用の受付・稼働3tick以上を確認してcancel。terminal後に1tickの新しい使用が成功することも確認 |
| `Escape` | `stop-ready.json`と稼働を確認した操作者がEscを一度押す。`failed / local_emergency_key`と入力所有終了を確認 |
| `UiOff` | T0前にchat画面を開き、`stop-ready.json`後に画面のMCPボタンをOFFにする。Escで先に止めず、`local_ui_disabled`とcontrol OFFを確認 |
| `WorldChange` | 下記の独立したfixture操作で使用中に次元を変更。`world_boundary`、新session、control OFFを確認 |

使用中停止は`hold_ticks:1000`のshieldを一度だけ使用し、外部停止を最大40秒待ちます。稼働前の失敗、自然完了、異なる停止理由は合格にしません。開始応答を失った操作は再送しません。UI OFFと次元変更の後は手動で再許可し、1tickのshield使用が終了できることを別途確認します。

WorldChangeでは専用datapackへ`prepare-dimension-ui.mcfunction`、`prepare-destination.mcfunction`、`world-change.mcfunction`を、それぞれ`v2smoke:prepare_dimension`、`v2smoke:prepare_destination`、`v2smoke:world_change`として配置します。T0前にprepare_dimensionを一度実行し、netherのchunkロード後に25cellの足場を作ります。`(200,200,200)`がstone、その上2cellがairであることを準備側で確認してから進みます。

MCP READYで、ゲームUIから`/schedule function v2smoke:world_change 15s replace`を予約し、直後にWorldChangeを開始します。予約関数は準備時に付けたtagのプレイヤーだけをnetherの`(200.5,201,200.5)`へ移し、tagを外します。runnerへcommandやadmin tokenを渡しません。移動前の実稼働を証拠に残し、移動後はdimension・座標・体力と、OFF中の新規操作拒否を確認します。新sessionがまだ現れない場合は未完了として扱います。予定どおりに開始できなかった場合は予約を準備側で解除し、成功扱いで再送しません。

追加runnerの数量不一致・未確定移送・早期終了・停止理由・OFF/session確認のmock:

```powershell
pwsh -File tools/eval/Test-McmcpV2StorageStopSmoke.ps1
```

所持収納transferの応答待ち取消、通信遅延、他MOD収納、次元変更以外の切断・再接続は、この追加smokeとは別の検証です。終了時はgame/JVMを停止し、datapack・次元save・MODも含むprofile全体を元へ復元します。

## 優先機能の追加smoke（2026-09-29）

`Invoke-McmcpV2Trial.ps1`は同じMCP専用clientで9フェーズを検証します。これは決定的な機能smokeであり、fresh LLM評価ではありません。事前に専用profileを退避し、Minecraft 26.2のdatapackへ以下を配置してください。一般profileへfixtureを導入しないでください。

| 元ファイル | 隔離datapackのfunction名 |
|---|---|
| `prepare-trial-ui.mcfunction` | `v2trial:prepare` |
| `prepare-path-ui.mcfunction` | `v2trial:path` |
| `prepare-bridge-ui.mcfunction` | `v2trial:bridge` |
| `prepare-cancel-ui.mcfunction` | `v2trial:cancel` |

これらはrestricted admin loaderの対象外で、T0前のゲームUIから実行します。`path`等は内部で`prepare`を呼び、専用範囲のworldと所持品を初期化します。制御ON後に最新sessionを取得し、フェーズごとに新しい出力先を指定します。

1. `v2trial:path` → `PathDefault` → `PathClear`。既定で壁を壊さないことと、許可時だけ2cellを破壊して到着することを確認。
2. `v2trial:bridge` → `Bridge`。明示材料で2cellの足場を補充し、到着と変更結果を確認。
3. `v2trial:cancel` → `PathCancel`。障害物処理開始後の取消と入力所有終了を確認。
4. `v2trial:prepare` → `Entity` → `Conditions` → `Menu` → `Construction`。遠方entity接近、成立／未観測条件、menu数量不一致の拒否と全stack移動、施工pause/resume。
5. `v2trial:prepare` → `Special`。植物→ベッド→扉の順で配置し、各2cellのserver確認を照合。他の設置物で支持面を遮らないよう独立して実行。

```powershell
./tools/eval/Invoke-McmcpV2Trial.ps1 -TokenPath '対象のmcp-tokenへのpath' `
  -ExpectedWorldSession '現在のsession ID' -ArtifactDirectory '新しい結果フォルダ' -Phase Bridge
```

試験終了後はゲームとlauncherを閉じ、専用profile全体を復旧して個数・全hashを照合します。失敗runは消さず、準備ミス・製品修正・残る制約を区別してください。[実施記録](../../../../docs/experiments/20260929_v2_trial_local.md)に成功・失敗と導入JARの対応を記載しています。
