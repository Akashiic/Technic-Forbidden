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
| GeckoLib `RawGeometryTree.traverse` (modelo de exemplo `optimus`, 2.989 ossos) | ~9% | Override de arquivo; medição abaixo |
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

## Testado e reprovado: cachear os transformers do Reika no CoreTweaks

Ideia: acrescentar à `transformersToCache` do CoreTweaks (modo LITE) os transformers do DragonAPI,
o `InterfaceVerifier` do RotaryCraft, o `EventSubscriptionTransformer` do FML e o `ContainerTransformer`
do InvTweaks.

Resultado no cliente real: com o cache quente, **crash no PreInit**
(`NoSuchFieldError: field_149755_E` em `AgriCraft BlockCrop.<init>`). A classe `Block` saiu errada.
Pelo menos um desses transformers depende de estado montado durante o boot, não só dos bytes da classe.

**Não usar.** Se algum dia for retomado, só com bissecção transformer por transformer e teste de
regressão. Um transformer não determinístico também pode corromper uma classe em silêncio.

## O que já dá para fazer

1. **Java 8 atual (Zulu/Temurin 8u4xx/5xx)** no lugar do 1.8.0_51 embutido no Technic. Nos logs do
   jogador, o boot caiu de 467 s para 367 s só com essa troca.
2. **Tirar `-XX:+AlwaysPreTouch`.** No Java 8 ele toca cada página nova do heap numa thread só, o que
   atrasa cada expansão do heap durante o boot.
3. **Pôr `-XX:+UseStringDeduplication`.** No menu há ~390 MB de texto repetido (`char[]` + `String`).
4. O cache de transformers do CoreTweaks só é salvo depois de 40 ticks dentro de um mundo, ou num
   encerramento normal. Matar o processo pelo gerenciador de tarefas logo depois do menu faz o próximo
   boot rodar com o cache desatualizado.
