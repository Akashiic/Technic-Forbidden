# Boot do TEOF 5.5: para onde vai o tempo

## Como foi medido

- **Logs reais do jogador** (Windows, `latest.log` + FMLPhaseAudit do Akashic-ThePatcher): tempo de
  cada fase do FML e os mods mais lentos de cada fase.
- **Cliente de teste** (`tools/headless-client` do AkashicThePatcher: Forge 1614, Java 8u504, Linux,
  Mesa por software) com o pack completo, gravando o boot com o **Java Flight Recorder** do próprio JDK:
  `-XX:StartFlightRecording=name=boot,settings=profile,maxsize=2g` e, no menu,
  `jcmd <pid> JFR.dump name=boot filename=boot.jfr`.
- Análise com [`ferramentas/AnalisarBoot.java`](ferramentas/AnalisarBoot.java) (roda com JDK 11+):

  ```bash
  java AnalisarBoot.java boot.jfr          # por mod, por causa, por transformer
  ```

  ```bash
  java AnalisarBoot.java boot.jfr quem 'regex'   # quem chama um trecho quente
  ```

> No JFR do Java 8, as amostras de método nativo usam outra frequência e outra regra de seleção de
> threads. Misturá-las com as amostras Java infla o que é nativo (ex.: abrir jar). Medido à parte,
> abrir os 211 jars do pack leva 0,2 s. Por isso a análise usa só as amostras Java.

## Mapa do boot (log real do jogador, 367 s com Zulu 8u512)

| Fase | Tempo | % |
|---|---:|---:|
| Antes do FML (coremods, mixins, caches do CoreTweaks, descoberta de jars) | ~55 s | 15% |
| Construction | 21 s | 6% |
| PreInit | 87 s | 24% |
| Init | 70 s | 19% |
| PostInit | 66 s | 18% |
| LoadComplete | 15 s | 4% |
| Recarga final (texturas ~24 s com o StitchLayoutCache acertando, sons/fontes ~20 s) | ~44 s | 12% |

~72% do boot é a inicialização dos mods, uma de cada vez, numa thread só.

## Onde o tempo Java da thread principal vai (JFR, só amostras Java)

| Causa | % | Situação |
|---|---:|---|
| Transformers ASM do Reika (DragonAPI/RotaryCraft) em **toda** classe carregada | ~20% | Cache testado e **reprovado** (ver abaixo) |
| Thaumcraft `ThaumcraftCraftingManager.generateTags` | 9,7% | Precisa de patch (candidato ao ThePatcher) |
| GeckoLib `RawGeometryTree.traverse` (modelo de exemplo `optimus`, 2.989 ossos) | ~9% | Override de arquivo: **−4 s** medido (abaixo) |
| Eln `CraftingRecipes.recipeExists` | 5,4% | Precisa de patch (candidato ao ThePatcher) |
| Mixins (todos) | 2,6% | Normal |

### Thaumcraft: `generateTags`

O Thaumcraft calcula os aspectos de um item a partir das receitas, de forma recursiva
(`getAspectsFromIngredients` → `generateTags` → `generateTagsFromRecipes`). Vários mods disparam isso
no PostInit ao registrar aspectos: RotaryCraft, ThermalFoundation, MFR, ChromatiCraft, ExtraUtilities,
ThermalExpansion, Thaumic Energistics e outros. Nem o TC4Tweaks nem o Salis Arcana têm opção para isso.
A correção seria memorizar resultados (inclusive os "sem aspecto") durante o carregamento.

### Eln: `recipeExists`

Para cada receita que registra, o Eln procura duplicatas na lista inteira (`Utils.areSame` em laço),
o que dá O(n²). A correção seria um índice por item de saída.

### GeckoLib: modelo de exemplo gigante

`assets/geckolib3/geo/optimus.geo.json` (5,8 MB, 2.989 ossos) vem no jar do GeckoLib e é carregado pelo
`GeckoLibCache` mesmo com `enableexamples=false`. O `RawGeometryTree.parseHierarchy` reconstrói o mapa
da árvore inteira para achar o pai de cada osso, o que dá O(n²) a O(n³). Override de 1 osso, carregado
pelo TX Loader:
[`overrides/config/txloader/load/geckolib3/geo/optimus.geo.json`](overrides/config/txloader/load/geckolib3/geo/optimus.geo.json).
Nenhum mod do pack usa esse modelo.

**Medido no cliente real** (boots alternados, cache quente):

| | Até o menu | Init do geckolib3 |
|---|---:|---:|
| Config atual (3 boots) | 322,1 / 311,6 / 313,7 s | 11,7 / 10,4 / 9,5 s |
| Com o override (2 boots) | 318,8 / 305,8 s | 6,6 / 6,7 s |

Ganho real de **~4 s** no Init do geckolib3, menos que os "9%" do JFR sugeriam: as porcentagens do JFR
são de amostras Java, não de tempo de relógio. Memória: ~3 MB a menos, sem efeito visível no total.
Seguro e barato, mas pequeno. Para aplicar, copiar o arquivo para
`config/txloader/load/geckolib3/geo/optimus.geo.json` no pack.

## Testado e reprovado: cachear os transformers do Reika no CoreTweaks

Ideia: acrescentar à `transformersToCache` do CoreTweaks (modo LITE) os transformers do DragonAPI,
o `InterfaceVerifier` do RotaryCraft, o `EventSubscriptionTransformer` do FML e o `ContainerTransformer`
do InvTweaks.

Resultado no cliente real: com o cache quente, **crash no PreInit**
(`NoSuchFieldError: field_149755_E` em `AgriCraft BlockCrop.<init>`). A classe `Block` saiu errada.
Pelo menos um desses transformers depende de estado montado durante o boot, não só dos bytes da classe.

**Não usar.** Se algum dia for retomado, só com bissecção transformer por transformer e teste de
regressão. Um transformer não determinístico também pode corromper uma classe em silêncio.

## Pré-filtro dos transformers do Reika (Akashic Leveza, experimental)

Os 6 transformers do DragonAPI/RotaryCraft fazem o parse completo de **toda** classe carregada só para
procurar uma anotação ou um nome. O Leveza pode embrulhá-los e só chamá-los quando a classe contém as
mesmas strings que eles comparam (`boot.prefiltro.reika`, ou `-Dakashic.leveza.prefiltro=true`).

**Equivalência provada:**
- **Offline:** os transformers originais rodados sobre as 107.618 classes dos 211 jars. Nenhuma classe
  pulada seria alterada (0 divergências, 0 erros). Só 2 a 388 classes por transformer são encaminhadas.
- **No cliente real:** as 93 alterações registradas pelo Reika (`DRAGONAPI ASM: …`) saem idênticas, linha
  a linha, às do pack sem o Leveza.

Três problemas apareceram no cliente real e foram corrigidos antes de chegar a esse resultado:
1. **Reflexão genérica no instalador** carregou cedo a classe `Chunk`, e o mixin do Mirai falhou com
   "loaded too early". Agora só nomes de classe e o campo `original` do proxy do CoreTweaks.
2. **Lista de delegação do Mixin:** o embrulho entrava na lista, e os transformers do DragonAPI rodavam
   2× em algumas classes (108 linhas contra 93). O embrulho agora implementa `ILegacyClassTransformer`.
3. **Mixingasm:** ele registra os nomes de classe da lista como "não confiáveis". O embrulho reproduz a
   decisão original: DragonAPI excluídos e InterfaceVerifier incluído.

**Boot (mesma instância, cache quente, alternando):**

| | Boots | Média |
|---|---|---:|
| Ligado | 290,1 / 300,4 s / crash | 295,3 s |
| Desligado | 325,1 / 293,3 / 295,3 s | 304,6 s |

O ganho ficou **dentro da variação**: as medianas são iguais (295 s). Houve 1 crash em 3 boots com o
pré-filtro ligado, de uma corrida que já existe no ThePatcher (abaixo); a mudança de tempo pode deixá-la
mais provável. Por isso o pré-filtro vem **desligado por padrão**.

## Corrida no registro paralelo de ícones do Akashic-ThePatcher (achado do teste)

Crash `Parallel block icon registration failed`: `NullPointerException` em `BlockSandStone.getIcon`,
chamado por `ganymedes01.etfuturum.blocks.BlockRedSandstoneSlab.registerBlockIcons` numa thread de
trabalho do `registerBlockIconsHybrid` (módulo AsyncCompat). A laje de arenito vermelho do Et Futurum lê
os ícones do arenito vanilla durante o próprio registro. Em paralelo, eles podem ainda não existir.

O `CompatHooks` do ThePatcher (beta.34 a beta.48) força para a thread principal só `team.chisel.`,
`com.cricketcraft.chisel.`, `mekanism.`, `powercrystals.minefactoryreloaded.` e `cofh.`. **O Et Futurum não
estava nessa lista**, e isso pode derrubar o boot de jogadores mesmo sem o Leveza: depende do tempo entre as threads.

**Corrigido no Akashic-ThePatcher 1.1.0-beta.49 "BlockIconCrossDeps"** (repositório AkashicThePatcher,
`versions/Akashic-ThePatcher-1.1.0-beta.49-BlockIconCrossDeps/`):
- Uma varredura do bytecode dos 215 jars do pack achou **28 classes de bloco** que leem o ícone de outro bloco
  durante o próprio registro: Et Futurum (lajes, muro, casca), ChromatiCraft, ReactorCraft, RotaryCraft,
  Twilight Forest, Magic Bees, Botania, Thaumic Horizons e Extra Utilities.
- Esses blocos (e subclasses) agora rodam na thread do cliente. Antes, o laço híbrido espera todos os jobs
  enviados antes deles, e os blocos que eles leem vêm antes no registro, como no vanilla.
- Kill-switch: `-Dakashic.iconCrossDeps.disable=true`.
- No cliente real, 25 das 28 classes estão no registro do pack (36 blocos), todas foram para a thread do
  cliente, e o registro dos ícones de blocos levou 2.294 ms. Na mesma instância com a beta.48, levou 2.296 a
  2.448 ms: sem custo mensurável.

## O que mais daria ganho (próximos passos)

Thaumcraft `generateTags` e Eln `recipeExists` precisam de patch. Pelo que o GeckoLib (9% no JFR → 4 s
reais) e o pré-filtro mostraram, as porcentagens do JFR superestimam bastante o tempo de relógio.
Qualquer patch novo precisa de A/B no cliente real antes de valer a pena.

## O que já dá para fazer

1. **Java 8 atual (Zulu/Temurin 8u4xx/5xx)** no lugar do 1.8.0_51 embutido no Technic. Nos logs do
   jogador, o boot caiu de 467 s para 367 s só com essa troca.
2. **Tirar `-XX:+AlwaysPreTouch`.** No Java 8 ele toca cada página nova do heap numa thread só, o que
   atrasa cada expansão do heap durante o boot.
3. **Pôr `-XX:+UseStringDeduplication`.** No menu há ~390 MB de texto repetido (`char[]` + `String`).
4. O cache de transformers do CoreTweaks só é salvo depois de 40 ticks dentro de um mundo, ou num
   encerramento normal. Matar o processo pelo gerenciador de tarefas logo depois do menu faz o próximo
   boot rodar com o cache desatualizado.
