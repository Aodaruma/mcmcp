# 観測・記憶モデル

## 目的

LLMが建築、農林業、探索、失敗回復を行うには、現在の1画面だけでなく、以前見た場所と自分が確認済みの施工結果を扱う必要があります。一方、クライアントが内部に保持するchunk dataを無制限に公開すると、壁越し・地中の現在状態を読むX-ray相当になります。

この設計では、情報を削る代わりに、情報の出所と鮮度を明示します。

## 3つの知識状態

BlockとEntityの知識は、次を混同しません。

| 状態 | 意味 |
|---|---|
| `current` | 今回の問い合わせ時点で通常の観測条件に合格した状態 |
| `last_known` | 過去に観測、または自分の通常操作後にサーバー同期まで確認した最終状態 |
| `unknown` | 現在も過去も根拠のある情報がない状態 |

`last_known`は現在も同じ状態だという保証ではありません。必ず観測tick、経過tick、出所を返します。

Blockの共通recordを`ObservedBlock`と呼び、`position / state / observed_context / knowledge / world_session_id`を一組で扱います。`get_snapshot`と`compare_block_plan`で同じ形を再利用し、過去値から鮮度情報だけが脱落することを防ぎます。`unknown`は値のない別recordであり、BlockStateを推測して埋めません。

## BlockStateは常に完全に返す

観測対象になったblockでは、`detail`指定にかかわらずregistry IDと全`BlockState` propertyを返します。payloadを減らす場合はpropertyを欠落させず、問い合わせ座標数と結果件数を制限します。

例:

```json
{
  "position": {
    "dimension": "minecraft:overworld",
    "x": 120,
    "y": 65,
    "z": -31
  },
  "state": {
    "block": "minecraft:oak_stairs",
    "properties": {
      "facing": "south",
      "half": "bottom",
      "shape": "straight",
      "waterlogged": "false"
    }
  },
  "observed_context": {
    "block_light_at_observation": 11,
    "sky_light_at_observation": 15,
    "fluid_at_observation": null,
    "replaceable_at_observation": false,
    "collision_empty_at_observation": false,
    "sturdy_faces_at_observation": ["down"]
  },
  "knowledge": {
    "currentness": "last_known",
    "provenance": "line_of_sight_observation",
    "observed_at_client_tick": 184220,
    "age_ticks": 860,
    "visible_now": false
  },
  "world_session_id": "uuid"
}
```

作物の`age`、階段の`facing/half/shape`、ドアの`open/hinge/half`、`lit`、`powered`、`waterlogged`など、BlockStateに存在するものを常時含めます。明るさはBlockStateとは別なので`block_light`と`sky_light`として返します。

`observed_context`は`observed_at_client_tick`時点の履歴であり、現在値ではありません。現在観測できたrecordにだけ`live_context.visible_faces`と`live_context.within_reach`を追加します。last-knownへ現在のplayer依存値を載せず、明るさ等も`*_at_observation`として古さを明示します。

BlockEntityの任意NBT、未開封container内容、村人POI、AI brain、server内部値はBlockStateに含めません。通常画面で確認できるcontainer内容は、所有権を持つ画面routineと`screen` scopeで別に扱います。

## 現在観測の境界

初版では、次のいずれかを満たすblockをlive observationとして扱います。

1. 現在のcamera/FOV内で、render上の表面へ視線が通る
2. crosshair raycastの対象として確認できる
3. routineが通常のinteractionを行い、その座標の結果をサーバー同期後に確認できる

単にchunkがloadedである、client Levelから`getBlockState`できる、hiddenなblock updateを受信した、という理由だけでは観測済みにしません。

ガラス、水、階段、ハーフブロック等では単純なfull-cube rayだけを使わず、視覚的な遮蔽とshapeを考慮します。Sodium等のrendererへ直接依存しないsampled visibility判定を第一候補とし、実際の見え方との一致を互換性試験で確認します。

現在のFOV外を読み取りtoolだけで360度観測したことにはしません。必要な場合は`survey_area` routineが通常操作で視点を回し、歩き、観測を蓄積します。

## World memory

memoryへ記録するのは次だけです。

- 共通Observerを通して`get_snapshot`、`compare_block_plan`、routineが実際にlive observationとして利用したblock。`include_matches=false`で応答から省略した一致blockも含む
- 自動化が通常操作で設置・破壊し、対応するサーバー同期結果を確認したblock

画面へ映り得る全block、受信した全chunk、hidden updateを無条件に収集しません。

記録の出所は少なくとも次を区別します。

- `line_of_sight_observation`
- `interaction_confirmation`

数値のconfidenceは偽精度になるため使いません。推論はworld memoryの事実と混ぜず、LLM側のplan/beliefとして扱います。

### 更新規則

- 同じ問い合わせで再観測できた値を`current`として返す
- 視界外になった記録は値を残して`last_known`にする
- 再観測時は全state、時刻、出所を置換する
- 自分で破壊し、サーバー同期を確認した座標は`minecraft:air`として記録する
- 設置要求を送っただけでは更新しない
- doorやbed等の複数block設置は、actionとの対応が確認できた座標だけ更新する
- piston、fluid、redstone等による隠れた派生変化は、再観測できるまで更新しない
- chunk unload後もsession内の`last_known`として残す
- memory上限へ到達した場合は黙って欠落させず、`retention_policy / evicted_count / oldest_retained_tick`をstatsとして監査可能にする

## World sessionとdimension

memory keyは次の組です。

```text
(world_session_id, dimension_id, x, y, z)
```

- `world_session_id`はworld参加ごとに生成するopaque UUID
- server address、world seed、認証情報は含めない
- dimension移動は同じsession内の別namespaceとする
- disconnect/reconnectでは新しいsessionにし、旧memoryを現在値として再利用しない
- 初版はメモリ上のsession内記憶だけとし、永続化は実需が出てから検討する

## `get_snapshot`との関係

`visible_blocks` scopeはliveとmemoryの取得元を選べます。

```json
{
  "scopes": ["player", "visible_blocks"],
  "options": {
    "visible_blocks": {
      "source": "live_and_memory",
      "query": {
        "kind": "positions",
        "positions": [
          {"x": 120, "y": 65, "z": -31}
        ]
      }
    }
  }
}
```

`source`は次の3種類です。

- `live`: 現在観測できたものだけ。観測不能な要求座標は値を持たないquery outcome `not_currently_observable`
- `memory`: 最終観測値だけ
- `live_and_memory`: liveを優先し、観測不能ならmemoryを返す

問い合わせ形式は`viewport`と`positions`から始めます。denseな任意AABBの全block取得は公開しません。

`positions`は現在dimension内の座標だけを受けます。要求した各座標には必ず`current / last_known / not_currently_observable / unknown`のいずれか1 outcomeを返します。`not_currently_observable`は`source=live`で今は見えないというquery結果で、過去の記憶がないという意味ではありません。`unknown`は選択したmemory経路にも根拠がなく、`reason = never_observed | evicted | unavailable`を付けます。実装がevictionと未観測を確実に区別できない場合は`unavailable`とし、断定しません。

応答envelopeとlive sampleは同じclient tickで確定しますが、memory recordはそれぞれ固有の`observed_at_client_tick`を保持します。`observation_revision`は、そのworld sessionで公開memoryまたはlive snapshotが更新されるたびに増えるクライアント内の単調番号です。server/world全体のrevisionでも、将来の実行許可tokenでもありません。

## `compare_block_plan`との関係

設計との差分も、現在値と過去記憶を区別します。

- `match_current`
- `mismatch_current`
- `match_last_known`
- `mismatch_last_known`
- `unknown`

`match_last_known`は再計画材料にはできますが、現在の完成保証には使いません。読み取り専用の`compare_block_plan`は明示したpropertyだけによる部分一致を許し、`required=false`のdiagnostic座標を完成判定から外せます。一方、Phase 4の`apply_block_plan`（実装・受入完了）は全entryを必須targetとし、runtime registry上の全propertyを含む完全な`expected_before / expected_after`だけを受けます。

`apply_block_plan`はpreflight、各操作直前、prediction ACK後の結果、最終確認をcurrent-onlyで行い、last-knownで補いません。各place後はfreshなinbound selected-slot inventory syncも待ちます。phase完了には、最大64 targetすべてを同じclient tickにcurrentとして収集し、完全state一致かつunknown 0であることが必要です。この集合は要求targetの確認であり、通常vanilla処理による隣接block更新やgame eventを含むtarget外stateの不変証明ではありません。

密閉される内部機構は、基礎・機構・外装のようにphaseを分け、閉じる前に検査します。完成記録は「現在壁越しに検査済み」ではなく、「tick Nで機構を確認後、外装を閉じた」という証跡として扱います。

## Entityの記憶

Entityは`visible_entities`で、現在LOS/FOV内の観測とlast-knownを区別します。

- type、相対位置、距離、motion、vehicle/passenger、通常画面で分かる状態
- player識別子は返さない
- 遮蔽後は現在座標を更新せず、最後に見た位置と時刻だけを返す
- task対象が既知の封鎖routeやvehicle内でも、脱走・同定不能時は`TARGET_LOST`とする
- 壁越しの正確な現在座標や、client同期済みというだけのEntity一覧は返さない

## 湧き潰しと空間調査

`survey_area`は、入力で宣言された最大32 waypointと最大256 sampleだけを通常操作で移動・見回りし、観測できた表面と明るさをmemoryへ蓄積するroutineです。結果は次を区別します。

- `checked`
- `possibly_spawnable`
- `unknown`
- `coverage`

spawn条件はversion、dimension、server設定、追加MODで変わり得るため、client-visibleなblock/lightからの判定は常に`predicted`とします。sampleごとのcurrent、last-known、unknownとcoverageを保ち、未観測洞窟や壁裏を含む場合に「完全に湧き潰し済み」とは返しません。

## 禁止する情報経路

- 壁越し・地中・未観測座標の現在BlockState取得
- hidden座標に対する一致・不一致だけのBoolean oracle
- hidden block updateによるmemoryの自動更新
- 未ロードchunk、seed、structure、POI、mob AI内部状態の取得
- stale memoryをcurrentとして表示すること
- stale memoryだけを根拠に破壊・設置を開始すること
