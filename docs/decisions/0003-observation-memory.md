# ADR 0003: 現在観測と過去記憶を分離する

- 状態: 採用
- 日付: 2026-08-20

## 決定

World情報を`current / last_known / unknown`へ分けます。

- `current`: 今回のFOV/LOS、crosshair、通常interaction結果で確認
- `last_known`: 過去に観測、または自分の操作後にserver同期まで確認
- `unknown`: 根拠なし

観測したblockはregistry IDと全BlockState propertyを常に返します。payload制御はproperty削減ではなく、query座標数と結果件数で行います。

Wall-throughの現在state、hidden一致oracle、hidden updateによるmemory更新は許可しません。一度見た情報は時刻・出所付きでsession内memoryへ残します。

## 理由

- current viewportだけでは建築や長時間作業の空間理解に不足する
- 人間も以前見た・自分で置いた構造を記憶して作業する
- client chunk dataをそのまま公開するとX-ray/ESP相当になる
- stale情報をcurrentとして扱わなければ、記憶とfairnessを両立できる
- BlockStateを省略しないことでstairs、door、crop、redstone等の事故を減らせる

## 影響

- 全観測recordにworld session、dimension、client tick、provenance、currentnessが必要
- reconnect後の旧memoryをcurrentへ昇格させない
- sealed structureは閉鎖前phaseのserver-confirmed証跡で扱い、完成後に透視検査しない
- `compare_block_plan`はcurrent matchとlast-observed matchを区別する
- `survey_area`は通常移動と視点操作でmemoryを更新する
- 初版はsession内memoryのみで、永続world mapは作らない

詳細は[観測・記憶モデル](../observation-model.md)に定めます。
