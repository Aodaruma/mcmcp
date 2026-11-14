# 自動緊急回復とエリトラ飛行：R19導入・検証記録

2026-10-12 追記：通常導入後の押し出し復帰・設置停止の調査から、足場占有時の無関係な探索を抑えるR20候補を準備した。[追加検証](20261012_v2_floor_recovery.md)を参照。通常はR19、元の短時間停止は厳密再現未完了。

2026-10-12 導入後追記（施工担当チャットからの報告）：通常「くらふとぶ！」へのR19差し替えと復帰が完了。差し替え担当が実JVMの製品hash `51acd397…b50252`、凍結17 tools catalog `da3dc676…`、11設定を照合し、06:19 JSTに所有を返却した。施工担当は新session `02ec383e-757e-45e5-b683-fb08d34531e4`でHP20・food18・XP2560、READY／Actionなし、足元支持を確認。その後、agent_buildの行折返し3床（entry13605..13608）が8.18秒で成功し、新規観測で全3座標を照合した。黒stack64を維持し自動補充も継続。報告時の確定数は床13443／松明165。監査参照は施工担当の `construction/r19-root-resume-20261012.json`、`r19-pilot-verified-20261012.json`、`blueprint-013605-013608.result.json`。この開発チャットでは通常ゲームを操作していない。松明末尾問題の回避策を維持し、R19で完全修正とは扱わない。以下の候補・未導入表記は開発時点の履歴。

2026-10-12 05:40 JST: **R19は隔離実機で飛行救助→上階帰還→戦闘→設計図10ブロックの完工を2回確認し、再遭遇・行商人接触・高速飛行・取消・補充の回帰確認も完了。導入候補として準備済み、通常「くらふとぶ！」へは未導入**。自動1918件とsource checks成功。元66/world61ファイルをhash一致で復元し、Docker/WSL停止・lease解放済み。通常導入済みの設計図R2とは区別する。最新結果と制約は末尾のR19節を参照する。

23:34 JST追記：利用者のcloudflared更新後、開発PCの2026.10.0とSSH exit 0・hostname `aod` を確認。接続先のsshd／CloudflaredはRunningでlabも存在する。SSH経由の検証準備を再開する。Codex遠隔projectの一覧復帰は未確認。[接続復旧記録](20261010_cloudflared_ssh_recovery.md)。

## 候補へ追加した動作

- 実行中のv2 actionで落下・予期しない水没・火／溶岩・窒息・実際の攻撃・プレイヤーの押し／釣り竿を検出し、元の入力を解放して有限の救助へ移る。READYで待機中には起動しない。
- エリトラと安全な花火、水への着水、水バケツ、クモの巣、通常ボート、スライム・干草・蜂蜜・ベッドの順で利用条件を確認する。水の蒸発属性を確認し、既設ベッド等の危険な右クリック先を避ける。
- Mobは受けた攻撃またはプレイヤーへの敵対先の証拠がある場合だけ反撃候補とする。弓・剣・斧・その他道具の優先順。プレイヤーへの反撃は行わない。中立エンダーマンを自動視点操作で見つめることも避ける。
- プレイヤーからの攻撃／妨害時の定型チャット、登録済みWaystonesの指定先への退避を設定できる。任意のMOD輸送機構を自動探索・実行する機能は含まない。
- **自動切断は既定OFF**。有効時だけ対プレイヤー攻撃や致命的危険で離脱候補とし、自動再接続は行わない。
- 救助中の状態は元actionとscript親の `result.recovery` に保持する。安全を確認してから再計画可能な移動・設置・破壊だけ再開する。未確認の操作は再送しない。元actionの期限は延長せず、期限切れなら作業を再開しない。救助自身には別の有限期限がある。
- 再開できない操作も、元入力の解放と救助の終了が済むまでactionの終端を返さない。Esc・OFF・取消・world変更で救助も停止し、解放に失敗した所有者は後続tickの解放再試行へ保持する。

### 2026-10-12 R8～R10：帰還時の敵と中立Mobの接触

救助後も各作業tickの前に危険を再確認する。攻撃元の記録を直近1体から最大64体へ変更し、同じaction（scriptでは同じ親action）の間は相手のUUIDを保持する。元の10秒の直近攻撃期間を過ぎた相手は、再び見えたときにこちらを向いた攻撃姿勢／襲撃者のクロスボウ装填姿勢、または現在の攻撃対象の証拠を確認してから反撃候補にする。別の相手を狙うことが分かった場合は除外する。未観測の位置追跡、Mob種だけでの先制攻撃、以前攻撃したが現在平静な相手への無条件反撃は行わない。

近くにいるだけの行商人・動物は作業を妨げない。接触と水平変位を検出した場合は `mob_interference` として既知の安全な位置への退避／待機を行い、安全継続時間を満たしてから再開可能な作業へ戻る。この理由では反撃・対プレイヤー用チャット・自動切断を行わない。窮地からの位置調整にも既存の距離・時間・入力解放の上限が適用される。

R8の接触試験では、停止後に同じ行商人へ進み直す反復を検出した。R9で毎回見えている近傍のMob／プレイヤーの占有範囲を経路候補から一時的に除外し、R10で接触対象から水平方向0.75 blocksの余裕を確保してから安全継続時間を数えるよう修正。単に押す速度が低下したことを再開条件にしない。地形の記憶は変更せず、相手が動けば次の観測で再利用できる。未知の迂回路や完全に塞がった通路を通れるという保証は含まない。

R8ローカル自動試験は1906件、failure/error 0、build成功。複数の既知攻撃元への再遭遇、攻撃姿勢の再確認、親action／worldの境界、記録件数の上限、中立Mob接触時の方針、毎tickの救助判定の配線を検査した。以下のR7以前の実機合否とは区別する。エリトラ救助から帰還・戦闘・施工を続ける全工程は未合格。

R10は1910件、failure/error 0、build成功。身体との接触を避ける迂回、接触中の安全な離脱、地形を消さない一時除外、速度が止まっても十分に離れるまで再開しない判定を追加試験した。

`agent_move` の候補schemaに `mode:"elytra"` と `max_rockets`（0～64、既定4）を追加。既定 `mode:"auto"` は従来の移動。飛行は座標／相対指定に対応し、clear_path・bridge_block・sneak・非ゼロのmax_fall_damageとは併用しない。通常の装備変更・ジャンプ・アイテム使用を通し、飛行速度や座標を書き換えない。

## 高速飛行の観測

前方の観測を毎tick更新し、現在速度・旋回・通信遅延・花火の残り推進時間を含む有限の軌道を予測する。即時操舵と遅延操舵の両方に観測済みの空間が必要。候補の全予測区間を確認できない場合、新しい花火を使用しない。未観測・未ロードを空気として扱わず、飛行のためのchunk取得も行わない。

観測の既定上限は96 blocks、設定範囲16～128で描画距離にも制約される。1 tickのcell照合は2048件まで。花火は爆発成分がなく、flight durationが1以下の通常品に限定する。自然な慣性や既に使った花火は入力解放では消えない。全速度・通信状態で安全に止まれる保証ではなく、実機の高速／壁／未観測境界の検証が必要。

設計の参考は [Baritone ElytraBehavior](https://github.com/cabaletta/baritone/blob/master/src/main/java/baritone/process/elytra/ElytraBehavior.java) と [ElytraProcess](https://github.com/cabaletta/baritone/blob/master/src/main/java/baritone/process/ElytraProcess.java)。コードは移植せず、対象26.2の物理処理と既存入力所有を使う。

## 設定の候補

Mods → MCMCPの設定画面を登録し、日英の表示名を追加した。実GUI表示は未検証。

| 設定 | 新規設定の既定 |
| --- | --- |
| automatic_recovery / recovery_resume / recovery_retaliate / recovery_elytra | true |
| recovery_disconnect | false |
| recovery_player_message | 空文字：チャットなし |
| recovery_waystone_destination | 空文字：Waystones退避なし |
| recovery_waystone_max_levels / recovery_waystone_max_points | 0 / 0 |
| recovery_safe_ticks | 100 ticks |
| recovery_max_ticks / recovery_max_distance | 600 ticks / 128 blocks |
| flight_observation_blocks | 96 blocks |

既存設定に保存された旧上限は維持する。救助のカメラ・interaction・設置上限も既存設定で制約する。

## ローカル検証

`gradlew test harnessTest adminBridgeTest verifyHarnessIsolation build` 成功。最新候補R7はtest 1856件、harnessTest 14件、adminBridgeTest 28件、計1898件、failure/error 0。飛行の連続予測による着地、発進直後の落下検出、近距離の過剰boost回避、救助中取消の終端保持とruntimeでの要求順序を追加した。`git diff --check` は問題なし（改行正規化警告のみ）。直前の `tools/check-source.ps1 -SkipJava` も成功。

追加試験は飛行予測と未知空間でのboost禁止、救助方針／Nether水使用禁止／切断既定OFF、再開epoch、確定済みblockの二重実行防止、救助中の結果保持と終端保留、移動の再計画と元予算保持、schemaを含む。

独立Codex CLIレビューは、インストール済みCLIが設定モデルに対応しないため開始できなかった。レビュー済みと扱わない。

## 残作業

1. 固定fixtureとserver側oracleは用意済み。次回は再度元world／設定のbackup・hashと所有を取得し、以下の未検証ケースへ進む。
2. エリトラの地上発進、落下中展開、高速／壁／未観測境界、着地、取消を検証する。候補探索の観測予算と負荷、通信補正時の停止、装備変更のserver確認、要求座標の到達判定を見直す。
3. 水バケツ・クモの巣・ボート・軽減block・既存水面、敵対Mobと中立Mob、プレイヤー妨害、Waystonesの成功と中断を確認する。飛行不能後の救助継続も確認する。
4. Mobへの反撃後の移動再開と、R7の取消結果修正は隔離実機で確認済み。施工への復帰、script子の失敗保持、期限・Esc・OFF・world変更・解放再試行を実機で確認する。再開を保証できない操作の範囲を仕様へ明記する。
5. 成功した候補のcatalog・利用ガイド・固定hash・PR・CIを同期し、検証済みJARとSHA-256を差し替え担当へ渡す。通常ゲームへ開発担当から割り込まない。

## 2026-10-11 23:41以降の隔離実機

既存world・MOD・設定を66ファイルのhashとともにbackupし、45分lease（期限10月12日00:26 JST）で隔離Dockerを利用。製品JARと分離されたtest-harnessだけが固定領域の整地・落下床の除去・敵生成を行う。製品側は通常のMCPとゲーム入力だけを使う。対照用oracleはserverで最小HP、最大速度、飛行tick、設置block、inventoryを記録する。fixtureのsaturationによる自然回復があるため、最終HPだけで無傷と判定しない。

| 候補・試験 | 観測結果 |
| --- | --- |
| R1 落下検出 | 支持床消失直後に通常停止が先行。onGroundの更新前でも、局所支持消失＋非上昇を検出するよう修正。 |
| R2 水バケツ #21 | server設置確認、最小HP20、乾いた場所へ退避、100 ticks安定。action `31588a3c-ca14-4f0e-9cd0-ef33dcaeb0b9`。 |
| R3 クモの巣 #31 | 設置・server ACK確認、最小HP20。action `6508001c-0523-4d79-a7aa-30f5e08ce088`。 |
| R3 ボート #32 | 設置＋乗船の2操作、最小HP20。接地したボート上で安定終了。乗車したまま地上作業は再開しない。action `e9573426-cba2-44c3-a5ba-ff58c4aec8e9`。 |
| R3 スライム #33 | 設置確認、最小HP20、跳ね返り後に安定。移動累計約100.94 blocks、411 ticks。action `88f0a39d-f8f2-4e92-bc2a-5fb6bfe03d54`。 |
| R3 干草 #34 | 設置確認、最小HP17。軽減であって無傷ではない。action `661c7fa5-af60-4d97-a120-8f009f85bd87`。 |
| R3 ベッド #35 | 2 blockの設置を確認、最小HP14、安定終了。Overworldのみ。action `272fb6fe-eb95-4f24-b482-8c18f87e0e80`。 |
| R4 広い水面 #41 | R3の浮上・沈下反復を修正。観測した水面を進み岸へ到達、最小HP20、air300、373 ticksで安定。action `7f081031-4606-4269-8dce-1621f4ba25d5`。 |
| R5 Mob＋移動再開 #52 | 攻撃された後に剣3回で敵を倒し、287 ticksの救助後に元の移動を再開。29.096秒で目的地到達、`state=succeeded`、`result.recovery.status=resumed`。最小HP17・最終20。action `fdac0da3-6a33-4848-9245-ab59fe015993`。 |
| R2 非敵対Mob #28 | cowの近くで500 ticksの入力を完了。救助発動・HP損失なし。牛以外の中立MobやEndermanの実機試験とは区別する。 |

水バケツ・クモの巣などの元actionは、再開不能なraw入力を使用したため `preempted_for_recovery` で失敗として終了する。救助の成功は `result.recovery.status=stable` に分けて返る。

R2のスライム・干草・ベッド・水面試験は、直前に製品が置いたボートをfixtureが回収できず条件が混ざったため採用しない。R3で固定された使い捨て試験領域内のボートとdropを毎回回収して再試験した。R2のweb/boatで効果確認がfalseのまま閉じられた問題も、server確認の待機とボートの安定判定を修正し、R3で確認した。

### 飛行で確認した失敗

- R1 #4：発進・花火2発の消費・149 flying ticks・最大約1.68 blocks/tickを確認したが、目的地付近を通り過ぎ、max_distance=128で `safety_interrupted`。入力解放後も自然慣性が残り、その後落下死した。成功として扱わない。
- R3/R4 #37/#42：近い低所への新しい花火を抑制。発進台から十分離れる前に `flight_no_observed_escape_corridor` で終了し、台へ着地。R4では観測した発進方向への助走を追加したが未解決。
- R5 #51：安全な短い着地の再発進を追加したが、滑空終了と接地フラグの更新間に `flight_ended_aloft`。花火を使用しない飛行で、最終item使用tickを基準にしていた待機期限が誤りだった。R6は直近の滑空tickからの猶予へ修正。
- R5以降は、着地後2 blocks以内なら既存の局所安全付き最終位置調整へ接続する。小数点座標のtoleranceを1 blockに緩めて成功扱いしない。
- R6 #61：143 flying ticks、花火0発、最大約0.77 blocks/tick、最小HP20で低地へ着地。ただし目標 `(335.5,101,310.5)` から約3.4 blocks離れ、`flight_landed_short_of_goal`。action `7d82d9f8-3d22-4b61-873c-e0f3b81a16b2`。再発進3回を使い切った。安全に着地したことと座標到達の成功を区別する。
- R6 落下中救助 #65：エリトラ展開・花火1発・上昇を確認。49 ticks・約33.24 blocks移動した時点でカメラ累計345.01度となり、次の操作の余裕不足で `recovery_camera_budget`。終端後oracleは体力20・66 flying ticks・最大約1.58 blocks/tickだが、依然空中。action `7e76161f-f43b-4748-bb0f-63025b236a43`。救助成功ではなく、直後に隔離GUIでpauseして保存終了した。落下で失われた元の安全地点へ向かい続けることと、着地前に旋回予算が切れることを次回修正する。単に上限を外す解決はしない。

### 救助中の取消と最新候補

R6 #64は救助40 ticks・web設置済みの時点で `agent_cancel_action` を送り、41 ticksで救助と操作は終了、READY・実行中actionなしになった。しかし外側のstateがfailed、cancel_requested=false、内側recovery.statusだけcancelledだった。action `bb96b51d-4b1e-409f-8f99-74815669ff2d`。先の#62も同じ問題で、単なる試験タイミングの遅れとは扱わない。

原因はruntimeが取消要求をstoreへ記録する前に救助の終端保留を解除し、元の失敗を確定していたこと。R7で要求を先に記録し、救助入力解放後に既存の各ownerのcancelへ接続した。storeの部分結果保持と、runtimeの要求→解放の順序を自動試験で確認。**10月12日R9でこの修正の実機再試験が成功**（下記）。

| 候補 | 製品JAR SHA-256 |
| --- | --- |
| R3（落下道具） | `0ddeb15639a3f6d73a41204fd1965eb5b17242bcf459def64c2525e373546e80` |
| R4（水面退避） | `3e7b01cc4fe09a1ecd59f462bee50b59a07f62ba31b14cb05320a520a8da125d` |
| R5（反撃後の移動再開） | `6fdf65b41c1a12be9b9caf714a3e18680fc21838454e51e55409580c75e7c673` |
| R6（10月11日最終の実機候補） | `962149ea3c6f7cbc347959683201009426c6686aa0c655982caf8d6a28b07726` |
| R7（取消入口修正、自動試験のみ） | `7c8d154d5f183f3cfb7d01634f281f8693b41cd3a131b209c0039ddc41b3d35d` |

全候補はsource基点`c317ecd`からの未commit作業tree。通常配布用として合格したJARではない。R3以降のharnessは `a7cd6c5bbcf6ba6fd6ea052ed75f2c165f46990fc4825944407cbd0c12a8eb85`。ローカル非公開資料 `artifacts/v2-emergency-20261011/evidence/` に公開tool応答JSONLとserver oracle、復元記録を保存した。古い候補の同名summaryが上書きされたケースでは、request番号入りJSONLを一次記録とする。

### 復元

10月12日00:24:42 JSTに元66ファイル／world61ファイルのhash一致、fixtureと追加resourceの回収、container停止を確認。その後Docker process 0、Ubuntuとdocker-desktop WSLのStopped、lease正常終了・lock消失を確認。期限00:26:22の延長は行っていない。通常「くらふとぶ！」は操作・再起動・JAR交換していない。

現段階で高速飛行・壁回避・未知空間境界・落下中エリトラ救助は合格していない。装備変更のserver証拠、飛行中に予算を使い切る前の着地余裕、救助先の足場消失、干渉・通信補正・中断なども残課題。通常環境への差し替えは行わない。

## 2026-10-12 帰還・接触・取消の隔離実機

別runで元66ファイルを再backupし、02:41:01～03:26:01 JSTの45分leaseを取得。新しいharnessは攻撃後に同じUUIDのゾンビを13秒間離してから再遭遇させる固定ケースと、行商人の近接／接触ケースを持つ。これはエリトラで帰還する全工程の試験とは区別する。

| ケース | 確認した結果 |
| --- | --- |
| R8 行商人が近くにいるだけ #2 | 500 ticksのraw入力を成功。救助・反撃なし、双方HP20。action `d4f96f04-f848-4b11-aff3-7597a5dea82d`。 |
| R8/R9 接触後の移動 #4/#11 | 退避と再進入を繰り返してtick上限で停止。無傷だが目的地未到達なので不合格。R10の離隔判定を追加する根拠とした。 |
| R9 同じ敵との再遭遇 #12 | 260 ticks離れた同じ敵へ剣で3回反撃。救助361 ticksの後に元の移動を再開し、約71.27秒で目的地 `(340.5,121,310.5)` 付近へ到達。最小HP17、最終HP20、`succeeded`・`recovery.status=resumed`。action `28828da2-4e7a-467b-bdcf-63313f1d98fb`。再遭遇後に被弾しているため、無被弾での先制防御を証明した試験とはしない。 |
| R9 救助中の取消 #74 | web設置確認後、救助40 ticks以降に取消。42 ticksで `cancelled`・`cancel_requested=true`・`stop_reason=client_request`、READY・running_action_idなし。確定済みwebの記録を保持。action `1e09172e-c39d-4584-a31f-df59db1d5507`。 |
| R10 行商人の接触から移動再開 #21 | 救助109 ticks・距離1.439 blocks・interaction 0。その後迂回して約15.39秒で目的地へ到達、`succeeded`・`recovery.status=resumed`。双方HP20。action `55d40915-c7c8-4643-96de-f684ca5b0f87`。 |
| R10 行商人が近くにいるだけ #22 | 最新候補でも500 ticksのraw入力が成功。救助・反撃なし、双方HP20。action `428b72fe-dc32-488d-9cec-c7c9d4e879a9`。 |

初回R8には旧保存設定（救助200 ticks／16 blocks）が残り、再遭遇後の安定確認とweb取消要求前に予算が終了した（#5/#64）。後者は取消要求そのものに達しておらず、取消不具合の再発とは扱わない。R9以降はこの隔離設定だけ候補既定の600 ticks／128 blocksへ合わせ、camera 360度・interaction 8の上限を維持した。元設定は復元対象。

| 候補 | 製品JAR SHA-256 |
| --- | --- |
| R8 | `a2e0d0211c07d77a04bc6197794b8f853c9753eb00e1d8ed633a19859a594574` |
| R9 | `218dca1456332b11d474633044dbc44f0cda90aa1ef9571a808e9c7690cd14d0` |
| R10 | `ccc20a1601fe328f08cc0d8489541f5f9f69d51f78f9af0ffaf0b930a1105a69` |

harnessは `8df08bf77fd278b629d293208f87f10fbdf6a46187f269dfd5488e3f57b30d91`。非公開のtool応答・oracle・GUI・復元記録は `artifacts/v2-emergency-20261012/` に保存する。通常環境への導入、襲撃者のクロスボウを含む各Mobの実機網羅、飛行救助→帰還→戦闘→施工の通し試験は未完了。

03:05:24 JSTに元66ファイル／world61ファイルをhash一致で復元。03:06:13にDocker process 0、続いてlease正常終了を確認した。期限03:26:01の延長や通常ゲームへの操作は行っていない。

### 差し替え担当向け

現時点の凍結候補は非公開workspaceの `artifacts/v2-emergency-20261012/product-r10.jar`（SHA-256は上表）。**installable=false**。通常の設計図R2から差し替える候補としてはまだ合格していない。エリトラは既定ONの候補機能だが着地前の予算終了が未解決で、設定OFFだけをもって全体の導入合格とはしない。

既存の設計図施工・自動補充・松明・confirmed_countの契約を変更する目的の修正ではなく、既存を含む自動1910件は成功。ただし共通runtimeと設置／破壊の危険判定へ影響するため、救助後の実施工でこれらの互換性を確認済みとはいえない。施工復帰は、確定済み1セルを保持して次セルへ進むmock試験と、未確認送信を再実行しないmock試験まで。通常導入時は新規既定値が旧保存設定を上書きしない点も確認が必要で、今回の隔離設定変更は通常profileに適用していない。

## 2026-10-12 R11～R14：着地と施工再開

03:44:02～04:29:02 JSTの別45分leaseで、元66ファイル／world61ファイルを再backupして隔離実機を検証した。R14の製品JAR SHA-256は `ce87c8458d35ec1963ebe1b8b5f6c02fcb0ec387a80393cedff2c99e7b0f9035`。非公開資料は `artifacts/v2-emergency-20261012-r11/`。通常「くらふとぶ！」の操作・設定変更・JAR交換は行っていない。

### 修正内容

- 失われた救助元の足場へ旋回し続けず、毎tickの観測から支持・頭上・危険・視線を検査した着地候補を選び直す。未知のblockを空気と扱わない。時間・距離・視点の残予算から着地へ切り替え、新しい花火を抑制する。
- 地上発進前に観測した12 blocksの通路を確認する。発進台の端で接地と滑空の更新がずれる猶予を設ける。通常飛行の着地後は、元actionの残距離・期限内で既存の地上経路探索と小数点位置調整へ接続する。
- `advance`施工の送信前preflight/preparingだけを再準備可能にする。送信後に結果未確認の操作は再送しない。
- scriptの子actionが救助中でも、親が誤って取り消さないようにする。救助中は子の終端を待ち、新しい命令を開始しない。親にも救助の再開／取消結果を記録する。

### 隔離実機の結果

| 候補・ケース | 確認した結果 |
| --- | --- |
| R12 通常飛行 #11 | 207 ticksで低所の小数点目標へ到達。111 flying ticks、花火0、最大0.7733 blocks/tick、最小HP20。action `909e351d-03c5-40b1-b4fc-5ca71a11384c`。 |
| R13 高速飛行 #22 | 100 blocks先へ234 ticksで到達。花火1、137 flying ticks、最大2.0688 blocks/tick（約41.38 blocks/s）、最小HP20。action `bf9328e3-35f7-49a9-8675-c0fbc7efbfd8`。 |
| R13 近い壁 #23 | 発進前に `elytra_takeoff_corridor_unobserved` で停止。地上のまま、花火0、飛行0、最小HP20。action `61e1db98-32b0-47ed-9139-0efa87a0bcad`。 |
| R14 script反撃→施工 #31 | 剣3回で敵を倒し、救助155 ticks・安定100 ticks後に10床と後続inventory命令を完了。30.034秒、`placed_blocks=10`、材料64→54、最小HP15／最終20、`recovery.status=resumed`。action `ef0df274-fb2b-4b4e-ab32-ddc244faf2bf`。 |
| R14 agent_build反撃→施工 #33 | 8＋2の2区間、10床を63.162秒で完了。`next_index=10`、`completed_entries=10`、`batch_complete=true`、`blueprint_complete=true`。救助460 ticks、剣3回、最小HP17／最終20。action `50a55662-c69f-4eff-8ce6-18466be4bac0`。 |
| R14 高速飛行＋壁 #34 | x360、y101～170の壁を越え、着地後に地上移動して小数点目標へ398 ticksで到達。花火1、216 flying ticks、最大1.6866 blocks/tick、最小HP20。action `b1aa1a39-8d4a-4c6b-9ee7-c6595bf270f1`。 |
| R14 足場消失→エリトラ救助 #35 | 新しい着地点へ74 flying ticks、花火0、最小HP20。救助183 ticks・移動44.026 blocks・視点238.519度で接地し、100 ticks安定。元のraw入力は再開不能のため `preempted_for_recovery`、救助は `stable`。action `ae8701be-d6a0-4627-9d25-d690062a2945`。 |
| R14 script親子の取消 #36 | 救助80 ticks以降に取消し83 ticksで終了。親子とも `cancelled`、`cancel_requested=true`、`stop_reason=client_request`。確定済み1床を保持し、再開・巻戻しなし、READY・実行中actionなし。親 `3a94281c-68b9-4c56-914b-08b5e8f898e2`、子 `d74d1eb9-2d11-4ca4-b473-dca49e5bf061`。 |
| R14 補充＋松明＋色替え #37 | 4黒床→松明→4灰床のscriptを17.205秒で完了。`placed_blocks=9`、床と松明のserver oracle一致。SophisticatedBackpacksの黒／灰各64→60、手元各64を維持、松明32→31。最小HP20。action `c1c70aa3-c148-4ce4-95b2-91fd43ddf392`。 |

fixtureの自然回復があるため最小HPと最終HPを分ける。飛行の途中で目標方向へ進んだことだけでは成功扱いしない。

R11の発進台の端での停止、R12の施工再準備拒否、R13の着地後地上移動を8 blocksで拒否する問題を順に修正した。R13のscript試験#25では親が救助開始直後に子を誤って取り消し、敵が残って後に死亡した。R14の#31/#33/#36はこの不具合に対する再試験である。

### 現時点の残件・引継ぎ条件

R14は `test harnessTest adminBridgeTest verifyHarnessIsolation build` 成功、単体1871＋harness14＋admin28＝1913件、failure/error 0。最後のfixture追加後もharness試験と製品への非混入検査は成功。`git diff --check` は問題なし。

04:30に `tools/check-source.ps1 -SkipJava` もexit 0。MCP transportのPython17件、blueprint compilerのPython6件、施工ledger／checkpoint／各capability gateとv2 runnerのmock回帰試験を完了した。実ゲームへの追加接続は行わない試験である。

落下→エリトラ着地→上階へ帰還→新しい敵への反撃→施工までを一つにした `recovery_build_flight` fixtureを追加したが、転送時の04:15 JSTにCloudflareの新規SSH認証が期限切れとなり、**この通し試験は未実施**。個別の飛行救助・反撃後施工・同じ敵との再遭遇の成功から、この通し試験を済ませたと扱わない。

復元・停止はaod-mimoid側の接続調査チャットに依頼中。期限を延長せず、元ファイルのhash一致とDocker停止を確認して記録する。現時点では **installable=false** を維持し、施工担当への更新完了通知は保留する。

04:29追記：leaseはハード期限でexit 1となった。新しい検証は開始せず、期限も延長していない。遠隔側は対象・所有・backup66件のhash一致を確認したが、元チャットの直接許可を参照できず実行確認待ち。**world復元・Docker停止は未完了**。利用者へ遠隔側確認への回答を依頼済み。復元後に実ファイルhashと停止結果を追記する。

04:41追記：再認証後のSSHから後始末を実行し、04:40:00に元66/world61ファイルをhash一致復元、04:40:53にDocker停止、lock解放・WSL停止を確認した。上記の未復元は解消。ローカル `artifacts/v2-emergency-20261012-r11/evidence/` に162ファイルの検証・復元記録を取得し、zipのremote/local SHA-256一致を確認済み。

既存設定の救助200 ticks／16 blocksは自動上書きされない。実機で使用した設定は600 ticks／128 blocks、視点360度・interaction 8・安定100 ticks。通常導入時は設定値の確認が必要。プレイヤーへの反撃は行わず、切断は既定OFF。弓、襲撃者を含む全Mob、プレイヤー妨害／チャット／切断、Waystones、通信補正、各次元の全道具をこのrunで網羅していない。取消時には入力を解放するが、飛行の自然慣性までは消せない。

## 2026-10-12 再認証後の通し試験

前runの完全復元後、04:41:42～05:26:42 JSTの別leaseを取得した。今回のleaseは期限または終了要求時に、遠隔側だけで元world／設定の復元とDocker停止を行う。資料は `artifacts/v2-emergency-20261012-return-r14/`。バックアップは同じ元66/world61ファイル。

起動時に検証用Xvfbの古い `/tmp/.X99-lock` が残り、containerが約10秒で終了した。`xdpyinfo`でdisplay不在、`pgrep -x Xvfb`でprocess不在を確認し、固定されたlock／socketだけを同じcontainerのtmp内へ退避して再起動した。認証情報や通常ゲームは変更していない。

| 候補・ケース | 結果と修正根拠 |
| --- | --- |
| R14 通し試験 #41 | 87 flying ticks・最小HP20で着地したが、救助86 ticks時点でcamera344.456度、残り16度未満になり `recovery_camera_budget`。元施工0床で失敗。action `0ba3d8d2-485d-4e75-a69c-25354f72fdb4`。 |
| R15 通し試験 #42 | 最初の飛行救助から作業を再開し、復帰用のはしごを検出して登った。しかしはしご上の停止／下降を落下扱いし、展開できないエリトラ救助を繰り返して親scriptの120秒期限に到達。最小HP19／最終20、85 flying ticks、施工0床。action `f9b3dcba-fc2a-41e7-985f-c23c5a599657`。 |

R15では予測する旋回全体が残りcamera予算に収まることを要求し、予算が少ない場合には現在のyaw／pitchを維持する候補も評価する。着地点を通り過ぎて大きく引き返す旋回を始めない。残りの旋回予算がなくても、観測済みの滑空を監視して着地を待てる。距離・時間・未知空間・取消の制約は維持する。R16では落下検出へ実際の`onClimbable`状態を渡し、はしごにつかまった停止／下降と、はしごを外れた落下を区別した。

R15 SHA-256 `1c093628fca0ff9d00e898f9957b1efa955c316856d4274e44fce8f39f4c696e`、R16 SHA-256 `a6a209c266bbdd616cafe03f9bad1d5f46bfc49d39bfc061abfe4586ebf3b5ec`。R16の自動試験・buildは成功。R16は実機再試験中で、まだ通常導入・完了通知は行わない。

### 再認証後の通し試験（続き、2026-10-12）

- R16+h15 #51: 初回エリトラ救助195 ticks、滑空88 ticks、HP20で着地。はしご中の誤救助は再発しなかった。一方、床ブロックy120で終わるはしごは上階の立ち位置y121への出口edgeを作れず、y119付近から進めず120秒でtimeout。配置0。
- h16では既存の対応形状との切り分けとして、はしごと背面支持をy121まで伸ばした（救助条件・敵出現条件・施工範囲は同じ）。R16+h16 #61は上階へ帰還し、敵出現後に再落下、その後再帰還して剣3回で反撃し施工を2セル再開。ただし120秒でtimeout、最小HP1、最終HP12.17。安全な成功とは扱わない。
- R17開発中: 最上段から1ブロック上の隣接床に出る経路を、上昇空間と水平空間を個別確認して観測。支持確認済みのLADDER上り出口だけを許可し、未支持の対角edgeや2ブロック段差は拒否。実機試験前。
- R16+h16 #62: 240秒枠でも49.26秒で反撃のcamera予算344度に達し終了。反撃と退避入力を同じtickで出し続け、剣の射程に敵が入らないことが原因。R17では装備が確定し地上で戦闘可能な場合に退避入力を解放して反撃。武器なし／低HPでは従来どおり退避。
- R17+h15 #71: 元の短いはしご形状で通し成功。action `f44c8d3c-7db6-4d10-a547-ba1722f3dd46`、70.519秒、設計図10/10、next_index=10、実ブロック10セル・所持64→54。救助着地と帰還後の敵出現がoracleでtrue、敵消失、剣による反撃を記録。最低HP15／最終20、滑空95 ticks／花火0。元の上端形状への帰還、敵対後の反撃、施工再開・完工が一連で成立。
- R18は上記に加え、反撃中は安定待ち時間を0に戻し、敵再出現時に残りの安全待ち時間を流用しない。product SHA256 `CD6BC7D4CCE4BDBF668B07C46BFBAB3A38CB536EA10070F6CE95149A57AA58C2`。ローカル1917 tests（1875 + harness14 + admin28）、harness isolation、build成功。実機確認中。
- R18+h15 #81: 39.91秒でrecovery_camera_budget。剣3回後、敵HP0.537で停止。通し試験中の最小HP17／終端HP20だが、READY移行後に残った敵に倒されたことを終了画面で確認。R17成功1回をもって安定完了としない。R18はinstallable=false。反撃の一律「残り16度未満なら終了」を、実際の1tick旋回量の確認と予算不足時の無旋回退避に変更する。
- 前runは05:24:42 JSTに元66/world61ファイルをhash一致で復元、05:25:22にDocker停止、lease解放・WSL停止確認済み。証拠zip123ファイル／9,763,596 bytes、SHA256 `571BC03CE05DB544674D040E3955CC02852943F600A8F9328A03C702E8498902`。取得・展開済み。R19は別の新規45分leaseで再開。

### R19 最終候補（2026-10-12）

product SHA256 `51ACD39756D3D01DFA42BF93F8E0671B2B1C98015DD27E6DF567A70670B50252`、h15 SHA256 `CA7FD53EED4BDEFC519F5AB84D58EFB61AD6AD217981A0F6D44D28FA80CC0D93`。

`NavigationViewLease.turnTowardWithinBudget`は1 tickの実旋回量（Vanilla floatの丸めと中立エンダーマン回避方向を含む）を残予算と比較する。残予算が16度未満でも必要な旋回が収まれば照準・反撃できる。収まらない場合は視点を変更せず、反撃器を閉じて局所観測済みの退避へ移る。camera不足だけでactionを終了させない。時間・距離・操作回数の有限上限は維持する。`combat.camera_budget_blocked`と`strategy=retreat_camera_budget`を結果で識別できる。

- ローカル: 1918 tests（1876 unit + 14 harness + 28 admin）、failure/error 0、harness isolation/build成功。`tools/check-source.ps1 -SkipJava`も全成功。
- 通し #2: action `1a2ee01e-04f0-4a59-9df4-a027de476e2c`、69.345秒、救助着地・帰還後の敵出現・剣3回反撃・敵消失・設計図10/10完工。最低HP15、最終20、滑空95 ticks、花火0、所持64→54、next_index10。
- 通し #3: action `8b401bbe-4539-4534-bc02-e40744c1b52f`、81.702秒、同じ通し条件で2回目成功。最低HP17、最終20、滑空106 ticks、花火0、剣3回、所持64→54、実10セル・next_index10。
- どちらもh15の短いはしご（床ブロック高さまで）を使用。R17の上端退出edgeの修正を実機確認。初回救助と帰還後の危険再検出が同じ親action内で動作する。
- 下記の回帰確認と元環境の復元を終えてから導入候補として通知する。通常ゲームは操作していない。
- 高速・壁越え #4: `68371a0d-b14f-46c2-ba6c-b26ab837a691`、19.507秒、HP20で成功。
- 救助中の親取消 #5: `3ea237e6-7a1f-49fa-9ec7-fbd0f6bacef1`、81救助ticksで取消、親はclient_request/cancelled、HP20。飛行入力の解放後に作業を勝手に再開しない。
- 自動補充＋黒4／松明1／灰4 #6: `87c86ddf-852e-4e93-918b-91183284127d`、16.887秒、9設置、HP20で成功。
- 施工担当報告の松明位置の近傍再現: feet `(317.440417,106,310.5)`から隣のgray support `(318,105,310)`へ松明を設置し成功。`95e1b460-5a34-478a-b777-e35d952b81f4`。報告位置を平行移動した要求feetとの差は約0.04 block以内。ただし本試験は事前に`agent_look`で支持面を向いているため、通常R2の連続施工末尾での4.39秒停止を厳密に再現・修正したとは扱わない。通常現場の安全な観測→中心へ移動→単独設置という回避策は継続可能。
- 再遭遇 #7: `4e3f5ccc-bf15-4069-a2f8-e22a2bb3f68a`、41.185秒でmove成功。同じ敵の非表示→復帰→再攻撃をserver oracleで確認し、復帰後の剣3回で対処して移動再開。最低HP17／最終20。
- 行商人接触 #8: `8b24bacf-472e-4735-9568-b64c2d8e961f`、15.410秒で成功。mob_interferenceから1.439 blocks退避・100 ticks安定後に再開、attack/interaction0、HP20。
- 近くの行商人 #9: `acd68754-5b94-4e5f-b3ea-e036caaeb826`、8.404秒で成功。救助・反撃なし、HP20。

- R19 #5の子action `a7f30dcb-2e82-41cb-9f25-bb1b4b41de0e`もcancelled/client_request/cancel_requested=trueを確認。#4は滑空216 ticks・最高1.68657 blocks/tick（約33.7 blocks/s）、最低HP20。
- R19 runは05:38:12 JSTに元66/world61ファイルをhash一致復元、05:39:06にDocker停止、05:39:20にlease解放とWSL停止を確認。証拠zip85ファイル／1,680,101 bytes、SHA256 6A949FFE0115B5178969AAB51122A294E4D428F5DA35F3DCB4B5AF477CD4DA3D。ローカル取得・展開・hash照合済み。
- R19のcamera残予算不足は旋回器の単体試験で検査。今回の実機成功2回ではcamera_budget_blocked=falseのため、予算不足による退避分岐自体の強制実機注入は未実施。
