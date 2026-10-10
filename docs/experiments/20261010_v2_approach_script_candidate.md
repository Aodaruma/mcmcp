# 2026-10-10 接近先選定とscript子操作の診断（R19候補）

R18の検証済みJARは固定し、追加修正をR19として分離した。R19はソース試験・ビルドまで完了した候補で、隔離Minecraftでの実機確認と通常導入は未実施。通常ゲーム・設備・画面の操作権は地図アート担当が保持する。

## 接近先の選定

上部チェストに `advance:true` で接近した際、実際の操作reachを満たさない屋根へ移動し、対象に届かず終了した現場報告に対応した。従来の接近処理は対象blockと足元cellの距離で中継点を選んでいた。

収納、精錬・醸造等の設備、ブロックへのinteract、破壊の接近処理を共通化した。新しい到着条件は次のとおり。

- 対象の現行revision・期限内の観測済み面を使い、面ごとの新しいray hitを最大6点まで接近計画のヒントとする。未観測blockの状態を公開しない。
- 安全な終点として認められた既知cellのみを候補にする。未支持の梯子途中を最終到着点へ昇格させない。
- 目線の高さ、cell内の支持高の幅、移動許容誤差0.35 blockを含む距離が、nativeのblock reachと既存上限4.5 blockの両方に収まることを要求する。
- 候補中心の目線から観測済み面までのblock rayが対象に届くことを確認する。移動後の実際の目線・crosshair・fresh観測・入力直前の安全条件は改めて確認する。接近計画だけで操作を許可しない。
- 現在の足元cellと対象が局所安全半径6 block以内なら、操作条件を満たさない中継点への迂回を行わない。経路がない・観測がない場合は有界に待機して停止する。地図の更新だけで待機カウンターを0に戻さない。
- 6 blockより遠い目標には、既存の有限な段階移動を維持する。毎tickの衝突・支持・health判定、1 blockの通常下降制限は変更していない。

設置は空気のtargetそのものではなく隣の支持面を使うため、今回の共通化対象には含めていない。MODごとの特殊形状、狭い通路、変動する設備、遠方から近傍への切替は実機での回帰確認を要する。通常環境の帰路での被ダメージは、落下・敵等の原因を確定できていない。

## scriptの子操作結果

`agent_get_action(include_result:true)` の親script終端結果へ `result.last_command` を追加した。明示的な `report()` に到達する前に子操作が失敗しても、runnerが返した最後の子操作を保持する。

```json
{
  "last_command": {
    "command": "inventory",
    "outcome": "failure",
    "action": {
      "action_id": "子操作のID",
      "kind": "inventory",
      "state": "failed",
      "stop_reason": "container_quantity_not_confirmed",
      "result": {
        "requested_count": 10,
        "confirmed_count": 2,
        "unconfirmed": true,
        "release_pending": false,
        "effects": [{"verification": "unknown"}]
      }
    },
    "truncated": false
  }
}
```

上記は構造例で、実機で取得した結果ではない。

- 保存は子payloadのコピー256ノード、子payloadの深さ8、wrapperを含むUTF-8 JSON 8 KiBまで。`report`・script workとは独立した領域で、不変のコピーを作る。ID・状態・失敗・数量・未確認・解放待ち・effectsを大きいitem一覧より優先する。
- 上限で省略した場合は `truncated:true`。省略されたeffectsを変更なしと解釈しない。`unknown` をconfirmedへ変更せず、未確認操作を再送しない。
- 実行受付前の失敗は `action:{}` として、前の成功childのIDを流用しない。割込み等で待機を離れる場合、存在するchildのその時点の公開snapshotを保存するため、`action.state` が非終端の場合もある。
- `last_command` は最後にrunnerが返した呼出しのsnapshotで、子の全履歴や進行中childを常時公開するものではない。`on_failure:continue` 後に次の操作が返れば置き換わる。操作を始めなかったscriptには付かない。
- 親の終端確定は引き続き入力解放を待つ。`completed_commands` は確認済みの成功childだけを数える。失敗の許容範囲、自動再試行、保存・再開の契約は変更しない。

公開15ツールの入出力schemaは据え置き。既存のv2 `result` 内の追加情報として返し、catalogの説明とschema適合試験を更新した。

## 完了した試験

Java 1,723件、harness 14件、admin bridge 28件、計1,765件が失敗・skipなしで成功した。`test harnessTest adminBridgeTest verifyHarnessIsolation build` が成功。追加16件は、届かない屋根、reachと支持高・位置誤差、遮蔽・未観測、低い天井、面の失効、近傍での無駄な迂回拒否／遠方継続、未支持梯子終点拒否、部分移送UNKNOWN、report満杯、取消・安全境界、受付失敗、深い／過大／循環データ、不変性、公開結果schemaを扱う。

`tools/check-source.ps1 -SkipJava` も成功した。transport 15件、blueprint Python 4件、台帳・checkpoint・capability gateとv2 smoke（7件）・storage/stop smoke（13件）のPowerShell mockを含む。いずれもMinecraftに接続しない試験であり、実機成功として扱わない。

## 次の隔離実機試験

この追加実装は12:00 JST以降に行った。[AGENTS.md](../../AGENTS.md)の「検証は12:00～23:00 JSTにはこのPCのみで行い、23:00～12:00 JSTには許可済みSSH `aod-mimoid`の隔離Dockerも使える」に従い、aod-mimoidのDockerを再起動していない。R18試験の元66ファイル復元・container／Docker Desktop停止を維持する。

次回は新しいbaselineとleaseを作り、次を確認する。R18の復元済みbaselineを新しい試験の復元元として再利用しない。

| 固定fixture | 入力と期待 |
| --- | --- |
| `approach_chest` | `agent_inventory(operation:"inspect",target:"container",x:309,y:101,z:305,advance:true,max_distance:20,max_duration_ticks:500)`。上段から1段ずつ降り、届く足場で開き、health低下・cursor残留なし。 |
| `approach_roof` | 同じtarget。屋根にいる状態で操作可能経路なし。下降を強行せず、移動・開操作をせず有界停止。 |
| `approach_wall` | 同じtarget。手前の壁で遮蔽し、開操作なしで有界停止。 |
| `approach_far` | 同じinspectでtarget x:319、max_distance:32。既知区間の段階移動後に近傍の操作条件へ切り替わり、成功する。 |
| `storage_drift` | `report` 32件の後、食料take2をscriptで1回呼ぶ。fixture注入後の失敗child ID・confirmed_count・UNKNOWN/effectsを親に保持し、後続takeを実行しない。 |
| `storage_tab` | 成功するinspect、有限take/store、取消のscriptでlast_commandを取得し、数量・cursor・reportと照合。 |

すべてhealth・位置・cursor・入力解放・READY/jobなしを記録する。予想外のcursor等で解放を保留した場合は、自動再送せずR18で確認した診断・取消／通常終了の手順を使う。終了時に当該試験baselineの全ファイルhashを照合して復元し、container・Docker Desktopを停止する。


## 候補の分離

ローカル成果物は `artifacts/v2-approach-script-20261010/candidate-r19`。製品JAR SHA-256は `db755a95d8540344ae69b58cb78c15ba3d05d29fc9f9753a5e3fd519f9ff1dd8`、catalog SHA-256は `d24eb671e0204de6f4c1214b276b52796f2ec407b755944d2a2b1637dd11d733`。JAR内catalogとの完全一致とharnessクラス不在を確認した。fixture用JARは別フォルダー `fixture-r19` へ置き、通常環境には導入しない。

R18製品SHA-256 `6ebd98e76dadc0e6333a9879138064a8494e3b10d663f83fe2a5f68092dea13a` の不変を確認した。R19の存在は通常差し替えの指示ではなく、実機試験後に別途検証結果を引き継ぐ。
