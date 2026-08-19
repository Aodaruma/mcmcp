# くらふとぶ！ v01.2 互換性

## 確認した実環境

- Minecraft 26.2
- NeoForge 26.2.0.59
- Java 25.0.1 LTS
- 24個の外部MOD JAR
- サーバー接続成功
- Simple Voice Chatの認証・音声チャンネル初期化成功

起動ログには任意連携先がないことによるMixin警告、Windows performance counter警告、CreativeCoreのdefault style読込エラーが既存状態であります。ゲームとサーバー接続は継続しているため、これらを本MODの回帰と誤判定しないようbaselineとして記録します。ただし、新しい例外や頻度増加は失敗扱いです。

## 重要な互換性判断

| MOD群 | リスク | 方針 |
|---|---|---|
| Sodium | 中 | renderer、framebuffer、描画Mixinへ触れない |
| Emotecraft / Player Animation系 | 中 | animation stateやplayer modelを直接変更しない |
| Simple Voice Chat | 中 | 2.6.22専用adapter、開始時互換性検査、fail closed |
| JEI / Sophisticated Backpacks | 中 | PoCでは画面・コンテナ操作をしない |
| Carry On | 中 | 使用キーの意味が変わる場面を避け、対象allowlistを使う |
| Waystones | 低〜中 | テレポートUIを自動操作しない |
| PlayerRevive / Corpse | 中 | 死亡・行動不能時に停止。自動蘇生・回収は対象外 |
| mob追加MOD | 中 | 可視敵対mob接近時に停止する。自動戦闘は対象外 |
| FerriteCore | 低 | メモリ内部やMixin対象を共有しない |

## 導入形態

- `displayTest="IGNORE_ALL_VERSION"`相当を使い、サーバー側に同MODがないことを正常扱いにする。
- 物理クライアント専用エントリポイントに分離し、サーバーコンポーネントや独自payloadを登録しない。
- くらふとぶ！本体へ同梱せず、まずPrism Launcherでインスタンスを複製し、複製側へ追加する。
- manifestや既存MODを更新・置換しない。自動化MODの削除だけで元へ戻せる状態を維持する。

`displayTest`は接続画面上の互換表示を制御するもので、クライアント専用化そのものではありません。コードのside分離と、サーバーへ機能を要求しない設計を別途守ります。

## 導入済みJARの確認一覧

```text
Balm 26.2.0.6
Carry On 2.11.0
Chat Heads 1.2.8
Corpse 1.1.18
CreativeCore 2.14.16
Emotecraft 3.4.0
Entity Model Features 3.2.6
Entity Player Compatibility 2.2.0
Entity Texture Features 7.1.1
FerriteCore 9.0.0
Illager Invasion 26.2.0
JEI 30.23.0.159
Mutant Monsters 26.2.1
Nemo Inventory Sorting 1.21.2
Player Animation Lib 1.2.5
PlayerRevive 2.1.2
Puzzles Lib 26.2.3
Shogi 26.2.0.5
Sit 1.5.2
Sodium 0.9.1
Sophisticated Backpacks 3.25.90.2084
Sophisticated Core 1.4.99.2265
Simple Voice Chat 2.6.22
Waystones 26.2.0.9
```

## 他環境への展開

この文書は最初の互換性確認結果であり、自動化MODを「くらふとぶ！」専用にはしません。シングルプレイや別のNeoForge 26.2環境でも、バージョン・競合・停止処理のセルフテストに合格すれば使える構成にします。マルチプレイでは、接続先のMOD・アンチチート・ルールに合わせて利用者が有効化を判断します。
